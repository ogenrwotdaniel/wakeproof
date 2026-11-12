# WakeProof Privacy Policy

**Effective date:** 12 November 2026
**Developer:** Ogenrwot Daniel
**Contact:** ogenrwotdaniel@gmail.com

WakeProof is an alarm clock and study companion app. This policy explains what
data the app handles, where it goes, and the choices you have. The short
version: **WakeProof has no ads and no analytics, and everything stays on your
phone unless you turn on one of two optional features.** Those two are cloud
sync (which needs you to sign in) and the AI features (which need you to supply
your own Gemini API key). Both are off until you switch them on, and both are
described below.

## Data stored only on your device

The following never leaves your phone and is deleted when you uninstall the
app:

- **Alarms, settings and preferences** (alarm times, focus goals, sound
  choices, subjects, distraction limits, wall rules).
- **Habit history** (which alarms you completed, focus minutes, streaks,
  flashcards and their review schedule, exams, parked thoughts).
- **Screen-time statistics.** With your permission (Usage access), WakeProof
  reads your device's app-usage statistics to show you your own screen time and
  to act on the daily limits you set — either a notification, or blocking the
  app from opening for the rest of the day, whichever you chose for that app.
  The same on-device check enforces your schedule walls over social, video,
  messaging, portal and browser apps (for example opening only during your
  Monday–Thursday morning window and staying shut Friday through Sunday) and
  keeps apps outside your focus list shut while a focus session runs. These
  statistics are processed on-device only.
- **Your installed apps.** WakeProof reads the list of apps installed on your
  phone to let you set daily limits, choose study apps, and — if you make
  WakeProof your home screen — show your apps. During a focus session it
  checks which app is on screen (using Usage access) to keep apps outside your
  focus list shut and, with "Grow a tree" on, to tell whether you left for a
  distracting app. None of this is stored beyond your own settings or sent
  anywhere.
- **Notifications from apps you have walled.** With your permission
  (Notification access), WakeProof removes notifications posted by an app
  while one of your walls keeps it shut — outside your Monday–Thursday open
  window, on Friday through Sunday, during a focus session, at bedtime, or once
  its daily limit is spent. It looks only at which app posted a notification,
  never at its content, and stores and sends nothing. The same access lets it
  pause media that a walled app is still playing (for example a video left in
  picture-in-picture); for that it reads only which app is playing and whether
  it is playing.
- **Calendar events.** With your permission, WakeProof reads event titles and
  times from your device calendar to show your agenda — in the app, and on the
  WakeProof home screen if you use it — to remind you five minutes before an
  event, and to time a focus session to an event's length. It never modifies your
  calendar and never transmits calendar data.
- **Pushup verification video.** The camera feed used to count pushups is
  processed entirely on-device (Google ML Kit pose detection). No video or
  image from pushup verification is stored or transmitted.
- **Day-plan photos.** The day-plan alarm asks you to photograph the page you
  wrote your day on. Each photo is saved in WakeProof's private storage on
  that device, where the Day plans screen shows it and lets you delete it.
  Only the newest 90 days of plans are kept; older photos are removed.
  The photos are never uploaded, synced to your other devices or sent to any
  AI service, and uninstalling the app removes them. WakeProof does not read
  or analyse what is on the page.
