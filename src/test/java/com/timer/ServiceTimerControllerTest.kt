package com.timer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ServiceTimerControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val controller = TimerController(context)

    @After
    fun tearDown() = TimerStateStore.update(TimerState.Idle)

    @Test
    fun `start sends a foreground start command with the duration`() {
        controller.start(60_000)

        val intent = shadowOf(context).nextStartedService
        assertEquals(TimerService.ACTION_START, intent.action)
        assertEquals(60_000L, intent.getLongExtra(TimerService.EXTRA_DURATION, 0L))
        assertEquals(TimerService::class.java.name, intent.component?.className)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `start rejects a non-positive duration`() {
        controller.start(0)
    }

    @Test
    fun `commands are not sent while idle`() {
        controller.pause()
        controller.resume()
        controller.stop()

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `commands are forwarded while a countdown exists`() {
        TimerStateStore.update(TimerState.Running(1_000, 2_000))

        controller.pause()
        controller.stop()

        assertEquals(TimerService.ACTION_PAUSE, shadowOf(context).nextStartedService.action)
        assertEquals(TimerService.ACTION_STOP, shadowOf(context).nextStartedService.action)
    }

    @Test
    fun `state mirrors the shared store`() {
        TimerStateStore.update(TimerState.Paused(500, 1_000))

        assertEquals(TimerState.Paused(500, 1_000), controller.state.value)
    }
}
