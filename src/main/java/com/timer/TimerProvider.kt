package com.timer

import android.app.PendingIntent

interface TimerProvider {
    val timerProgress: TimerProgress
    val timerDone: TimerDone
    fun getIntent(): PendingIntent
}