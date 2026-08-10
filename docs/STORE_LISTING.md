# WakeProof — store listing, Data safety, permission declarations

Copy-paste source for the Play Console forms. Referenced from
`docs/PLAY_PUBLISHING.md`. Keep in sync with `docs/PRIVACY_POLICY.md` — Play
rejects listings whose Data safety answers contradict the policy.

---

## Main store listing

**App name** (30 chars max)

```
WakeProof
```

**Short description** (80 chars max — 76 used)

```
The alarm you can't cheat. Do pushups or drink water to prove you're awake.
```

**Full description** (4000 chars max)

```
WakeProof is the alarm you cannot dismiss by tapping snooze.

To silence it, you prove you are actually awake: do your pushups in front of the
camera, or photograph your water bottle before and after drinking. The alarm
keeps ringing until you do.

PROVE YOU'RE AWAKE
• Pushup alarm — the front camera counts your reps on-device with pose
  detection. No video is recorded, stored or uploaded, ever.
• Water alarm — snap your bottle before and after; the app checks the water
  level actually dropped.
• Emergency dismiss — hold for 60 seconds, unlocked after three failed
  attempts or when the camera is unavailable. You are never locked out.

THEN ACTUALLY STUDY
• Pomodoro timer with a floating bubble that follows you across other apps.
• Focus lock — pin the phone to WakeProof so you cannot drift off mid-session.
• Five built-in ambient study loops, with fade in and out. Or drive Spotify.
• Park a distracting thought in one tap and deal with it on your break.
• Flashcards with proven spaced repetition (SM-2), tagged by subject.
• Exam countdowns on your home screen.
• Daily focus goal, streaks, and a 7-day breakdown per subject.

STOP LOSING HOURS
• See your real screen time per app.
• Set a daily limit on any app. Choose a nudge, or a hard block that stops the
  app opening for the rest of the day.

ON YOUR WRIST AND ON YOUR TABLET
• Wear OS tile and app: streak, focus progress, water, and the live Pomodoro
  countdown on your watch.
• Optional cross-device sync — sign in and your alarms, streak, flashcards and
  exams stay the same on your phone and your tablet.

PRIVACY
Pushup counting runs entirely on your device. There are no ads, no analytics
and no tracking. Sync is optional and off until you sign in. The AI features
are optional too and use an API key you supply yourself.

WakeProof needs Android 14 or newer.
```

**Category:** Productivity
**Tags:** Alarm clock, Productivity, Study, Focus
**Contact email:** ogenrwotdaniel@gmail.com
**Website:** https://ogenrwotdaniel.github.io/wakeproof/
**Privacy policy:** https://ogenrwotdaniel.github.io/wakeproof/privacy/

---

## Data safety

Play Console → Policy → App content → Data safety. Answer the wizard as below.

**Does your app collect or share any of the required user data types?** → **Yes**
(cloud sync and the AI features both transmit data off the device.)

**Is all of the user data collected by your app encrypted in transit?** → **Yes**
**Do you provide a way for users to request that their data is deleted?** → **Yes**
(email request, stated in the privacy policy).

### Data types to declare

| Data type | Collected | Shared | Processed ephemerally | Required or optional | Purpose |
|---|---|---|---|---|---|
| Personal info → Email address | Yes | No | No | **Optional** | App functionality (account for sync) |
| Personal info → Name | Yes | No | No | **Optional** | App functionality (account for sync) |
| Personal info → User IDs | Yes | No | No | **Optional** | App functionality (account for sync) |
| Photos | Yes | Yes | **Yes** | **Optional** | App functionality (water verification — sent to the AI provider you configure, not retained by WakeProof) |
| App activity → Other user-generated content | Yes | No | No | **Optional** | App functionality (alarms, streak history, flashcards, exams, parked thoughts — synced only if you sign in) |
| App activity → Other actions | Yes | No | No | **Optional** | App functionality (aggregate focus/screen-time numbers used by the AI features when enabled) |

