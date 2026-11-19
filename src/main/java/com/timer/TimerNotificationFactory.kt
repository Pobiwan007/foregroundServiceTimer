package com.timer

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Builds every notification the timer shows and owns their channels.
 *
 * Has no state of its own besides lazily cached [PendingIntent]s, so the service can ask for a
 * fresh notification on every state change without re-creating intents.
 *
 * @param wallClock wall-clock time (`System::currentTimeMillis`), needed because the
 *   notification chronometer counts towards an absolute [Notification.when].
 */
internal class TimerNotificationFactory(
    private val context: Context,
    private val provider: TimerProvider,
    private val wallClock: Clock = Clock(System::currentTimeMillis)
) {
    private val contentIntent by lazy { provider.contentIntent() }
    private val pauseIntent by lazy { serviceIntent(TimerService.ACTION_PAUSE) }
    private val resumeIntent by lazy { serviceIntent(TimerService.ACTION_RESUME) }
    private val replayIntent by lazy { serviceIntent(TimerService.ACTION_REPLAY) }
    private val stopIntent by lazy { serviceIntent(TimerService.ACTION_STOP) }

    /** Creates (or updates the names of) both channels. Safe to call repeatedly. */
    fun createChannels() {
        val progress = NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(provider.channelName)
            .setShowBadge(false)
            .build()
        // High importance so the "done" notification pops up as heads-up. The sound is played by
        // CompletionSoundPlayer with alarm attributes, so the channel itself stays silent.
        val completion = NotificationChannelCompat.Builder(COMPLETION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(provider.completionChannelName)
            .setSound(null, null)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(progress, completion))
    }

    /**
     * Running countdown. The system chronometer counts down on its own, so this notification
     * only has to be posted when the countdown starts or resumes, not on every tick.
     */
    fun running(remainingMillis: Long): Notification {
        val content = provider.progressContent
        return progressBuilder(content.appearance)
            .setWhen(wallClock.nowMillis() + remainingMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(0, content.pauseLabel, pauseIntent)
            .addAction(0, content.stopLabel, stopIntent)
            .build()
    }

    /** Paused countdown: a frozen remaining time instead of a chronometer. */
    fun paused(remainingMillis: Long): Notification {
        val content = provider.progressContent
        return progressBuilder(content.appearance)
            .setContentText(formatRemainingTime(remainingMillis))
            .setShowWhen(false)
            .addAction(0, content.resumeLabel, resumeIntent)
            .addAction(0, content.stopLabel, stopIntent)
            .build()
    }

    /** Finished countdown, on the high-importance channel so it shows as heads-up. */
    fun finished(): Notification {
        val content = provider.doneContent
        return baseBuilder(content.appearance, COMPLETION_CHANNEL_ID)
            .setContentText(content.contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, content.replayLabel, replayIntent)
            .addAction(0, content.stopLabel, stopIntent)
            .setDeleteIntent(stopIntent)
            .build()
    }

    private fun progressBuilder(appearance: NotificationAppearance): NotificationCompat.Builder =
        baseBuilder(appearance, PROGRESS_CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

    private fun baseBuilder(appearance: NotificationAppearance, channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setContentTitle(appearance.title)
            .setSmallIcon(appearance.smallIconRes)
            .setLargeIcon(appearance.largeIcon)
            .setContentIntent(contentIntent)
            .apply {
                appearance.bigPicture?.let { setStyle(NotificationCompat.BigPictureStyle().bigPicture(it)) }
            }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            TimerService.intent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val PROGRESS_CHANNEL_ID = "com.timer.TIMER_CHANNEL"
        const val COMPLETION_CHANNEL_ID = "com.timer.TIMER_DONE_CHANNEL"
        const val PROGRESS_NOTIFICATION_ID = 7_001
        const val COMPLETION_NOTIFICATION_ID = 7_002
    }
}
