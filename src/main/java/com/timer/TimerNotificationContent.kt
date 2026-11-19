package com.timer

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes

/** Visual part shared by the "in progress" and "done" notifications. */
public data class NotificationAppearance(
    public val title: String,
    @DrawableRes public val smallIconRes: Int,
    public val largeIcon: Bitmap? = null,
    public val bigPicture: Bitmap? = null
)

/** Notification shown while the timer is running or paused. */
public data class TimerProgressContent(
    public val appearance: NotificationAppearance,
    public val pauseLabel: String,
    public val resumeLabel: String,
    public val stopLabel: String
)

/** Notification shown when the timer has finished. */
public data class TimerDoneContent(
    public val appearance: NotificationAppearance,
    public val contentText: String,
    public val replayLabel: String,
    public val stopLabel: String,
    @RawRes public val doneSound: Int? = null
)
