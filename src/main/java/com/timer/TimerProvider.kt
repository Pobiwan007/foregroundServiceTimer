package com.timer

import android.app.PendingIntent

/**
 * Everything app-specific the service needs (texts, icons, sound, the screen to open on
 * notification tap). Handed to the library by the host Application via [TimerProviderOwner].
 */
public interface TimerProvider {
    public val progressContent: TimerProgressContent
    public val doneContent: TimerDoneContent

    /** Opened when the user taps the notification. */
    public fun contentIntent(): PendingIntent

    /** Volume of the completion sound in `0f..1f`. */
    public val soundVolume: Float get() = 0.5f

    /** User-visible name of the channel for the running / paused countdown. */
    public val channelName: String get() = "Timer"

    /** User-visible name of the high-importance channel for the "done" notification. */
    public val completionChannelName: String get() = "Timer finished"
}
