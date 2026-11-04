# WakeProof Clip — one clipboard across laptop, phone and tablet (+ calendar tap-to-focus)
Requested 2026-11-04. User's choices so far: transport = LAN first, cloud fallback;
both directions from the start; Samsung Keyboard's clipboard history is how pasting
happens on the tablet (so putting the clip on the system clipboard is enough).

## What research established (2026-11-04)

- Laptop: Ubuntu 26.04, **GNOME Shell 50.1 on Wayland**. `wl-paste --watch` fails
  ("requires a compositor that supports the wlroots data-control protocol") — GNOME
  does not implement it. A background process cannot watch the clipboard here; a
  **GNOME Shell extension** can (`Meta.Selection` `owner-changed` + `St.Clipboard`),
  which is how GSConnect and every GNOME clipboard manager do it.
- GNOME's Print Screen saves to `~/Pictures/Screenshots` AND puts the image on the
  clipboard, so watching the clipboard alone catches screenshots — no folder watch
  on the laptop (it would send every screenshot twice).
- Laptop is on `10.64.135.111/22` (campus Wi-Fi). Campus networks commonly isolate
  clients and block mDNS — this is why cloud fallback is not optional.
- Firestore rules allow `users/{uid}/{collection}/{docId}` (one level) for the owner,
  so `users/{uid}/clips/{docId}` needs NO rules change. A doc holds ≤ 1 MiB, so an
  image is split across chunk docs. Cloud Storage would need the Blaze plan — not used.
- `AccountRepository.idTokenForWatch` already mints a Google ID token for handing a
  sign-in to another device; the laptop can exchange it via the Firebase Auth REST
  `signInWithIdp` for a long-lived refresh token. No new OAuth client needed.
- Python 3.14; `cryptography` present, `zeroconf` / `qrcode` not installed.
- Android: an app may WRITE the clipboard from the background; it may READ it only
  while focused (or as the IME). WakeProof is the HOME app, so it is focused every
  time the user goes home — that is the automatic phone→laptop path.
- The in-app Calendar screen (`ui/agenda/AgendaScreen.kt:141`) only ticks rows; the
  launcher already has title-tap → `EventFocusDialog`. The watch has no calendar.

## Phases (risk first)

### Phase 0: Probes — observable facts, decided by running, not asking (S)
Verified when: each probe below has a written result in this file.
- 0.1 From `AppBlockService` (background, screen on, another app in front) call
  `setPrimaryClip` with a `content://` PNG from a FileProvider → appears in Samsung
  Keyboard's clipboard panel AND pastes into Samsung Notes on the tablet.
  If it fails: fallback is a notification "Screenshot from laptop" whose tap opens a
  1-frame activity that sets the clip in the foreground, then a share to Notes.
- 0.2 Launcher `onResume`: `getPrimaryClipDescription().timestamp` readable without
  the "pasted from clipboard" toast; `getPrimaryClip()` shows the toast once.
- 0.3 LAN reachability laptop ↔ phone on campus Wi-Fi and at home (plain TCP to a
  test port); mDNS visible or not.
- 0.4 A 30-line GNOME 50 extension logs `owner-changed` for copied text and for Print
  Screen, and reads the PNG bytes.
Blocked by: none. Throwaway code, not committed.

### Phase 1: Clip core, pinned across languages (M)
Verified when: `./gradlew :app:testDebugUnitTest` and `python -m pytest tools/clip` green
on the SAME fixture vectors.
- Envelope `{id (uuid), origin installId, createdAt, mime (text/plain | image/png |
  image/jpeg), bytes}`; AES-256-GCM with the shared clip key; chunking into ≤ 700 KiB
  pieces; dedupe by id; expiry 1 h; keep last 10.
