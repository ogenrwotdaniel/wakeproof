# WakeProof Privacy Policy

**Effective date:** 27 October 2026
**Developer:** Ogenrwot Daniel
**Contact:** ogenrwotdaniel@gmail.com

WakeProof is an alarm clock and study companion app. This policy explains what
data the app handles, where it goes, and the choices you have. The short
version: **WakeProof has no ads and no analytics, and everything stays on your
phone unless you turn on one of two optional features.** Those two are cloud
sync (which needs you to sign in) and the AI features (which need you to supply
your own API key). Both are off until you switch them on, and both are
described below.

## Data stored only on your device

The following never leaves your phone and is deleted when you uninstall the
app:

- **Alarms, settings and preferences** (alarm times, focus goals, sound
  choices, subjects, distraction limits).
- **Habit history** (which alarms you completed, focus minutes, streaks,
  flashcards and their review schedule, exams, parked thoughts).
- **Screen-time statistics.** With your permission (Usage access), WakeProof
  reads your device's app-usage statistics to show you your own screen time and
  to act on the daily limits you set — either a notification, or blocking the
  app from opening for the rest of the day, whichever you chose for that app.
  The same on-device check holds social, video and messaging apps behind a
  five-minute wait, and keeps apps outside your focus list shut while a focus
  session runs. These statistics are processed on-device only.
- **Your installed apps.** WakeProof reads the list of apps installed on your
  phone to let you set daily limits, choose study apps, and — if you make
  WakeProof your home screen — show your apps. During a focus session it
  checks which app is on screen (using Usage access) to keep apps outside your
  focus list shut and, with "Grow a tree" on, to tell whether you left for a
  distracting app. None of this is stored beyond
  your own settings or sent anywhere.
- **Calendar events.** With your permission, WakeProof reads event titles and
  times from your device calendar to show your agenda — in the app, and on the
  WakeProof home screen if you use it. It never modifies your
  calendar and never transmits calendar data.
- **Pushup verification video.** The camera feed used to count pushups is
  processed entirely on-device (Google ML Kit pose detection). No video or
  image from pushup verification is stored or transmitted.
- **Your AI API key**, stored encrypted (Android EncryptedSharedPreferences).
- **Your paired watch.** If you install the WakeProof watch app, your phone
  sends it a small snapshot — streak, focus minutes and goal, water count and
  target, and the current timer — over Google's on-device Wearable Data Layer.
  This travels between your own two devices and reaches no server of ours.

## Optional cloud sync — only when you sign in

WakeProof can keep your data the same across your own devices (for example a
phone and a tablet). This is **off by default**. It starts only when you sign in
under Settings → Sync across devices, and stops when you sign out.

- **Signing in** uses your Google account. WakeProof receives your Google
  account identifier, email address and display name, and uses them for one
  purpose: to know which private storage area is yours. There is no profile, no
  public presence, and no way for another user to find you.
- **What is synced:** your alarms, your completion history and streak, your
  flashcards and their review schedule, your exams, and your parked thoughts.
- **What is *not* synced:** your settings and preferences, your API key, your
  screen-time statistics, and your calendar data. These stay on each device.
- **Where it is stored:** Google Firebase (Firestore and Firebase
  Authentication), operated by Google LLC, in a private area readable only by
  your signed-in account. See <https://firebase.google.com/support/privacy> and
  Google's privacy policy at <https://policies.google.com/privacy>.
- **Who can see it:** you. Access rules restrict every record to the account
  that created it. The developer of WakeProof does not read your synced data,
  and it is never sold, shared or used for advertising.
- **Turning it off:** sign out in Settings. Your data stays on the device; it
  simply stops mirroring.
- **Deleting it:** email **ogenrwotdaniel@gmail.com** from the address you
  signed in with and your stored data and account record will be deleted.

If you never sign in, none of the above applies and no account data is
collected.

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
| Water-drinking verification — **second opinion only** | The two photos you take of your water container, and only when the on-device check did not accept the pair and you have configured a key |
| Flashcard drafting | The notes text you paste, or the notes photo you pick |
| Study coach chat | The messages you type in the chat |

