package com.timer

/** State of the countdown, observable through [TimerController.state]. */
sealed interface TimerState {
    data object Idle : TimerState

    data class Running(val remainingMillis: Long, val totalMillis: Long) : TimerState

    data class Paused(val remainingMillis: Long, val totalMillis: Long) : TimerState

    data class Finished(val totalMillis: Long) : TimerState
}