- **Evening reviews and recall notes.** What you enter at the evening review
  (how much of the plan got done, tomorrow's top three) and the lines you
  write after a focus block are kept in WakeProof's private storage on that
  device. They are not synced. They are included in the JSON backup file
  you can export yourself. A recall note is sent to Google Gemini only
  if you tap "Draft with AI" on it.
- **Night screen use.** For the sleep score WakeProof reads, from the same
  Usage access, when the screen was on between 20:00 and 03:00. It is worked
  out on the device each time and sent nowhere.
- **Your Gemini API key**, stored encrypted (Android EncryptedSharedPreferences).
- **Your paired watch.** If you install the WakeProof watch app, your phone
  sends it a small snapshot — streak, focus minutes and goal, whether today's
  day plan is done, today's agenda, and the current timer — over Google's on-device
  Wearable Data Layer. This travels between your own two devices and reaches no
  server of ours.

## Optional cloud sync — only when you sign in

WakeProof can keep your data the same across your own devices (for example a
phone and a tablet). This is **off by default**. It starts only when you sign in
under Settings → Sync across devices, and stops when you sign out.

- **Signing in** uses your Google account. WakeProof receives your Google
  account identifier, email address and display name, and uses them for one
  purpose: to know which private storage area is yours. There is no profile, no
  public presence, and no way for another user to find you.
- **What is synced:** your alarms, your completion history and streak, your
  flashcards and their review schedule, your exams, your parked thoughts, your
  daily focus goal and study-app focus list, and (when you pair WakeProof Clip)
  end-to-end encrypted clipboard items between your own devices.
- **What is *not* synced:** your Gemini API key, your full screen-time
  statistics, and your calendar data.
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

## Data sent to Google Gemini — only when you enable it

Some optional features call the **Google Gemini API** (Google LLC — see
<https://policies.google.com/privacy>). They only work after **you** create a
Gemini API key in Google AI Studio and paste it into Settings, so no data flows
until you opt in.

What is sent, per feature, over an encrypted connection (HTTPS):

| Feature | Data sent |
|---|---|
| Flashcard drafting | The notes text you paste, the notes photo you pick, or a recall note you choose to draft from |
| Study coach chat | The messages you type in the chat |

That is the whole list. In particular your **weekly report** and the
**motivational lines** after an alarm are written on your device from your own
numbers; no statistics, screen-time totals or app names are sent to Google
Gemini, with or without a key.

No alarm uses these features: pushup counting, the day-plan photo, flashcard
review and the sums all run on the device.

WakeProof sends this data directly from your phone to Google's Gemini API; the
developer of WakeProof never receives, stores or sees any of it. Google
processes it to return a result; their retention and processing are governed by
Google's privacy policy and Gemini API terms. Don't include personal
information you wouldn't share with Google (e.g. avoid photographing notes
containing other people's personal data).

If you never add an API key and never sign in for sync, the app makes **no
internet requests at all**.

## Permissions summary

| Permission | Why WakeProof asks |
|---|---|
| Camera | Counting pushups (on-device), photographing your day plan (kept on the device), and scanning the WakeProof Clip pairing code |
| Notifications, exact alarms, full-screen alarms | Making the alarm ring reliably, including on the lock screen |
| Usage access (special) | Showing you your own screen-time stats, applying the daily app limits you set (notify, or block for the rest of the day), enforcing your open-window and weekend walls on gated apps, and keeping apps outside your focus list shut during a focus session |
| Display over other apps (special) | The floating Pomodoro timer bubble, the block and focus-session screens shown over other apps, opening the alarm screen while you use other apps, and covering an app left open in a pop-up window while it is walled |
| Do Not Disturb access (special) | Turning on Do Not Disturb (priority mode) for a focus session, on every device the session walls, and restoring it afterwards |
| Notification access (special) | Removing notifications from apps while a wall keeps them shut, and pausing media a walled app is still playing; only the app and whether it is playing are looked at, never the content |
| Calendar (read) | Showing your agenda so you can tick events off, event reminders, focus sessions timed to an event |
| Home app (role, optional) | Only if you choose WakeProof as your home screen in Settings; switch back any time in Android's default-apps settings |
| Internet | Only for the optional cloud sync and Gemini AI features above |

Every permission is optional except notifications/alarms (the app's core); the
related feature simply stays off if you decline.

## Data sharing and selling

WakeProof does not sell or rent your personal data to anyone, and does not
share it except with the two processors you opt into: Google Firebase if you
sign in for sync, and the Google Gemini API if you add an API key. Both are
described above. There are no third-party analytics or advertising SDKs in the
app.

## Data retention and deletion

App data lives on your device. To delete it: uninstall the app, or use Android
Settings → Apps → WakeProof → Storage → Clear data.

If you enabled cloud sync, a copy also exists under your account. Signing out
stops the mirroring but does not erase that copy; to have it deleted, email
**ogenrwotdaniel@gmail.com** from the address you signed in with. Data you
export yourself (CSV or JSON backup) is under your control. Data already
processed by Google Gemini is subject to Google's policy and account tools.

## Children

WakeProof is not directed at children under 13 and does not knowingly collect
personal information from children.

## Changes to this policy

Material changes will be posted at this URL with an updated effective date.

**12 November 2026** — removed water-drinking verification altogether, and
with it the only case in which a photo taken at an alarm could be sent to
Google Gemini; added the day-plan alarm, whose photos are stored on the device
only, the evening review and focus-block recall notes (on the device only),
and a sleep score read from screen on/off times.

**6 November 2026** — retired the Kimi / Moonshot AI provider option so
optional AI features talk exclusively to the Google Gemini API; synced the
public web copy with on-device water verification (default on, with Gemini only
consulted as an optional second opinion) and walled-media pause; updated the
gated-app schedule description to reflect direct access during the
Monday–Thursday open window and the Friday–Sunday shutdown instead of a
five-minute countdown wait.

**27 October 2026** — removed music playback from your own phone: WakeProof no
longer reads music files or asks for the music and audio permission. Study
music is Spotify again, as before 7 October — WakeProof only opens the Spotify
app (or a playlist link you saved) and sends it play/pause/skip keys; it does
not sign in to Spotify or exchange any data with it. Also noted that the
on-screen-app check holds social, video and messaging apps behind walls and
keeps apps outside your focus list shut during a focus session, and that your
agenda can appear on the WakeProof home screen — all on-device, with no new
permission.

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