### Do NOT declare

These are read on-device and never transmitted — declaring them would be wrong
and invites questions you would then have to walk back:

- **Camera / pushup video** — pose detection is fully on-device; no frame leaves
  the phone. (The *water photos* are declared above, because those do.)
- **Calendar events** — read for the agenda, never transmitted.
- **App usage / screen time** — read for the usage screen and app limits,
  processed on-device. (Only the *aggregate* numbers reach the AI provider if
  you enable the AI features, which is what the "Other actions" row covers.)
- **Location, contacts, files, messages, financial info, health** — not accessed
  at all.

### Wording note

Every row above is **Optional**, and that is the whole story of this app: it
works fully with no account and no API key. If a reviewer asks, the answer is
that sign-in adds sync and nothing else, and the app is fully functional
without it.

---

## Permission declarations

### USE_EXACT_ALARM

```
WakeProof is an alarm clock. Its core and advertised function is waking the user
at a time they set, which requires the alarm to fire at the exact minute chosen
regardless of Doze. Alarms are scheduled with AlarmManager.setAlarmClock(), the
API reserved for user-facing alarm clocks, so each alarm appears in the system
status bar and in the system's next-alarm surface. The user creates every alarm
themselves; the app schedules nothing on its own.
```

### USE_FULL_SCREEN_INTENT

```
When an alarm fires while the device is locked, WakeProof shows the alarm screen
over the lock screen so the user can dismiss it — identical to the behaviour of
a stock alarm clock. The full-screen intent is used only for user-set alarms at
their chosen time, never for promotion, notification or any other purpose.
```

### PACKAGE_USAGE_STATS (Usage access)

```
Core use case: digital wellbeing. WakeProof shows users their own screen time
per app and lets them set a daily limit on any app, enforced either as a
notification nudge or as a block that prevents the app opening for the rest of
the day. This is user-facing, primary functionality reachable from the app's
main navigation ("Phone usage"), not a background or analytics use. The data is
read only for the user's own device, is displayed only to that user, is
processed entirely on-device, and is never transmitted or shared. The permission
is requested only when the user opens that screen, with an explanation, and the
rest of the app works without it.
```

Attach the demo video (see PLAY_PUBLISHING.md §4b).

### Foreground service types

| Type | Declaration |
|---|---|
| `mediaPlayback` | Plays the alarm ringtone until the user completes the verification, and plays ambient study loops during a focus session. Both are user-initiated and user-audible. |
| `specialUse` | A user-visible Pomodoro countdown that must keep exact time while the user works in other apps, plus the app-limit guard that shows the block screen when a user-set daily limit is reached. Neither fits an existing type. |

### SYSTEM_ALERT_WINDOW

```
Two user-visible, user-enabled uses: the floating Pomodoro timer bubble that
stays on screen while the user works in other apps, and showing the alarm screen
when an alarm fires while the device is unlocked and in use (Android suppresses
full-screen intents in that state). Both are opt-in in Settings.
```

---

## Assets checklist

| Asset | Spec | Status |
|---|---|---|
| App icon | 512×512 PNG, <1 MB | ☐ |
| Feature graphic | 1024×500 PNG/JPG | ☐ |
| Phone screenshots | 2–8, 16:9 or 9:16, min 1080px | ☐ |
| Tablet screenshots (7" and 10") | recommended — the app supports tablets and sync is a tablet feature | ☐ |
| Wear OS screenshots | required **only** if you publish the Wear app to Play | ☐ |
| Demo video (usage-stats declaration) | 30–60 s, unlisted YouTube | ☐ |

Screenshot capture, per device:

```bash
adb -s <serial> exec-out screencap -p > shot.png
```

Worth including, in this order: Home dashboard with streak, alarm ringing,
pushup verification with the pose overlay, Pomodoro with subject chips,
flashcard review, phone usage with a limit set, Settings showing sync signed in.
