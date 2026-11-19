package com.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide timer state shared by [TimerService] (the only writer) and [ServiceTimerController].
 *
 * A singleton because the service itself is one per process and its instances come and go;
 * it is internal, so consumers only see it through [TimerController.state].
 */
internal object TimerStateStore {
    private val _state = MutableStateFlow<TimerState>(TimerState.Idle)
    val state: StateFlow<TimerState> = _state.asStateFlow()

    fun update(state: TimerState) {
        _state.value = state
    }
}
