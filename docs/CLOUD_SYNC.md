# Cloud sync — how it works, and how to set it up

Cross-device sync keeps alarms, streak history, flashcards, exams and parked
thoughts the same on the phone and the tablet. It is opt-in: signed out, the app
behaves exactly as it did before, entirely on-device.

The watch needs nothing here. It has no account of its own and never talks to the
cloud — it reads a snapshot pushed over the Wearable Data Layer by whichever
phone it is paired with. Because that phone now syncs, the watch shows synced
numbers as a consequence. See the Data Layer notes in `CLAUDE.md`.

## The model

Room stays the source of truth. Firestore is a mirror at
`users/{uid}/{collection}/{syncId}`, one collection per table.

Every synced row carries four extra columns (`data/db/SyncableEntity.kt`):

| Column      | Job |
|-------------|-----|
| `syncId`    | Cross-device identity. The Room `id` is a local rowid — two devices both mint `id = 7`, so it can never leave the device. |
| `updatedAt` | Device clock, used **only** to decide which of two versions wins. |
| `deleted`   | Tombstone. A hard delete cannot replicate: the other device cannot tell "deleted" from "never seen" and would re-upload the row. |
| `pending`   | Still needs uploading. Separate from `updatedAt` so a downloaded row is not immediately queued back up. |

One pass is: push everything `pending`, then pull everything the **server** has
stamped since this device's cursor, resolving collisions by last-write-wins
(`data/sync/MergeDecision.kt`, unit-tested).

Two clocks are deliberately kept apart. Reads are ordered by a Firestore
`serverTimestamp`, so a device whose clock is wrong cannot skip or re-read rows
forever; the device clock is used only to compare two versions of the same row.

Sync runs on app open (foreground, so the tablet is current before anything is
read), on app close (queued through WorkManager, so it survives the activity
going away), hourly in the background, and on the "Sync now" button.

### Known limits, accepted on purpose

- **Last write wins, no merge.** Two devices editing the same row within the same
  sync window means one edit is dropped. "My tablet is three days behind" is the
  real case, and it is handled exactly.
- **Tombstones are never garbage-collected.** They are tiny and the row count here
  is a personal study history, not a dataset.
- **Settings do not sync**, only the tables above — with ONE exception, the
  daily focus goal, kept at `users/{uid}/settings/shared` (fields
  `dailyFocusGoalMinutes`, `dailyFocusGoalUpdatedAt`). The streak is scored
  against it, so per-device goals gave the phone and tablet different streaks
  for the same day. Latest change wins (`sharedSettingAction`); a device whose
  goal was never changed since this shipped (stamp 0) adopts the cloud's.
  Bottle size, targets and API keys stay per-device. API keys in particular are
  in EncryptedSharedPreferences and should not travel.
- **A pre-sync edit to a starter alarm can duplicate.** Untouched starter alarms
  are local-only and are dropped once real ones arrive (the `seeded` column). Edit
  one *before* the first sign-in and it becomes a real alarm, which can then sit
  alongside the equivalent from the other device.

## Setup (one-time, in the Firebase console)

The app builds and runs without any of this — `app/google-services.json` is
untracked, and a build without it simply reports sync as unavailable
(`CloudSyncAvailability`). A Play release build **must** have it.

1. **Create the project** at <https://console.firebase.google.com> — name it
   `wakeproof`. Google Analytics is not needed.

2. **Add an Android app** with package name `com.ogenrwotdaniel.wakeproof`.

3. **Register the signing fingerprints.** Google Sign-In checks the certificate
   the installed app is signed with, so every certificate that will ever sign it
   needs to be listed under Project settings → Your apps → SHA certificate
   fingerprints:

   | Which | SHA-1 |
   |---|---|
   | Debug (sideloading from this machine) | `5E:6E:0D:3D:EF:C6:2A:24:2C:E1:A0:27:7C:6C:59:A9:4F:0A:47:C0` |
   | Release upload key (`wakeproof-release.keystore`) | `40:28:6E:CA:7C:DD:1E:E5:A0:61:AF:11:1E:9E:91:B5:B4:74:91:1C` |
   | **Play App Signing key** | `8F:72:B7:BD:91:6F:A9:0C:9A:3F:0B:42:01:00:3D:C9:6E:9B:D2:EB` (exists only after the first AAB upload — see below) |

   > **The one that bites.** Play App Signing re-signs the app with a key Google
   > holds, so the copy users install is **not** signed with the upload key above.
   > Until the Play signing SHA-1 is added here, sign-in works in every build you
   > install by hand and fails for everyone who installs from Play. Get it from
   > Play Console → Test and release → Setup → App signing, and add it as another
   > fingerprint. Re-download `google-services.json` afterwards.
   >
   > **Hit for real on 2026-08-12**, on the first Play-installed (internal
   > testing) build. What the user sees is *"No Google account is available on
   > this device"*, which sends you to the device's account settings — the wrong
   > place entirely. The truth is only in logcat:
   >
   > ```
   > CredentialManager: Get credential errorMsg=[28444] Developer console is not set up correctly.
   > ```
   >
   > To confirm which certificate actually signed a Play install, pull it off the
   > device rather than trusting any console page:
   >
   > ```bash
   > adb shell pm path com.ogenrwotdaniel.wakeproof     # gives the base.apk path
   > adb pull <base.apk path> play-base.apk
   > $ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs play-base.apk
   > ```
   >
   > Read the **V3.0 Signer** SHA-1. Google's signature shows up as
   > `CN=Android, O=Google Inc.`, and the V3.2 hybrid/PQC signers and the source
   > stamp signer listed alongside it are NOT the fingerprint Firebase wants.
   >
   > Adding the fingerprint needs no rebuild and no `versionCode` bump: the
   > registration is server-side, and `WEB_CLIENT_ID` (the only thing baked into
   > the build) does not change when an android-type OAuth client is added.

4. **Download `google-services.json`** into `app/`. It is gitignored. The Gradle
   build detects it and applies the plugin; without it you get a build warning and
   a sync-less app.

5. **Enable Google sign-in**: Authentication → Sign-in method → Google → Enable.
   Set a support email.

6. **Create the Firestore database**: Firestore Database → Create database →
   **Production mode** (not test mode — test mode is world-readable and expires).
   Pick the region closest to you and keep it: it cannot be changed later.

7. **Publish the rules** in `firebase/firestore.rules`. Either paste them into the
   Rules tab, or `firebase deploy --only firestore:rules`. They allow a signed-in
   user to reach only their own `users/{uid}` tree and deny everything else.

### Checking it works

Sign in on both devices (Settings → Sync across devices), add an exam on one, tap
"Sync now" on the other. The section reports what changed, and reports failures
rather than swallowing them.

## Cost

Free-tier Firestore is 50k document reads, 20k writes and 1 GiB storage per day.
One user across three devices, syncing hourly with a delta of a handful of rows,
is orders of magnitude under that.
