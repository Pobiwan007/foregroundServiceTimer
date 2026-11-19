package com.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CountdownTimerTest {

    /** Extra time added to the clock to simulate the device sleeping / a stalled thread. */
    private var clockSkew = 0L

    private fun TestScope.timer() = CountdownTimer(
        scope = backgroundScope,
        clock = { testScheduler.currentTime + clockSkew }
    )

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `counts down every second and finishes`() = runTest {
        val timer = timer()

        timer.start(3_000)
        runCurrent()
        assertEquals(TimerState.Running(3_000, 3_000), timer.state.value)

        advance(1_000)
        assertEquals(TimerState.Running(2_000, 3_000), timer.state.value)

        advance(2_000)
        assertEquals(TimerState.Finished(3_000), timer.state.value)
    }

    @Test
    fun `pause freezes remaining time and resume continues from it`() = runTest {
        val timer = timer()
        timer.start(5_000)
        runCurrent()

        advance(1_500)
        timer.pause()
        assertEquals(TimerState.Paused(3_500, 5_000), timer.state.value)

        advance(10_000)
        assertEquals(TimerState.Paused(3_500, 5_000), timer.state.value)

        timer.resume()
        runCurrent()
        assertEquals(TimerState.Running(3_500, 5_000), timer.state.value)

        advance(500) // re-aligns to whole seconds after resume
        assertEquals(TimerState.Running(3_000, 5_000), timer.state.value)

        advance(3_000)
        assertEquals(TimerState.Finished(5_000), timer.state.value)
    }

    @Test
    fun `remaining time follows the clock, not the number of ticks`() = runTest {
        val timer = timer()
        timer.start(5_000)
        runCurrent()

        clockSkew = 1_500 // 1.5 s passed without any tick being delivered
        advance(1_000)

        assertEquals(TimerState.Running(2_500, 5_000), timer.state.value)
    }

    @Test
    fun `start after finish runs the full duration again`() = runTest {
        val timer = timer()
        timer.start(2_000)
        runCurrent()
        advance(2_000)
        assertEquals(TimerState.Finished(2_000), timer.state.value)

        timer.start(2_000)
        runCurrent()

        assertEquals(TimerState.Running(2_000, 2_000), timer.state.value)
    }

    @Test
    fun `stop resets to Idle and cancels ticking`() = runTest {
        val timer = timer()
        timer.start(2_000)
        runCurrent()

        timer.stop()
        advance(5_000)

        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test
    fun `pause and resume are no-ops in Idle`() = runTest {
        val timer = timer()

        timer.pause()
        timer.resume()

        assertEquals(TimerState.Idle, timer.state.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive duration is rejected`() = runTest {
        timer().start(0)
    }
}
