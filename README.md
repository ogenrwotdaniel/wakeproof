# WakeProof

An Android alarm app that makes you **prove you're awake** before the alarm stops.
Dismissing an alarm requires camera-verified pushups or drinking a glass of water —
no more half-asleep snoozing.

Built for Samsung Galaxy S25 Edge, Android 14+.

**Project page:** https://ogenrwotdaniel.github.io/wakeproof/ ·
**Privacy policy:** https://ogenrwotdaniel.github.io/wakeproof/privacy/

## Features

### ⏰ Reliable alarm engine
- `AlarmManager.setAlarmClock()` for exact, Doze-proof firing
- Full-screen intent notification that takes over the lock screen
- Foreground service (media playback) keeps the ringtone alive
- Survives reboots, time/timezone changes, and app updates (Direct Boot–aware
  alarm mirror + rescheduling receivers)
- Built-in reliability check that flags Samsung battery-optimization traps

### 💪 Pushup verification
- Front camera (CameraX) + ML Kit Pose Detection, fully on-device — no network
- Elbow-angle state machine counts real reps; configurable rep goal

### 💧 Water verification
- Take two photos: a full glass, then an empty one
- Photos are judged by the Kimi (Moonshot) vision model, which returns a JSON verdict
- Honor-mode fallback when no API key or network is available (marked with an
  asterisk in your stats)

### 🚨 Emergency dismiss
Every alarm screen has an escape hatch: press and hold for 60 seconds. It only
unlocks after 3 failed verifications or when the camera/network is unavailable,
so it can't be used to cheat.

### 🍅 Pomodoro study mode
- Work/break timer running in a foreground service
- Optional AI study chat (Kimi) during breaks

### 📊 Dashboard & stats
- Wake-up streak with a 7-day history chart
- A day counts only when pushups are verified and all waters are logged;
  skipping breaks the streak

## Tech stack

- Kotlin, Jetpack Compose, Material 3, single-activity Compose Navigation
- MVVM + Repository pattern, Hilt DI, Room, DataStore
- CameraX + ML Kit Pose Detection (on-device)
- Retrofit/OkHttp client for the Kimi API (OpenAI-compatible,
  `https://api.moonshot.ai/v1`, model `kimi-k2.5`)
- API key entered in Settings, stored in `EncryptedSharedPreferences`
- Coroutines + Flow throughout; no RxJava, no XML layouts

## Requirements

- Android 14+ (minSdk 34, targetSdk 36), compileSdk 37
- Gradle 9.4.1 wrapper, AGP 9.2.1 with built-in Kotlin
  (do **not** apply `org.jetbrains.kotlin.android`)
- A Moonshot (Kimi) API key for water verification and study chat — optional;
  the app falls back to honor mode without one

## Building

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # unit tests (pushup counter, Kimi client)
./gradlew lintDebug            # lint
```

## Setup on device

1. Install the debug APK and walk through onboarding (notification permission,
   full-screen intent, battery optimization exemption).
2. Optionally add your Kimi API key in **Settings → API key** to enable water
   verification and study chat.
3. Run the reliability check from onboarding to confirm alarms can fire on
   your device.

## License

WakeProof is free software, licensed under the
[GNU General Public License v3.0](LICENSE). You may use, study, modify and
share it; copies and modified versions must stay under the same license.
