package com.timer

import android.graphics.Bitmap

abstract class TimerData {
    abstract val title: String
    abstract val smallIconResource: Int
    abstract val bigIcon: Bitmap?
    abstract val largeIcon: Bitmap?
}