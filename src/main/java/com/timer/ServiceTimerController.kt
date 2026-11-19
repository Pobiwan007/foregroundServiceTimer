package com.timer

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/** [TimerController] that sends commands to [TimerService] and reads [TimerStateStore]. */
internal class ServiceTimerController(private val context: Context) : TimerController {

    override val state: StateFlow<TimerState> = TimerStateStore.state

    override fun start(durationMillis: Long) {
        require(durationMillis > 0) { "Duration must be positive, was $durationMillis" }
        ContextCompat.startForegroundService(context, TimerService.startIntent(context, durationMillis))
    }

    override fun pause() = send(TimerService.ACTION_PAUSE)

    override fun resume() = send(TimerService.ACTION_RESUME)

    override fun stop() = send(TimerService.ACTION_STOP)

    private fun send(action: String) {
        // Only meaningful while a countdown exists; avoids starting the service just to stop it.
        if (state.value == TimerState.Idle) return
        context.startService(TimerService.intent(context, action))
    }
}
