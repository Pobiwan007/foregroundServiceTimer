# foregroundServiceTimer

[![CI](https://github.com/Pobiwan007/foregroundServiceTimer/actions/workflows/ci.yml/badge.svg)](https://github.com/Pobiwan007/foregroundServiceTimer/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Pobiwan007/foregroundServiceTimer.svg)](https://jitpack.io/#Pobiwan007/foregroundServiceTimer)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)
![targets](https://img.shields.io/badge/Android%2014%2B-FGS%20types-blue)

An Android library: a countdown timer that keeps running in the background as a **foreground service**,
with an interactive notification (Pause / Resume / Stop, Replay when done) and a completion sound.

## Features

- `TimerController` interface with `state: StateFlow<TimerState>`: inject it into a ViewModel, fake it in tests
- **no DI framework required**: the host app hands over its content through a tiny `TimerProviderOwner`
  interface, so it works with Hilt, Koin or plain Kotlin
- **drift-free**: remaining time is computed from `SystemClock.elapsedRealtime()`, not by counting ticks
- **battery-friendly notification**: while running, the system chronometer counts down,
  so the notification is not re-posted every second (the `StateFlow` still ticks for your UI)
- **heads-up on completion**: a separate high-importance channel for the "done" notification;
  the sound uses alarm audio attributes, so it follows the alarm volume
- **no lingering service**: after the timer ends the service stops itself; Replay / Stop on the
  "done" notification still work
- Android 14+ ready: `specialUse` foreground service type, `POST_NOTIFICATIONS` check, `ServiceCompat`

## Architecture

```
            your UI / ViewModel
                   │  depends on
                   ▼
        «interface» TimerController ◀── FakeTimerController (your tests)
                   ▲
                   │ implements
        ServiceTimerController ──── intents ────▶ TimerService (LifecycleService, thin orchestrator)
                   │                                 │
                   │ reads              writes       ├─▶ CountdownTimer          pure Kotlin, Clock-injected
                   └──────▶ TimerStateStore ◀────────┤
                            StateFlow<TimerState>    ├─▶ TimerNotificationFactory  channels + notifications
                                                     ├─▶ CompletionSoundPlayer     MediaPlayer lifecycle
                                                     └─▶ TimerProvider ◀── your Application (TimerProviderOwner)
```

| Class | Responsibility |
|---|---|
| `TimerController` | Public API: `start / pause / resume / stop` + `state`. The only thing UI code needs. |
| `TimerProvider` / `TimerProviderOwner` | App-specific texts, icons, sound and tap intent, resolved from your `Application`. |
| `TimerService` | Routes commands, publishes state, decides *when* to show what. No building of notifications. |
| `CountdownTimer` | Countdown logic, no Android dependencies, unit-tested with virtual time. |
| `TimerNotificationFactory` | Builds the running / paused / done notifications and their channels. |
| `CompletionSoundPlayer` | Plays the completion sound once and releases the `MediaPlayer`. |

Only `TimerController`, `TimerState`, `TimerProvider`, `TimerProviderOwner`, the notification content
classes and `formatRemainingTime` are public; everything else is `internal`
(the module is compiled in Kotlin [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors)).

## Usage

### 1. Provide app-specific content

Implement `TimerProviderOwner` in your `Application`:

```kotlin
class App : Application(), TimerProviderOwner {
    override val timerProvider: TimerProvider by lazy { PastaTimerProvider(this) }
}

class PastaTimerProvider(private val context: Context) : TimerProvider {
    private val appearance = NotificationAppearance(title = "Pasta", smallIconRes = R.drawable.ic_timer)

    override val progressContent = TimerProgressContent(appearance, "Pause", "Resume", "Stop")
    override val doneContent = TimerDoneContent(appearance, "Pasta is ready!", "Replay", "Dismiss", R.raw.ding)

    override fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}
```

<details>
<summary>With Hilt</summary>

```kotlin
@HiltAndroidApp
class App : Application(), TimerProviderOwner {
    @Inject override lateinit var timerProvider: TimerProvider
}

@Module
@InstallIn(SingletonComponent::class)
object TimerModule {
    @Provides fun timerProvider(@ApplicationContext context: Context): TimerProvider = PastaTimerProvider(context)
    @Provides @Singleton fun timerController(@ApplicationContext context: Context): TimerController = TimerController(context)
}
```
</details>

<details>
<summary>With Koin</summary>

```kotlin
class App : Application(), TimerProviderOwner {
    override val timerProvider: TimerProvider by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@App)
            modules(module {
                single<TimerProvider> { PastaTimerProvider(androidContext()) }
                single { TimerController(androidContext()) }
            })
        }
    }
}
```
</details>

### 2. Control and observe it

```kotlin
class TimerViewModel(private val timer: TimerController) : ViewModel() {
    val state: StateFlow<TimerState> = timer.state

    fun start(minutes: Int) = timer.start(durationMillis = minutes * 60_000L)
    fun pause() = timer.pause()
    fun resume() = timer.resume()
    fun stop() = timer.stop()
}

// Compose
val state by viewModel.state.collectAsStateWithLifecycle()
when (val s = state) {
    is TimerState.Running -> Text(formatRemainingTime(s.remainingMillis))
    is TimerState.Paused -> Text("Paused at ${formatRemainingTime(s.remainingMillis)}")
    is TimerState.Finished -> Text("Done!")
    TimerState.Idle -> Unit
}
```

Without a DI framework, create the controller with `TimerController(context)`.

### Behaviour notes

- **Notification permission**: on Android 13+ request `POST_NOTIFICATIONS` before starting. Without it the
  timer still runs and the sound still plays, but the notifications are not shown.
- **Start from the foreground**: on Android 12+ a foreground service cannot be started while the app is in the
  background; `start()` then throws `ForegroundServiceStartNotAllowedException` (an `IllegalStateException`).
  Notification actions (Replay) are exempt.
- **Starting while running** replaces the current countdown with the new duration.
- **Finished** stays the state until the user presses Stop / dismisses the notification, or starts again.
  The service itself has already stopped by then.

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}

// build.gradle.kts
dependencies {
    implementation("com.github.Pobiwan007:foregroundServiceTimer:master-SNAPSHOT")
}
```

> There is no release tag yet, so JitPack builds from a branch (`master-SNAPSHOT`) or a commit hash
> (`com.github.Pobiwan007:foregroundServiceTimer:<commit-sha>`). Once a `vX.Y.Z` tag is pushed, depend on
> that tag instead for reproducible builds.

## Build & test

```bash
./gradlew testReleaseUnitTest lint assembleRelease
```

Unit tests run on the JVM: `CountdownTimer` with virtual time (`kotlinx-coroutines-test`),
and the service, notification factory and controller with Robolectric.
