package com.timer

import android.graphics.Bitmap

open class TimerProgress(
    override val title: String,
    override val smallIconResource: Int,
    override val bigIcon: Bitmap?,
    val pause: String,
    val stop: String,
    val `continue`: String,
    override val largeIcon: Bitmap?
) : TimerData()

open class TimerDone(
    override val title: String,
    override val smallIconResource: Int,
    override val bigIcon: Bitmap?,
    val stop: String,
    val replay: String,
    val contentText: String,
    override val largeIcon: Bitmap?,
    val doneSound: Int
) : TimerData()