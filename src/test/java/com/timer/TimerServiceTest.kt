package com.timer

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class TimerServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val controller: ServiceController<TimerService> = Robolectric.buildService(TimerService::class.java).create()

    @Before
    fun setUp() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @After
    fun tearDown() {
        controller.destroy()
        TimerStateStore.update(TimerState.Idle)
    }

    private fun send(action: String, durationMillis: Long? = null) {
        val intent = if (durationMillis != null) {
            TimerService.startIntent(context, durationMillis)
        } else {
            TimerService.intent(context, action)
        }
        controller.withIntent(intent).startCommand(0, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private val service get() = controller.get()

    @Test
    fun `start promotes the service to the foreground and publishes Running`() {
        send(TimerService.ACTION_START, durationMillis = 3_000)

        assertEquals(TimerState.Running(3_000, 3_000), TimerStateStore.state.value)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(TimerNotificationFactory.PROGRESS_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
    }

    @Test
    fun `state keeps ticking while the countdown runs`() {
        send(TimerService.ACTION_START, durationMillis = 3_000)

        advance(1_000)

        assertEquals(TimerState.Running(2_000, 3_000), TimerStateStore.state.value)
    }

    @Test
    fun `pause and resume are reflected in state`() {
        send(TimerService.ACTION_START, durationMillis = 5_000)
        advance(1_000)

        send(TimerService.ACTION_PAUSE)
        assertEquals(TimerState.Paused(4_000, 5_000), TimerStateStore.state.value)

        send(TimerService.ACTION_RESUME)
        assertEquals(TimerState.Running(4_000, 5_000), TimerStateStore.state.value)
    }

    @Test
    fun `finishing posts the done notification and stops the service`() {
        send(TimerService.ACTION_START, durationMillis = 2_000)

        advance(2_000)

        assertEquals(TimerState.Finished(2_000), TimerStateStore.state.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNotNull(shadowOf(notificationManager).getNotification(TimerNotificationFactory.COMPLETION_NOTIFICATION_ID))
        assertNull(shadowOf(notificationManager).getNotification(TimerNotificationFactory.PROGRESS_NOTIFICATION_ID))
    }

    @Test
    fun `stop resets the state, removes notifications and stops the service`() {
        send(TimerService.ACTION_START, durationMillis = 2_000)
        advance(2_000)

        send(TimerService.ACTION_STOP)

        assertEquals(TimerState.Idle, TimerStateStore.state.value)
        assertNull(shadowOf(notificationManager).getNotification(TimerNotificationFactory.COMPLETION_NOTIFICATION_ID))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a command that finds no countdown does not leave the service running`() {
        send(TimerService.ACTION_PAUSE)

        assertEquals(TimerState.Idle, TimerStateStore.state.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `missing TimerProviderOwner fails with a clear message`() {
        val error = runCatching { TimerProviderOwner.from(Application()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("TimerProviderOwner"))
    }
}
