package com.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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

    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val countdown by lazy { CountdownTimer(lifecycleScope, Clock(SystemClock::elapsedRealtime)) }
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lifecycleScope.launch {
            // drop(1): skip the initial Idle, which would otherwise stop the service right away
            countdown.state.drop(1).collect { state ->
                _state.value = state
                render(state)
            }
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
        mediaPlayer?.release()
        mediaPlayer = null
        _state.value = TimerState.Idle
        super.onDestroy() // cancels lifecycleScope and the countdown with it
    }

    private fun render(state: TimerState) {
        when (state) {
            is TimerState.Running -> notify(buildProgressNotification(state.remainingMillis, isRunning = true))
            is TimerState.Paused -> notify(buildProgressNotification(state.remainingMillis, isRunning = false))
            is TimerState.Finished -> {
                // Keep the "done" notification (with Replay) but let the system reclaim the service priority.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                notify(buildDoneNotification())
                playDoneSound()
            }
            TimerState.Idle -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID)
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
            NOTIFICATION_ID,
            buildProgressNotification(remainingMillis, isRunning = true),
            type
        )
        true
    } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException on API 31+
        Log.w(TAG, "Cannot start foreground service", e)
        false
    }

    @SuppressLint("MissingPermission") // checked in canPostNotifications()
    private fun notify(notification: Notification) {
        if (canPostNotifications()) notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun playDoneSound() {
        val soundRes = timerProvider.doneContent.doneSound ?: return
        val volume = timerProvider.soundVolume.coerceIn(0f, 1f)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, soundRes)?.apply {
            setVolume(volume, volume)
            setOnCompletionListener { player ->
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
            }
            start()
        }
    }

    private fun buildProgressNotification(remainingMillis: Long, isRunning: Boolean): Notification {
        val content = timerProvider.progressContent
        return baseBuilder(content.appearance)
            .setContentText(formatRemainingTime(remainingMillis))
            .apply {
                if (isRunning) {
                    addAction(0, content.pauseLabel, serviceIntent(ACTION_PAUSE))
                } else {
                    addAction(0, content.resumeLabel, serviceIntent(ACTION_RESUME))
                }
            }
            .addAction(0, content.stopLabel, serviceIntent(ACTION_STOP))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun buildDoneNotification(): Notification {
        val content = timerProvider.doneContent
        return baseBuilder(content.appearance)
            .setContentText(content.contentText)
            .addAction(0, content.replayLabel, serviceIntent(ACTION_REPLAY))
            .addAction(0, content.stopLabel, serviceIntent(ACTION_STOP))
            .setOngoing(false)
            .setDeleteIntent(serviceIntent(ACTION_STOP))
            .build()
    }

    private fun baseBuilder(appearance: NotificationAppearance): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(appearance.title)
            .setSmallIcon(appearance.smallIconRes)
            .setLargeIcon(appearance.largeIcon)
            .apply {
                appearance.bigPicture?.let { setStyle(NotificationCompat.BigPictureStyle().bigPicture(it)) }
            }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(timerProvider.contentIntent())

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(timerProvider.channelName)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TimerService"
        private const val CHANNEL_ID = "com.timer.TIMER_CHANNEL"
        private const val NOTIFICATION_ID = 7_001

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
            context.startService(Intent(context, TimerService::class.java).setAction(action))
        }
    }
}
