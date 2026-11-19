package com.timer

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Builds every notification the timer shows and owns their channel.
 *
 * Has no state besides lazily cached [PendingIntent]s, so the service can ask for a fresh
 * notification on every state change without re-creating intents.
 */
internal class TimerNotificationFactory(
    private val context: Context,
    private val provider: TimerProvider
) {
    private val contentIntent by lazy { provider.contentIntent() }
    private val pauseIntent by lazy { serviceIntent(TimerService.ACTION_PAUSE) }
    private val resumeIntent by lazy { serviceIntent(TimerService.ACTION_RESUME) }
    private val replayIntent by lazy { serviceIntent(TimerService.ACTION_REPLAY) }
    private val stopIntent by lazy { serviceIntent(TimerService.ACTION_STOP) }

    /** Creates (or updates the name of) the channel. Safe to call repeatedly. */
    fun createChannels() {
        val channel = NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(provider.channelName)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun progress(remainingMillis: Long, isRunning: Boolean): Notification {
        val content = provider.progressContent
        return baseBuilder(content.appearance)
            .setContentText(formatRemainingTime(remainingMillis))
            .apply {
                if (isRunning) {
                    addAction(0, content.pauseLabel, pauseIntent)
                } else {
                    addAction(0, content.resumeLabel, resumeIntent)
                }
            }
            .addAction(0, content.stopLabel, stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    fun finished(): Notification {
        val content = provider.doneContent
        return baseBuilder(content.appearance)
            .setContentText(content.contentText)
            .addAction(0, content.replayLabel, replayIntent)
            .addAction(0, content.stopLabel, stopIntent)
            .setOngoing(false)
            .setDeleteIntent(stopIntent)
            .build()
    }

    private fun baseBuilder(appearance: NotificationAppearance): NotificationCompat.Builder =
        NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setContentTitle(appearance.title)
            .setSmallIcon(appearance.smallIconRes)
            .setLargeIcon(appearance.largeIcon)
            .apply {
                appearance.bigPicture?.let { setStyle(NotificationCompat.BigPictureStyle().bigPicture(it)) }
            }
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            TimerService.intent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val PROGRESS_CHANNEL_ID = "com.timer.TIMER_CHANNEL"
        const val PROGRESS_NOTIFICATION_ID = 7_001
    }
}
