# foregroundServiceTimer

[![CI](https://github.com/Pobiwan007/foregroundServiceTimer/actions/workflows/ci.yml/badge.svg)](https://github.com/Pobiwan007/foregroundServiceTimer/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Pobiwan007/foregroundServiceTimer.svg)](https://jitpack.io/#Pobiwan007/foregroundServiceTimer)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)
![targets](https://img.shields.io/badge/Android%2014%2B-FGS%20types-blue)

A countdown timer that keeps running in the background as a **foreground service**,
with an interactive notification (Pause / Resume / Stop, Replay when done) and a completion sound.

## Features

- `TimerService.state: StateFlow<TimerState>`: observe the timer from any screen or ViewModel
- notification actions: Pause ↔ Resume, Stop; Replay and Dismiss when finished
- **drift-free**: remaining time is computed from `SystemClock.elapsedRealtime()`, not by counting ticks
- Android 14+ ready: `specialUse` foreground service type, `POST_NOTIFICATIONS` check, `ServiceCompat`
- no leaks: work runs in `lifecycleScope`, and `MediaPlayer` is released after playback
- all texts, icons and the sound come from the host app via a `TimerProvider` (Hilt)

## Architecture

```
TimerService (LifecycleService, Android)       CountdownTimer (pure Kotlin)
 ├─ onStartCommand(action) ─────────────────▶  start / pause / resume / restart / stop
 ├─ collects countdown.state ◀───────────────  StateFlow<TimerState>
 ├─ renders notification + plays sound            Idle | Running | Paused | Finished
 └─ TimerService.state (companion StateFlow) ──▶ UI
```

All countdown logic lives in `CountdownTimer`, which has no Android dependencies and is unit-tested
with virtual time (`kotlinx-coroutines-test`), including pause/resume alignment and drift compensation.

## Usage

```kotlin
// 1. Provide app-specific content through Hilt
@Module
@InstallIn(SingletonComponent::class)
object TimerModule {
    @Provides
    fun timerProvider(@ApplicationContext context: Context): TimerProvider = object : TimerProvider {
        private val appearance = NotificationAppearance(title = "Pasta", smallIconRes = R.drawable.ic_timer)

        override val progressContent = TimerProgressContent(appearance, "Pause", "Resume", "Stop")
        override val doneContent = TimerDoneContent(appearance, "Pasta is ready!", "Replay", "Dismiss", R.raw.ding)

        override fun contentIntent(): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// 2. Control it
TimerService.start(context, durationMillis = 8 * 60_000L)
TimerService.pause(context)
TimerService.resume(context)
TimerService.stop(context)

// 3. Observe it
val state by TimerService.state.collectAsStateWithLifecycle()
when (val s = state) {
    is TimerState.Running -> Text(formatRemainingTime(s.remainingMillis))
    is TimerState.Paused -> Text("Paused at ${formatRemainingTime(s.remainingMillis)}")
    is TimerState.Finished -> Text("Done!")
    TimerState.Idle -> Unit
}
```

On Android 13+ request `POST_NOTIFICATIONS` at runtime before starting the timer; without it the timer still
runs, but the notification is not shown.

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}

// build.gradle.kts (the app must use Hilt)
dependencies {
    implementation("com.github.Pobiwan007:foregroundServiceTimer:v1.0.0")
}
```

## Build & test

```bash
./gradlew testReleaseUnitTest assembleRelease
```
