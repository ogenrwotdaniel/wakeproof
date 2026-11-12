# WakeProof

**The alarm you can't talk your way out of — and the focus & study system that guards the rest of your day.**

WakeProof is an Android alarm, deep-focus, spaced-repetition, and distraction-wall system across **Android 14+ phones, adaptive tablets, Wear OS smartwatches, and Ubuntu GNOME Wayland**. Alarms carry no dismiss button: ringing stops only after on-device camera pose detection counts your push-ups, you photograph the day's written plan, you answer your due flashcards, or you complete an unbroken run of mental arithmetic.

- **Project page:** https://ogenrwotdaniel.github.io/wakeproof/
- **Full documentation (PDF):** https://ogenrwotdaniel.github.io/wakeproof/wakeproof_documentation.pdf
- **Privacy policy:** https://ogenrwotdaniel.github.io/wakeproof/privacy/

---

## Core Features

### Morning Alarms & Verification Missions
- **Reliable alarm delivery:** `AlarmManager.setAlarmClock()` + lock-screen full-screen intents + foreground `mediaPlayback` service + Direct-Boot mirror (`DirectBootAlarmStore` fires even before first post-reboot unlock) + weekly repeat bitmasks.
- **Single-device ringing role:** Alarm schedules and daily goals sync across phone and tablet, while `AlarmDevicePolicy` keeps companion tablets silent by default.
- **Push-up mission (100% on-device):** CameraX (front/back flippable) + ML Kit Pose Detection elbow-angle state machine (5–10 reps) with progressive ringtone ducking as reps complete. Zero images or video are stored or uploaded.
- **Session summaries:** After every focus session the phone asks for ten minutes writing down what was studied and a photo of the page, with the focus wall held until it is kept (30 minutes at most). Photos stay in an on-device library (the newest 90 days); nothing reads, judges or uploads them.
- **Flashcard review mission:** Up to 10 due SM-2 cards, each graded as on the Flashcards screen, so the alarm does the day's spaced repetition. With no card due it falls back to the sums.
- **Mental math mission:** Camera-free dark-room mode requiring 1–10 consecutive arithmetic problems; any mistake resets the run and draws a new problem.
- **Locked strict morning alarms & watch backup:** Optional uneditable morning slots survive reboots with zero snooze and arm a `+2 min` backup alarm on a paired Wear OS watch.

### Nuclear Focus, Audio & Study Suite
- **Nuclear Focus sessions:** Fixed 1 h, 1 h 30, 2 h, and 3 h recipes — or exact-length sessions timed to a calendar event — with **no pause and no stop button**, walling non-study apps across phone, tablet, and watch (breaks included) while engaging Priority Do Not Disturb. A session started on one device shows on the others with **Continue here**, which runs the same countdown there; a block finished on two devices is recorded once.
- **Focus Forest & streaks:** Every completed focus block grows a tree (`FocusTree`) that withers if you drift to a non-study app; daily streaks track verified focus minutes against a synced daily goal (up to 12 h).
- **Circular stereo soundscapes & Spotify:** 7 K-weighted loudness-matched 44.1 kHz stereo OGG Vorbis loops synthesized circularly without crossfade seams (0–60 s squared-curve fades), plus SDK-free Spotify playlist control.
- **SM-2 flashcards, exams & offline coach:** Classic SM-2 spaced repetition with optional Gemini 3.8 Flash card drafting from notes text/photos, exam countdowns, parked thoughts, raw event-folded screen-time analytics, and a 100% on-device weekly report.
- **Exam-driven targets, recall and balance:** The daily focus goal and review-alarm card count rise by themselves in the two weeks before an exam; every finished focus block asks for two lines of recall that become flashcards; the weekly report weighs each subject's hours against its credits and scores how often the phone was down by 20:00.

### Distraction Walls, Minimalist Launcher & Multi-Device Sync
- **Fixed schedule walls:** Social, video, messaging, portal, and browser apps open directly during your Monday–Thursday `08:20–08:50` window (the `15:00–08:20` bedtime wall lifts as it starts) and stay shut Friday–Sunday, backed by notification/media-pause guards, per-app daily caps, and a `20:00–03:00` night sleep wall. Every wall is fixed in code, and a cap can only ever be tightened.
- **Minimalist home screen:** Optional dark text-only launcher (`ROLE_HOME`) featuring permanent utility pins, today's live calendar agenda with one-tap focus launch, a 4-second reshuffling app list with no search bar, and a 45-second mindful pause on capped apps.
- **Wear OS companion (`:wear`):** Standalone wrist Nuclear Focus timer, ProtoLayout *Stats* and *WakeProof Today* tiles, watch-face complication, phone alarm mirror, and direct Wi-Fi/LTE Firestore sync.
- **Opt-in Cloud Sync & WakeProof Clip:** Dual-clock tombstone Firestore sync (`docs/CLOUD_SYNC.md`) plus end-to-end AES-256-GCM clipboard & screenshot sharing across Android and Ubuntu GNOME Wayland (`docs/CLIPBOARD.md`).

---

## Tech Stack & Requirements

- **Modules:** `:app` (Android 14+, `minSdk = 34`, `targetSdk = 36`, `compileSdk = 37`) and `:wear` (Wear OS, `minSdk = 30`, `targetSdk = 35`)
- **Build:** Gradle 9.8.2 wrapper, AGP 9.5.1 with built-in Kotlin 2.4 (do **not** apply `org.jetbrains.kotlin.android`)
- **Architecture:** Kotlin Coroutines + Flow, Jetpack Compose + Material 3 Adaptive, Hilt, Room v5 (WAL) + DataStore, WorkManager, Glance AppWidget
- **Vision & AI:** CameraX + ExifInterface, ML Kit Pose Detection & Barcode Scanning, optional Google Gemini (`gemini-3.8-flash`) via Retrofit 3 / OkHttp 5

## Building & Testing

```bash
./gradlew :app:assembleDebug :wear:assembleDebug          # build phone/tablet + watch APKs
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest  # unit test suites
./gradlew :app:lintDebug :wear:lintDebug                  # Android lint
```

Cloud Sync requires your own `app/google-services.json` (see [`docs/CLOUD_SYNC.md`](docs/CLOUD_SYNC.md)); builds without it compile cleanly with Cloud Sync disabled. Optional AI card drafting and study chat accept a user-supplied Google Gemini API key in **Settings**.

## License

WakeProof is free software licensed under the [GNU General Public License v3.0](LICENSE).
