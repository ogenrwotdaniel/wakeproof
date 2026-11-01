# Morning window, watch walls everything, calendar to-do focus, tighter peeking
Requested 2026-11-01 (eight suggestions). Builds on the uncommitted WhatsApp Business batch,
which is committed first, as it stands, as its own commit.

## Phases

### Phase 0: Commit the pending WhatsApp Business batch (XS)
- Verification: `./gradlew assembleDebug testDebugUnitTest lintDebug` green, then commit.

### Phase 1: Open window 08:10–08:50 (S)
- `WallRule.OPEN_WINDOW_*` defaults → 490 / 530. One-off flag `open_window_morning`
  writes them directly (bypassing the week, like `seedWhatsAppBusinessCap`), drops any
  queued window loosening.
- WhatsApp Business keeps the same window (user's choice).
- Earn-the-feed does not apply inside the window (user's choice): `gateVerdict` skips
  EARN_WALL when the window is configured — the window is the earned slot.
- Usage screen copy + slider ranges allow a morning start.
- Tests: `WallRulesTest`, `AppGateTest` (earn skipped in window).

### Phase 2: The watch's session walls phone and tablet (M)
- Watch → phone over the Data Layer: `/wakeproof/watch_session` {endsAt} put on start,
  0 on finish (works with no internet). Phone listener stores it and republishes to
  `live/focusSession` as install "watch", so the tablet walls too.
- Phone + tablet ALSO read `live/watchTimer` (watch's own cloud doc) as a backup:
  active && running && within `RUNNING_STALE_MILLIS` → session active.
- `AppBlockService` focus check = own session OR peer endsAt OR watch session.
- Contract duplicated by hand in app `WearContract` / wear `TimerContract`.
- Tests: pure `watchSessionActive(...)`.

### Phase 3: Study apps sync across devices (S)
- Extend the shared settings doc (`users/{uid}/settings/shared`) with `studyApps` +
  its own stamp, same `sharedSettingAction` rule as the focus goal.
- A pulled list that ADDS apps while a session runs on this device is held until the
  session ends (adding mid-session is refused locally too); removals apply at once.

### Phase 4: DND during focus, every device (S)
- Already exists for the device running the session (`SessionDnd`, needs DND access —
  most likely never granted, hence "not working"). Add: peers enter/exit DND while a
  peer or watch session is active; Home shows a one-tap "Allow Do Not Disturb" card
  when the grant is missing.

### Phase 5: Calendar to-do list → focus for the event (M)
- Launcher agenda becomes a to-do list: tap an event → confirm "Focus for 1h 25m
  (the event's length) — can't be stopped" → nuclear session of exactly the event's
  duration. Checkbox ticks it off. All-day events tick only. Under 10 min: tick only.
- `sessionPlan` gains an exact-length mode: 25/5 blocks, last focus block trimmed so
  the session ends on the minute (`exactSessionPlan`, pure + tested).
- Reminder 5 min before every timed event: one exact alarm for the next reminder,
  rescheduled on fire, boot, app start and each worker pass; notification action
  "Focus for this event" opens the same confirm. Posted on the alarm-ringing device only.

### Phase 6: Close the peeking routes (M)
- Notifications: `WindowNotificationGuard` removes a waiting/windowed app's notifications
  whenever that app is walled (outside the window, focus session, bedtime, budget) —
  not only outside the window. Cuts Edge panel / Edge lighting / icon long-press previews.
  Needs the notification-access grant; Home nags when missing.
- Pop-up windows: when the walled app is in a pop-up/freeform window, a full-screen
  `TYPE_APPLICATION_OVERLAY` cover goes up (overlays draw above pop-ups; the wall activity
  does not), removed when the app leaves.
- Recents: after a wall, `killBackgroundProcesses` on the walled app so a Recents tap
  cold-starts it into the wall. The thumbnail itself cannot be blanked by a normal app.

### Phase 7 (extras, "be creative"): see assumptions — drop any on request
- End-of-event check-in: when an event session ends, "Done what you planned?" → tick.
- Forest tree per event session named after the event.

## Always / Never
- Always: nuclear rules stand; stricter now, looser waits a week (except this one
  requested window change).
- Never: AccessibilityService; retained photos; wrist snooze/dismiss.

## Progress
| Phase | Status |
| --- | --- |
| 0 | done (9512a1b) |
| 1–6 | done 2026-11-01 — build, tests, lint green; not run on a device |
| 7 | check-in done; tree named after the event DROPPED (would pollute subject stats) |
