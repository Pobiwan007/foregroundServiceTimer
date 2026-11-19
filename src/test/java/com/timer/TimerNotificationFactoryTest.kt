package com.timer

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class TimerNotificationFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = TestTimerProvider(context)
    private val factory = TimerNotificationFactory(context, provider, wallClock = Clock { NOW })

    @Before
    fun setUp() = factory.createChannels()

    @Test
    fun `running notification counts down with the system chronometer`() {
        val notification = factory.running(remainingMillis = 90_000)

        assertEquals(NOW + 90_000, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(TimerNotificationFactory.PROGRESS_CHANNEL_ID, notification.channelId)
        assertEquals(listOf("Pause", "Stop"), notification.actionTitles())
    }

    @Test
    fun `paused notification shows the frozen remaining time`() {
        val notification = factory.paused(remainingMillis = 65_000)

        assertEquals("01:05", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Resume", "Stop"), notification.actionTitles())
    }

    @Test
    fun `finished notification uses the high-importance channel and can be dismissed`() {
        val notification = factory.finished(totalMillis = 60_000)

        assertEquals(TimerNotificationFactory.COMPLETION_CHANNEL_ID, notification.channelId)
        assertEquals(NotificationCompat.CATEGORY_ALARM, notification.category)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(listOf("Replay", "Dismiss"), notification.actionTitles())
        assertEquals("Pasta is ready!", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `replay action restarts the service with the finished duration`() {
        val replay = factory.finished(totalMillis = 42_000).actions.first { it.title == "Replay" }

        val intent = shadowOf(replay.actionIntent).savedIntent
        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(42_000L, intent.getLongExtra(TimerService.EXTRA_DURATION, 0L))
        assertTrue(shadowOf(replay.actionIntent).isForegroundService)
    }

    @Test
    fun `channels have the expected importance and the completion channel is silent`() {
        val manager = context.getSystemService(NotificationManager::class.java)

        val progress = manager.getNotificationChannel(TimerNotificationFactory.PROGRESS_CHANNEL_ID)
        val completion = manager.getNotificationChannel(TimerNotificationFactory.COMPLETION_CHANNEL_ID)

        assertEquals(NotificationManager.IMPORTANCE_LOW, progress.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, completion.importance)
        assertNull(completion.sound)
    }

    @Test
    fun `content intent is requested from the provider only once`() {
        factory.running(1_000)
        factory.paused(1_000)
        factory.finished(1_000)

        assertEquals(1, provider.contentIntentCalls)
    }

    private fun Notification.actionTitles() = actions.map { it.title.toString() }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
