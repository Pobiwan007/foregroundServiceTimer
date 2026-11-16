package com.timer

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes

/** Visual part shared by the "in progress" and "done" notifications. */
data class NotificationAppearance(
    val title: String,
    @DrawableRes val smallIconRes: Int,
    val largeIcon: Bitmap? = null,
    val bigPicture: Bitmap? = null
)

/** Notification shown while the timer is running or paused. */
data class TimerProgressContent(
    val appearance: NotificationAppearance,
    val pauseLabel: String,
    val resumeLabel: String,
    val stopLabel: String
)

/** Notification shown when the timer has finished. */
data class TimerDoneContent(
    val appearance: NotificationAppearance,
    val contentText: String,
    val replayLabel: String,
    val stopLabel: String,
    @RawRes val doneSound: Int? = null
)
