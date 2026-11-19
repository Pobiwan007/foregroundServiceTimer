package com.timer

/** State of the countdown, observable through [TimerController.state]. */
public sealed interface TimerState {
    public data object Idle : TimerState

    public data class Running(public val remainingMillis: Long, public val totalMillis: Long) : TimerState

    public data class Paused(public val remainingMillis: Long, public val totalMillis: Long) : TimerState

    public data class Finished(public val totalMillis: Long) : TimerState
}
