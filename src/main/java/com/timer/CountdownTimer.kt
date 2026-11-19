package com.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coroutine-based countdown with pause/resume, free of Android dependencies.
 *
 * Remaining time is derived from a monotonic [clock] and an end timestamp instead of
 * decrementing a counter on every tick, so the countdown does not drift when ticks are
 * delayed (busy main thread, Doze, etc.). Ticks are aligned to whole [tickMillis] of the
 * remaining time, so the displayed seconds stay stable after pause/resume.
 *
 * Not thread-safe: confine all calls and the [scope] to a single thread (the main thread in
 * `TimerService`), which keeps the implementation free of locks.
 *
 * @param clock monotonic time source (`SystemClock::elapsedRealtime` on Android).
 */
class CountdownTimer(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val tickMillis: Long = 1_000L
) {
    private val _state = MutableStateFlow<TimerState>(TimerState.Idle)
    val state: StateFlow<TimerState> = _state.asStateFlow()

    private var job: Job? = null
    private var endAt = 0L

    fun start(durationMillis: Long) {
        require(durationMillis > 0) { "Duration must be positive, was $durationMillis" }
        run(remainingMillis = durationMillis, totalMillis = durationMillis)
    }

    fun pause() {
        val running = _state.value as? TimerState.Running ?: return
        job?.cancel()
        _state.value = TimerState.Paused(remainingMillis = remaining(), totalMillis = running.totalMillis)
    }

    fun resume() {
        val paused = _state.value as? TimerState.Paused ?: return
        run(remainingMillis = paused.remainingMillis, totalMillis = paused.totalMillis)
    }

    fun stop() {
        job?.cancel()
        _state.value = TimerState.Idle
    }

    private fun run(remainingMillis: Long, totalMillis: Long) {
        job?.cancel()
        endAt = clock.nowMillis() + remainingMillis
        _state.value = TimerState.Running(remainingMillis, totalMillis)
        job = scope.launch {
            while (true) {
                val left = remaining()
                if (left <= 0L) break
                _state.value = TimerState.Running(left, totalMillis)
                val untilNextTick = left % tickMillis
                delay(if (untilNextTick == 0L) tickMillis else untilNextTick)
            }
            _state.value = TimerState.Finished(totalMillis)
        }
    }

    private fun remaining(): Long = (endAt - clock.nowMillis()).coerceAtLeast(0L)
}
