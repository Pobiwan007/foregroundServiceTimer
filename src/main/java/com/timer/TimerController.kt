package com.timer

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Controls the foreground countdown and exposes its state.
 *
 * Depend on this interface from UI / ViewModels and replace it with a fake in tests.
 * Create the real implementation with [TimerController] (the factory function).
 */
public interface TimerController {

    /** Current state; ticks once per second while running. Safe to collect from any screen. */
    public val state: StateFlow<TimerState>

    /**
     * Starts a countdown of [durationMillis] as a foreground service, replacing a running one.
     *
     * @throws IllegalArgumentException if [durationMillis] is not positive.
     * @throws IllegalStateException if the app is in the background on API 31+
     *   (`ForegroundServiceStartNotAllowedException`): start the timer from a visible screen.
     */
    public fun start(durationMillis: Long)

    /** Pauses a running countdown; no-op otherwise. */
    public fun pause()

    /** Resumes a paused countdown; no-op otherwise. */
    public fun resume()

    /** Stops the countdown, removes its notifications and stops the service; no-op when idle. */
    public fun stop()
}

/** Creates a [TimerController] backed by the library's foreground service. */
public fun TimerController(context: Context): TimerController =
    ServiceTimerController(context.applicationContext)
