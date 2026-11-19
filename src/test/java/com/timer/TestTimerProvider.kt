package com.timer

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Application used by Robolectric tests (see `robolectric.properties`). */
class TestApplication : Application(), TimerProviderOwner {
    override val timerProvider: TimerProvider by lazy { TestTimerProvider(this) }
}

class TestTimerProvider(private val context: Context) : TimerProvider {
    private val appearance = NotificationAppearance(title = "Pasta", smallIconRes = android.R.drawable.ic_lock_idle_alarm)

    override val progressContent = TimerProgressContent(appearance, "Pause", "Resume", "Stop")
    override val doneContent = TimerDoneContent(appearance, "Pasta is ready!", "Replay", "Dismiss")

    var contentIntentCalls = 0
        private set

    override fun contentIntent(): PendingIntent {
        contentIntentCalls++
        return PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
    }
}
