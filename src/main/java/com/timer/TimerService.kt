package com.timer

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.common.sharedPreference.BaseSharedPreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Timer
import javax.inject.Inject

const val TIMER_VALUE = "TIMER_VALUE"
const val NOT_VALUE = "NOT_VALUE"

@AndroidEntryPoint
open class TimerService : LifecycleService() {
    @Inject
    lateinit var timerProvider: TimerProvider

    @Inject
    lateinit var baseSharedPreferences: BaseSharedPreference

    private val binder = LocalBinder()
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val timerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var timeLeftInMillis: Long = 0L
    private var countDownTimer: Job? = null
    private var isTimerRunning = false
    private var totalTimerMillis: Long = 0L

    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                intent.getLongExtra(EXTRA_TIME, 0L).also { value ->
                    Log.e("StartedService", value.toString())
                    timeLeftInMillis = value
                    totalTimerMillis = value
                }
                startForegroundService()
                startTimer()
            }

            ACTION_STOP -> {
                stopTimer()
                stopSelf()
            }

            ACTION_FINISH -> {
                finish()
            }

            ACTION_RESUME -> {
                resumeTimer()
            }

            ACTION_PAUSE -> {
                pauseTimer()
            }

            ACTION_REPLAY -> {
                timeLeftInMillis = totalTimerMillis
                startTimer()
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }


    private fun pauseTimer() {
        if (isTimerRunning) {
            sendTimeUpdate(TimerState.PausedTimer.idEvent)
            countDownTimer?.cancel()
            isTimerRunning = false
            updateNotification()
        }
    }

    private fun resumeTimer() {
        if (!isTimerRunning) {
            sendTimeUpdate()
            startCountdown()
            isTimerRunning = true
        }
    }

    private fun startTimer() {
        if (isTimerRunning) return
        isTimerRunning = true
        startCountdown()
    }

    private fun startCountdown() {
        countDownTimer = timerScope.launch {
            while (timeLeftInMillis > 0) {
                if (timeLeftInMillis % 60000 == 0L) {
                    sendTimeUpdate()
                }
                delay(1000)
                timeLeftInMillis -= 1000

                updateNotification()
            }
            stopTimer()
        }
    }

    private fun sendTimeUpdate(timerState: Long? = null) {
        val intent = Intent(ACTION_UPDATE).apply {
            if (timerState == null)
                putExtra(EXTRA_TIME, timeLeftInMillis)
            else
                putExtra(EXTRA_TIME, timerState)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopTimer() {
        countDownTimer?.cancel()
        isTimerRunning = false
        stopForeground(false)
        if (timeLeftInMillis > 0) {
            sendTimeUpdate(TimerState.StartTimer.idEvent)
            stopSelf()
        } else {
            playNotificationSound()
            updateNotification()
            sendTimeUpdate(TimerState.FinishedTimer.idEvent)
        }
    }

    private fun finish(){
        countDownTimer?.cancel()
        isTimerRunning = false
        stopForeground(false)
        stopSelf()
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = buildProgressNotification()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                e.printStackTrace()
            }
        }

    }

    private fun updateNotification() {
        val isGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                baseSharedPreferences.getBoolean(NOT_VALUE, true)

        if (!isGranted) return

        val notification = if (timeLeftInMillis > 0) {
            buildProgressNotification()
        } else {
            buildDoneNotification()
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun playNotificationSound() {
        val mediaPlayer = MediaPlayer.create(this, timerProvider.timerDone.doneSound)
        val volume = baseSharedPreferences.getFloat(TIMER_VALUE, 0.5f)
        Log.e("TimerService", (volume).toString())

        mediaPlayer.setVolume(volume, volume)
        mediaPlayer.start()
    }

    private fun formatTime(timeMillis: Long): String {
        val minutes = (timeMillis / 1000) / 60
        val seconds = (timeMillis / 1000) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildDoneNotification(): Notification {
        val pendingIntent = timerProvider.getIntent()

        val timerData = timerProvider.timerDone
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(timerData.title)
            .setSmallIcon(timerData.smallIconResource, 0)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(timerData.largeIcon)
            )
            .setLargeIcon(timerData.bigIcon)
            .setContentText(timerData.contentText)
            .addAction(0, timerData.replay, getPendingIntent(ACTION_REPLAY))
            .addAction(0, timerData.stop, getPendingIntent(ACTION_FINISH))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(false)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildProgressNotification(): Notification {
        val pendingIntent = timerProvider.getIntent()

        val timerData = timerProvider.timerProgress
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(timerData.title)
            .setSmallIcon(timerData.smallIconResource, 0)
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(timerData.largeIcon)
            )
            .setLargeIcon(timerData.bigIcon)
            .setContentText(formatTime(timeLeftInMillis))
            .addAction(
                0,
                timerData.pause,
                if (isTimerRunning) getPendingIntent(ACTION_PAUSE) else null
            )
            .addAction(
                0,
                timerData.`continue`,
                if (!isTimerRunning) getPendingIntent(ACTION_RESUME) else null
            )
            .addAction(0, timerData.stop, getPendingIntent(ACTION_STOP))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Таймер", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows timer progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "TIMER_CHANNEL"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PAUSE = "com.cooker.timer.ACTION_PAUSE"
        const val ACTION_REPLAY = "REPLAY_TIMER"
        const val ACTION_RESUME = "com.cooker.timer.ACTION_RESUME"
        const val ACTION_START = "START_TIMER"
        const val ACTION_STOP = "STOP_TIMER"
        const val EXTRA_TIME = "EXTRA_TIME"
        const val ACTION_FINISH = "ACTION_FINISH"
        const val ACTION_UPDATE = "UPDATE_TIME"

        fun startService(context: Context, timeMillis: Long) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TIME, timeMillis)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseService(context: Context, timerValue: Long){
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TIME, timerValue)
            }
            context.startService(intent)
        }
        fun stopService(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