That is the whole list. In particular your **weekly report** and the
**motivational lines** after an alarm are written on your device from your own
numbers; no statistics, screen-time totals or app names are sent to an AI
provider, with or without a key.

**Water photos now stay on the device by default.** The water line is read by
computer vision running on your phone — no key, no network, no upload. A photo
pair leaves the device only if that on-device check did not accept it *and* you
have configured an API key, in which case the model is asked for a second
opinion before you are shown a rejection. Turning off "Read the water level on
this device" in Settings sends every pair instead. Either way, WakeProof stores
no photograph: they are held in memory for the comparison and discarded.

WakeProof sends this data directly from your phone to the provider you chose;
the developer of WakeProof never receives, stores or sees any of it. The
provider processes it to return a result; their own retention and processing
are governed by their privacy policy. Don't include personal information you
wouldn't share with that provider (e.g. avoid photographing notes containing
other people's personal data).

If you never add an API key and never sign in for sync, the app makes **no
network requests at all** — and water verification still works, on the device.

## Permissions summary

| Permission | Why WakeProof asks |
|---|---|
| Camera | Counting pushups (on-device) and photographing your water container |
| Notifications, exact alarms, full-screen alarms | Making the alarm ring reliably, including on the lock screen |
| Usage access (special) | Showing you your own screen-time stats, applying the daily app limits you set (notify, or block for the rest of the day), the five-minute wait before social, video and messaging apps, and keeping apps outside your focus list shut during a focus session |
| Display over other apps (special) | The floating Pomodoro timer bubble, the block, wait and focus-session screens shown over other apps, and opening the alarm screen while you use other apps |
| Calendar (read) | Showing your agenda so you can tick events off |
| Home app (role, optional) | Only if you choose WakeProof as your home screen in Settings; switch back any time in Android's default-apps settings |
| Internet | Only for the optional cloud sync and AI features above |

Every permission is optional except notifications/alarms (the app's core); the
related feature simply stays off if you decline.

## Data sharing and selling

WakeProof does not sell or rent your personal data to anyone, and does not
share it except with the two processors you opt into: the cloud-sync provider
(Google Firebase) if you sign in, and the AI provider you configure if you add
an API key. Both are described above. There are no third-party analytics or
advertising SDKs in the app.

## Data retention and deletion

App data lives on your device. To delete it: uninstall the app, or use Android
Settings → Apps → WakeProof → Storage → Clear data.

If you enabled cloud sync, a copy also exists under your account. Signing out
stops the mirroring but does not erase that copy; to have it deleted, email
**ogenrwotdaniel@gmail.com** from the address you signed in with. Data you
export yourself (CSV) is under your control. Data already processed by your
chosen AI provider is subject to that provider's policy and account tools.

## Children

WakeProof is not directed at children under 13 and does not knowingly collect
personal information from children.

## Changes to this policy

Material changes will be posted at this URL with an updated effective date.

**27 October 2026** — removed music playback from your own phone: WakeProof no
longer reads music files or asks for the music and audio permission. Study
music is Spotify again, as before 7 October — WakeProof only opens the Spotify
app (or a playlist link you saved) and sends it play/pause/skip keys; it does
not sign in to Spotify or exchange any data with it. Also noted that the
on-screen-app check now holds social, video and messaging apps behind a
five-minute wait and keeps apps outside your focus list shut during a focus
session, and that your agenda can appear on the WakeProof home screen — all
on-device, with no new permission.

**7 October 2026** — added music playback from your own phone (a folder you
pick, or your device's music library), which stays entirely on the device;
noted the installed-app list used for limits, study apps and the optional
home screen; noted the on-screen-app check used by the focus tree.

**11 August 2026** — added the optional cloud sync section (Google sign-in and
Firebase, off until you sign in); removed the weekly report from the list of
features that call an AI provider, because it is now written on your device;
noted that a daily app limit can block an app as well as notify you; noted the
snapshot your phone sends to a paired watch.

## Contact

Questions or requests: **ogenrwotdaniel@gmail.com**
