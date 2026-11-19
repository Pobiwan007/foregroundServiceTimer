package com.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.timer.TimerNotificationFactory.Companion.COMPLETION_NOTIFICATION_ID
import com.timer.TimerNotificationFactory.Companion.PROGRESS_NOTIFICATION_ID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that runs a countdown, shows its progress in a notification with
 * pause / resume / stop actions, and plays a sound when it ends.
 *
 * Observe [state] from any screen; control the service with [start], [pause], [resume], [stop].
 * App-specific texts, icons and sound come from a [TimerProvider] bound in the app's Hilt graph.
 */
@AndroidEntryPoint
class TimerService : LifecycleService() {

    @Inject
    lateinit var timerProvider: TimerProvider

    private val notifications by lazy { TimerNotificationFactory(this, timerProvider) }
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val countdown by lazy { CountdownTimer(lifecycleScope, Clock(SystemClock::elapsedRealtime)) }
    private val soundPlayer by lazy { CompletionSoundPlayer(this) }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        lifecycleScope.launch {
            countdown.state
                .drop(1) // skip the initial Idle, which would otherwise stop the service right away
                .onEach { _state.value = it }
                // The state ticks every second for the UI; the notification only changes with the
                // kind of state, because the running one counts down with the system chronometer.
                .distinctUntilChanged { old, new -> old::class == new::class }
                .collect(::render)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
                if (duration > 0L && promoteToForeground(duration)) countdown.start(duration) else stopSelf()
            }
            ACTION_PAUSE -> countdown.pause()
            ACTION_RESUME -> countdown.resume()
            ACTION_REPLAY -> {
                val total = (countdown.state.value as? TimerState.Finished)?.totalMillis
                if (total != null && promoteToForeground(total)) countdown.restart()
            }
            ACTION_STOP -> countdown.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        soundPlayer.release()
        _state.value = TimerState.Idle
        super.onDestroy() // cancels lifecycleScope and the countdown with it
    }

    private fun render(state: TimerState) {
        when (state) {
            is TimerState.Running -> notify(PROGRESS_NOTIFICATION_ID, notifications.running(state.remainingMillis))
            is TimerState.Paused -> notify(PROGRESS_NOTIFICATION_ID, notifications.paused(state.remainingMillis))
            is TimerState.Finished -> {
                // A separate notification on the high-importance channel, so it pops up as heads-up.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notify(COMPLETION_NOTIFICATION_ID, notifications.finished())
                playDoneSound()
            }
            TimerState.Idle -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(COMPLETION_NOTIFICATION_ID)
                stopSelf()
            }
        }
    }

    /** @return `false` if the system refused to start a foreground service (e.g. from background on API 31+). */
    private fun promoteToForeground(remainingMillis: Long): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            PROGRESS_NOTIFICATION_ID,
            notifications.running(remainingMillis),
            type
        )
        notificationManager.cancel(COMPLETION_NOTIFICATION_ID)
        true
    } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException on API 31+
        Log.w(TAG, "Cannot start foreground service", e)
        false
    }

    @SuppressLint("MissingPermission") // checked in canPostNotifications()
    private fun notify(id: Int, notification: Notification) {
        if (canPostNotifications()) notificationManager.notify(id, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun playDoneSound() {
        val soundRes = timerProvider.doneContent.doneSound ?: return
        soundPlayer.play(soundRes, timerProvider.soundVolume)
    }

    companion object {
        private const val TAG = "TimerService"

        const val ACTION_START = "com.timer.action.START"
        const val ACTION_PAUSE = "com.timer.action.PAUSE"
        const val ACTION_RESUME = "com.timer.action.RESUME"
        const val ACTION_REPLAY = "com.timer.action.REPLAY"
        const val ACTION_STOP = "com.timer.action.STOP"
        const val EXTRA_DURATION = "com.timer.extra.DURATION"

        private val _state = MutableStateFlow<TimerState>(TimerState.Idle)

        /** Current timer state; survives configuration changes and can be collected from any screen. */
        val state: StateFlow<TimerState> = _state.asStateFlow()

        fun start(context: Context, durationMillis: Long) {
            require(durationMillis > 0) { "Duration must be positive, was $durationMillis" }
            ContextCompat.startForegroundService(
                context,
                Intent(context, TimerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_DURATION, durationMillis)
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            // Only meaningful while the service is alive; avoids starting it just to stop it.
            if (_state.value == TimerState.Idle) return
            context.startService(intent(context, action))
        }

        internal fun intent(context: Context, action: String): Intent =
            Intent(context, TimerService::class.java).setAction(action)
    }
}
