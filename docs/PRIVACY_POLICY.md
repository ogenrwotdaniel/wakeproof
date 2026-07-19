# WakeProof Privacy Policy

**Effective date:** 19 July 2026
**Developer:** Ogenrwot Daniel
**Contact:** ogenrwotdaniel@gmail.com

WakeProof is an alarm clock and study companion app. This policy explains what
data the app handles, where it goes, and the choices you have. The short
version: **WakeProof has no accounts, no ads, no analytics, and no server of
its own. Almost everything stays on your phone.** The only data that ever
leaves your device is what you explicitly send to the AI provider you
configure, described below.

## Data stored only on your device

The following never leaves your phone and is deleted when you uninstall the
app:

- **Alarms, settings and preferences** (alarm times, focus goals, sound
  choices, subjects, distraction limits).
- **Habit history** (which alarms you completed, focus minutes, streaks,
  flashcards and their review schedule, exams, parked thoughts).
- **Screen-time statistics.** With your permission (Usage access), WakeProof
  reads your device's app-usage statistics to show you your own screen time
  and to nudge you when an app crosses a limit you set. These statistics are
  processed on-device only.
- **Calendar events.** With your permission, WakeProof reads event titles and
  times from your device calendar to show your agenda. It never modifies your
  calendar and never transmits calendar data.
- **Pushup verification video.** The camera feed used to count pushups is
  processed entirely on-device (Google ML Kit pose detection). No video or
  image from pushup verification is stored or transmitted.
- **Your AI API key**, stored encrypted (Android EncryptedSharedPreferences).

## Data sent to an AI provider — only when you enable it

Some optional features call an AI service. They only work after **you** create
an API key with a provider and paste it into the app, so no data flows until
you opt in. You choose the provider in Settings:

- **Google Gemini API** (Google LLC) — see Google's privacy policy:
  https://policies.google.com/privacy
- **Kimi / Moonshot AI** — see https://www.moonshot.ai (or platform.moonshot.ai)

What is sent, per feature, over an encrypted connection (HTTPS):

| Feature | Data sent |
|---|---|
| Water-drinking verification | The two photos you take of your water container |
| Flashcard drafting | The notes text you paste, or the notes photo you pick |
| Study coach chat | The messages you type in the chat |
| Weekly report | Aggregate numbers only (minutes per subject, daily screen-time totals, top-3 app names with minutes, your focus goal) |

WakeProof sends this data directly from your phone to the provider you chose;
the developer of WakeProof never receives, stores or sees any of it. The
provider processes it to return a result; their own retention and processing
are governed by their privacy policy. Don't include personal information you
wouldn't share with that provider (e.g. avoid photographing notes containing
other people's personal data).

If you never add an API key, the app makes **no network requests at all**, and
water verification falls back to an on-device honor mode.

## Permissions summary

| Permission | Why WakeProof asks |
|---|---|
| Camera | Counting pushups (on-device) and photographing your water container |
| Notifications, exact alarms, full-screen alarms | Making the alarm ring reliably, including on the lock screen |
| Usage access (special) | Showing you your own screen-time stats and enforcing your app limits |
| Display over other apps (special) | The floating Pomodoro timer bubble |
| Calendar (read) | Showing your agenda so you can tick events off |
| Internet | Only for the optional AI features above |

Every permission is optional except notifications/alarms (the app's core); the
related feature simply stays off if you decline.

## Data sharing and selling

WakeProof does not sell, rent, or share your personal data with anyone, other
than the direct AI-provider transmission you opt into, described above. There
are no third-party analytics or advertising SDKs in the app.

## Data retention and deletion

All app data lives on your device. To delete everything: uninstall the app, or
use Android Settings → Apps → WakeProof → Storage → Clear data. Data you
export yourself (CSV) is under your control. Data already processed by your
chosen AI provider is subject to that provider's policy and account tools.

## Children

WakeProof is not directed at children under 13 and does not knowingly collect
personal information from children.

## Changes to this policy

Material changes will be posted at this URL with an updated effective date.

## Contact

Questions or requests: **ogenrwotdaniel@gmail.com**
