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
 * Lifecycle: foreground while running or paused; once finished it posts the "done" notification,
 * plays the sound and stops itself. Replay / Stop on that notification start it again.
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
    private val soundPlayer by lazy { CompletionSoundPlayer(this) }
    private val countdown by lazy { CountdownTimer(lifecycleScope, Clock(SystemClock::elapsedRealtime)) }
    private var isPlayingSound = false

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        lifecycleScope.launch {
            countdown.state
                .drop(1) // the initial Idle of a fresh countdown must not overwrite a Finished state
                .onEach { _state.value = it }
                // The UI ticks every second; the notification only changes with the kind of state.
                .distinctUntilChanged { old, new -> old::class == new::class }
                .collect(::render)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_DURATION, 0L))
            ACTION_PAUSE -> countdown.pause()
            ACTION_RESUME -> countdown.resume()
            ACTION_STOP -> shutDown()
        }
        // A command that found nothing to act on (e.g. Pause after the process was restarted)
        // must not leave an idle started service behind.
        if (countdown.state.value == TimerState.Idle && !isPlayingSound) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        soundPlayer.release()
        // Keep Finished so the UI can still show "done" while its notification is visible.
        if (_state.value !is TimerState.Finished) _state.value = TimerState.Idle
        super.onDestroy() // cancels lifecycleScope and the countdown with it
    }

    /** Starting while a countdown is already running replaces it with the new duration. */
    private fun start(durationMillis: Long) {
        // Must call startForeground() even for a bogus duration: the caller used startForegroundService().
        if (!promoteToForeground(notifications.running(durationMillis.coerceAtLeast(0L)))) return
        if (durationMillis <= 0L) return
        notificationManager.cancel(COMPLETION_NOTIFICATION_ID)
        countdown.start(durationMillis)
    }

    private fun render(state: TimerState) {
        when (state) {
            is TimerState.Running -> notify(PROGRESS_NOTIFICATION_ID, notifications.running(state.remainingMillis))
            is TimerState.Paused -> notify(PROGRESS_NOTIFICATION_ID, notifications.paused(state.remainingMillis))
            is TimerState.Finished -> onFinished(state)
            TimerState.Idle -> Unit // handled by shutDown()
        }
    }

    private fun onFinished(state: TimerState.Finished) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notify(COMPLETION_NOTIFICATION_ID, notifications.finished(state.totalMillis))
        val sound = timerProvider.doneContent.doneSound
        if (sound == null) {
            stopSelf()
        } else {
            isPlayingSound = true
            soundPlayer.play(sound, timerProvider.soundVolume) {
                isPlayingSound = false
                if (countdown.state.value is TimerState.Finished) stopSelf()
            }
        }
    }

    private fun shutDown() {
        countdown.stop()
        soundPlayer.release()
        isPlayingSound = false
        _state.value = TimerState.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(COMPLETION_NOTIFICATION_ID)
        stopSelf()
    }

    /**
     * @return `false` if the system refused to start a foreground service. On API 31+ this is
     *   `ForegroundServiceStartNotAllowedException`, a subclass of [IllegalStateException].
     */
    private fun promoteToForeground(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, notification, type)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Cannot start foreground service", e)
        stopSelf()
        false
    }

    /**
     * Without POST_NOTIFICATIONS (API 33+) the timer still runs and the foreground notification is
     * still registered (visible in the Task Manager), but regular notifications are dropped.
     */
    @SuppressLint("MissingPermission") // checked in canPostNotifications()
    private fun notify(id: Int, notification: Notification) {
        if (canPostNotifications()) notificationManager.notify(id, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TimerService"

        const val ACTION_START = "com.timer.action.START"
        const val ACTION_PAUSE = "com.timer.action.PAUSE"
        const val ACTION_RESUME = "com.timer.action.RESUME"
        const val ACTION_STOP = "com.timer.action.STOP"
        const val EXTRA_DURATION = "com.timer.extra.DURATION"

        private val _state = MutableStateFlow<TimerState>(TimerState.Idle)

        /** Current timer state; survives configuration changes and can be collected from any screen. */
        val state: StateFlow<TimerState> = _state.asStateFlow()

        fun start(context: Context, durationMillis: Long) {
            require(durationMillis > 0) { "Duration must be positive, was $durationMillis" }
            ContextCompat.startForegroundService(context, startIntent(context, durationMillis))
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            // Only meaningful while a countdown exists; avoids starting the service just to stop it.
            if (_state.value == TimerState.Idle) return
            context.startService(intent(context, action))
        }

        internal fun intent(context: Context, action: String): Intent =
            Intent(context, TimerService::class.java).setAction(action)

        internal fun startIntent(context: Context, durationMillis: Long): Intent =
            intent(context, ACTION_START).putExtra(EXTRA_DURATION, durationMillis)
    }
}
