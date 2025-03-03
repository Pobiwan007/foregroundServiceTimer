package com.timer

sealed class TimerState(val idEvent: Long?) {
    data object StartTimer: TimerState(Long.MIN_VALUE)
    data object PausedTimer: TimerState(Long.MAX_VALUE)
    data object InProgressTimer: TimerState(null)
    data object FinishedTimer: TimerState(0L)
}