- `clip/ClipCodec.kt` (pure) + `tools/clip/clipsync/codec.py`; a cross-language test
  vector file (like the water port's fixture) so the two cannot drift.
- Files: `app/.../clip/ClipCodec.kt`, `tools/clip/clipsync/codec.py`, tests.
Blocked by: none.

### Phase 2: Pairing (M)
Verified when: laptop shows a QR, the phone scans it, the laptop prints "paired as
<email>", and its stored refresh token reads `users/{uid}/clips`.
- `wakeproof-clip pair` on the laptop opens a small window with a QR:
  `{lan addrs, port, one-time secret, laptop pubkey}`. Phone: Settings → "Clipboard
  across devices" → "Pair a computer" → CameraX + ML Kit barcode scan.
- Phone connects over LAN, sends `{clip key, Google ID token}` sealed to the one-time
  secret. Laptop → `signInWithIdp` → refresh token in GNOME Keyring (`secret-tool`),
  clip key likewise. (Pairing needs one shared network ONCE — home Wi-Fi or the
  phone's hotspot if campus isolates clients.)
- The clip key is created by the first device that pairs and travels only by QR/LAN,
  never through Firestore. The tablet joins by scanning the laptop's QR the same way.
- Files: `app/.../clip/ClipPairing*.kt`, `ui/settings/ClipSettings*.kt`,
  `AccountRepository` (generalise `idTokenForWatch`), `tools/clip/clipsync/pair.py`.
Blocked by: 1.

### Phase 3: Laptop → tablet/phone, end to end (M)
Verified when: Print Screen on the laptop → within 3 s on LAN / 10 s via cloud the image
is in the tablet's Samsung Keyboard clipboard and pastes into Notes; same for text.
- GNOME extension `wakeproof-clip@ogenrwotdaniel` = eyes and hands only: on
  `owner-changed` reads text/image, hands it to the daemon over D-Bus; exposes
  `SetClipboard` for incoming clips. Skips `x-kde-passwordManagerHint: secret`.
- Daemon `clipsync` (systemd --user): encrypt, send over LAN to every peer it can
  reach (addresses from mDNS + each peer's last-known LAN addrs in
  `users/{uid}/clips/presence_<installId>`), write cloud chunks for the rest.
- Android receiver lives in `AppBlockService` (already always-on FGS, phone AND tablet):
  small LAN server + Firestore listener on `clips` → decrypt → `setPrimaryClip`
  (+ a quiet notification with a thumbnail).
Blocked by: 0.1, 0.3, 0.4, 2.

### Phase 4: Phone/tablet → laptop and each other (M)
Verified when: copy text in Chrome on the phone, press Home → it is on the laptop
clipboard; a phone screenshot appears on the laptop clipboard and in
`~/Pictures/WakeProof Clip/`.
- Launcher `onResume`: if the clip's timestamp is newer than the last one sent, read and
  send it (skipping `ClipDescription.EXTRA_IS_SENSITIVE`).
- Quick Settings tile "Send clipboard" (1-frame activity) for when Home is not wanted.
- Share target "Send to my devices" (text + images).
- Screenshots: MediaStore observer on `Screenshots/`, READ_MEDIA_IMAGES held granted by
  device owner (like the camera). Debug-only manifest entry if it would trouble Play.
- Received images on the laptop are also saved under `~/Pictures/WakeProof Clip/`.
Blocked by: 0.2, 3.

### Phase 5: Calendar tap-to-focus everywhere (user: "build all")
#### Step 5.1: In-app Calendar screen (S)
- Tapping the TITLE opens the launcher's `EventFocusDialog` (☐ still ticks).
  Reuses `startForEvent` as-is. Verified: tap title → session with the event's length.
#### Step 5.2: Today's events reach the watch (S)
- Phone puts `/wakeproof/agenda` {today's timed events: key, title, start, end, done}
  on the Data Layer from `AgendaRepository` changes + each launcher/agenda refresh +
  the hourly worker. Same source and done-keys as the launcher, so all three agree.
  Contract duplicated by hand (`WearContract` / wear `AgendaContract`).
#### Step 5.3: Watch agenda + start (M)
- Watch idle screen gains "Today" (rotary-scrollable list). Tap a title → confirm
  "Focus until 15:00 (1 h 25 m)?" → starts the WATCH's own timer with a hand-kept COPY
  of `exactSessionPlan` (25/5, no long break, last block trimmed/stretched; counts
  from now if the event has started; < 10 min / ended / session running = tick only).
  Needs `WatchTimerState.lastFocusSeconds` (persisted, default = focus length).
- Tick on the watch → MessageClient `/wakeproof/command` `agenda_done:<key>` → phone
  marks it done → pushes the agenda back (no optimistic state, like timer commands).
- The watch session walls phone + tablet through the existing `WatchSessionBeacon`.
- Tests: wear `WatchTimerTest` (exact plan copy matches phone vectors), pure agenda parse.
Blocked by: 5.2. Whole phase blocked by nothing else.

### Phase 6: Settings, docs, close-out (S)
- Settings → "Clipboard across devices": on/off, paired devices, unpair = rotate key,
  "send screenshots automatically".
- `docs/CLIPBOARD.md`, CLAUDE.md section, privacy policy in docs/ AND the github.io copy.
- `./gradlew assembleDebug testDebugUnitTest lintDebug` green; install on phone + tablet.
Blocked by: 3, 4.

## Decision log
| Decision | Chosen option | Why |
| --- | --- | --- |
| Transport | LAN first, cloud fallback (user) | Fast/free at home; campus Wi-Fi likely isolates clients |
| Cloud storage | Firestore chunk docs, no Cloud Storage | Storage needs Blaze; rules already cover the path |
| Laptop clipboard access | GNOME Shell extension + Python daemon | GNOME Wayland blocks background clipboard watching (verified) |
| Laptop auth | Phone hands over a Google ID token at pairing → REST `signInWithIdp` | Reuses `idTokenForWatch`; no new OAuth client |
| Encryption | End-to-end AES-GCM, key only via QR/LAN | Clipboards carry private material; Firestore sees ciphertext only |
| Android auto-send | On every return to the WakeProof home screen | The only automatic read Android allows; Home is ours |
| Android receive host | `AppBlockService` | Already an always-on FGS on both devices |
| During focus sessions | Clip stays on | Pasting study material in is the point (user) |

## Not yet specified
- Phase 0 results may switch Phase 3's receive path to the notification fallback.
- Phase 3: fog — exact GNOME 50 extension API surface for reading image MIME types;
  researched at Phase 0.4.

## Always / Never
- Always: text and images; laptop, phone, tablet; end-to-end encrypted; password-flagged
  clips skipped; clips expire after 1 h.
- Never: files/PDFs/folders, clipboard HISTORY sync, the watch, a second computer,
  Cloud Storage / Blaze, an AccessibilityService or custom keyboard to read the clipboard.

## Progress
| Phase | Status |
| --- | --- |
| 0 Probes | pending |
| 1 Clip core | pending |
| 2 Pairing | pending |
| 3 Laptop → Android | pending |
| 4 Android → laptop | pending |
| 5 Calendar tap-to-focus | pending |
| 6 Settings/docs | pending |
