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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Thin orchestrator: routes intents to [CountdownTimer], publishes its state to [TimerStateStore]
 * and delegates rendering to [TimerNotificationFactory] and [CompletionSoundPlayer].
 *
 * Lifecycle: foreground while running or paused; once finished it posts the "done" notification,
 * plays the sound and stops itself. Replay / Stop on that notification start it again.
 *
 * Controlled through [TimerController]. App-specific texts, icons and sound come from the
 * [TimerProvider] of the host [android.app.Application] (see [TimerProviderOwner]).
 */
class TimerService : LifecycleService() {

    private val timerProvider: TimerProvider by lazy { TimerProviderOwner.from(application) }

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
                .onEach { TimerStateStore.update(it) }
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
        if (TimerStateStore.state.value !is TimerState.Finished) TimerStateStore.update(TimerState.Idle)
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
        TimerStateStore.update(TimerState.Idle)
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

        internal const val ACTION_START = "com.timer.action.START"
        internal const val ACTION_PAUSE = "com.timer.action.PAUSE"
        internal const val ACTION_RESUME = "com.timer.action.RESUME"
        internal const val ACTION_STOP = "com.timer.action.STOP"
        internal const val EXTRA_DURATION = "com.timer.extra.DURATION"

        internal fun intent(context: Context, action: String): Intent =
            Intent(context, TimerService::class.java).setAction(action)

        internal fun startIntent(context: Context, durationMillis: Long): Intent =
            intent(context, ACTION_START).putExtra(EXTRA_DURATION, durationMillis)
    }
}
