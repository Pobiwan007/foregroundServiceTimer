package com.timer

import android.app.Application

/**
 * Implemented by the host [Application] to hand the library its [TimerProvider].
 *
 * This keeps the library free of any DI framework: wire the provider with Hilt, Koin or by hand.
 * The service resolves it from the Application, so it also works when the process is recreated
 * by a notification action.
 */
public interface TimerProviderOwner {
    public val timerProvider: TimerProvider

    public companion object {
        internal fun from(application: Application): TimerProvider =
            (application as? TimerProviderOwner)?.timerProvider
                ?: error("${application::class.java.name} must implement ${TimerProviderOwner::class.java.name}")
    }
}
