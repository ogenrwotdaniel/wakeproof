# WakeProof — Google Play publishing guide

Developer: **Ogenrwot Daniel** · Contact: **ogenrwotdaniel@gmail.com**
App: WakeProof (`com.ogenrwotdaniel.wakeproof`) · Category: Productivity

This is the complete, ordered directive for getting WakeProof onto Google Play.
Follow it top to bottom. Companion files:

- `docs/PRIVACY_POLICY.md` — the privacy policy (must be hosted at a public URL).
- `docs/STORE_LISTING.md` — copy-paste store texts, Data safety answers, and
  permission-declaration texts.

---

## ⚠️ Read this first: the realistic timeline

Google requires **new personal developer accounts** (created after Nov 2023) to
run a **closed test with at least 12 testers continuously opted-in for 14 days**
before the app can go to production. That means:

| When | What you can do |
|---|---|
| Next week | Create the account, upload the app to **Closed testing**, recruit 12+ testers |
| ~2–3 weeks later | Apply for production access, then publish publicly |

So "published next week" realistically means **live for your testers next
week** and **public on Play about three weeks out**. Plan around that.
(Organization accounts skip the 12-tester rule, but need a D-U-N-S number —
slower to obtain, not worth it here.)

---

## Step 1 — Developer account (Day 1)

**DONE (2026-08-10):** account created and identity verification passed.
Tester recruitment stands at **10 of the 12** needed; closed-testing upload
planned for 2026-08-11.

1. Go to https://play.google.com/console/signup
2. Sign in with **ogenrwotdaniel@gmail.com**, choose **Personal** account.
3. Pay the **one-time $25 USD** registration fee (card required).
4. Complete identity verification (government ID + address; can take 1–3 days —
   start this immediately, everything else can proceed in parallel).
5. Developer name shown on Play: **Ogenrwot Daniel** (or a brand name if you
   prefer — it's public). Contact email: ogenrwotdaniel@gmail.com (Google
   verifies it and it is shown publicly on the listing).

## Step 2 — Host the privacy policy (Day 1)

**DONE (2026-07-20):** the policy is live at
**https://ogenrwotdaniel.github.io/wakeproof/privacy/** (HTML version of
`docs/PRIVACY_POLICY.md`, served from the `ogenrwotdaniel.github.io` repo,
`wakeproof/privacy/index.html`). Use this URL everywhere Play asks for a
privacy policy. Keep the two copies in sync when the policy changes.

Keep the URL stable forever; it goes into the Play listing and the app can't
ship without it.

## Step 3 — Prepare the release build (Day 1–2)

Already done in the repo:
- Signing: `keystore.properties` + `wakeproof-release.keystore` (repo root,
  untracked). **Back both up somewhere safe (password manager + offline copy).
  If the keystore is lost, the app can never be updated again.** Never commit
  them.
- `targetSdk 36` — Play requires API 36 for new apps and updates from
  **31 Aug 2026**. Shipping at 36 now avoids being locked out of publishing
  updates three weeks after launch.
- `versionName 1.0.0`, `versionCode 1`.
- Debug-only test buttons are stripped from release (`BuildConfig.DEBUG`).
- R8 minification and resource shrinking are **ON** (AAB 39 MB vs 93 MB
  unminified). Keep rules live in `app/proguard-rules.pro`; the deobfuscation
  map is bundled into the AAB automatically, so Play Console de-obfuscates
  crash reports without a separate upload.
- `app/google-services.json` must be present or the build ships **without cloud
  sync**. Gradle prints a warning when it is missing — see `docs/CLOUD_SYNC.md`.

> **Before the first production upload, re-read `docs/CLOUD_SYNC.md` step 3.**
> Play App Signing re-signs the app with Google's key, so Google sign-in fails
> for everyone installing from Play until that key's SHA-1 is added to Firebase.
> It cannot be done in advance — the key only exists after the first AAB upload.

To produce the upload artifact:

```bash
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab   (this is what you upload)
```

Before every upload, bump in `app/build.gradle.kts`:
- `versionCode` — must increase by at least 1 every upload (integer, e.g. 2, 3, 4…)
- `versionName` — human-readable (suggest `1.0.0` for the first Play upload)

Known audience constraints (deliberate, document but don't change):
- `minSdk 34` → the app only installs on **Android 14+** devices. Lowered from
  35 on 2026-08-10: every API the code touched that lint flagged below 35 turned
  out to be an API-34 symbol, so 34 was a one-line change with a fully green
  `lintDebug` and no version guards anywhere.
  Going further to 33 (Android 13) is **not** free, and the blocker is not the
  code. Four call sites would need guards —
  `NotificationManager.canUseFullScreenIntent` (`ReliabilityChecker`),
  `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (`OnboardingScreen`), and
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` in `PomodoroTimerService` +
  `AppBlockService` — but that last one is a compile-time constant, so it inlines
  and lint only warns. Whether Android 13 accepts the raw `specialUse` type at
  `startForeground` or throws can only be settled on a real API-33 device, and it
  sits under the Pomodoro timer and the app blocker. Don't ship 33 without one.
- arm64-only native libs → excludes a handful of old 32-bit devices; all
  current phones are arm64.

## Step 4 — Create the app in Play Console (Day 2)

Play Console → **Create app**:
- App name: **WakeProof**
- Default language: English (or en-GB)
- App or game: App · Free or paid: **Free**
  (⚠️ a free app can never be switched to paid later)

  > Set to **Paid** by mistake on 2026-08-12. That direction *is* recoverable:
  > **Monetize → App pricing** (older console: Store presence → Pricing &
  > distribution) converts paid → free, and the Console search box finds the page
  > under "pricing". It is one-way too, so after converting, paid is gone for
  > good. Paid also demands a payments/merchant profile and blocks installs for
  > anyone outside the licensed-tester list, so leaving it set would have stalled
  > the closed test.
- Declarations: accept Developer Program Policies + US export laws.

Then work through the **Dashboard → "Set up your app"** checklist. Every form
answer you need is below and in `docs/STORE_LISTING.md`.

### 4a. App content forms (all under Policy → App content)

| Form | Answer |
|---|---|
| Privacy policy | URL from Step 2 |
| App access | Choose "All or some functionality is restricted" and note: (a) AI features (water verification, flashcard drafting, study coach) need the reviewer to enter their own free Gemini API key in Settings → AI provider, and degrade gracefully (honor mode) without one; (b) cross-device sync needs a Google sign-in in Settings → Sync across devices. **State explicitly that no account is required to use the app** — sign-in only adds sync — so Play does not demand demo credentials. |
| Ads | **No**, the app contains no ads |
| Content rating (IARC questionnaire) | Category: Utility/Productivity. Answer **No** to all violence/sex/drugs/gambling questions; no user-generated public content; no location sharing. Expected rating: **Everyone / PEGI 3** |
| Target audience | 18+ (simplest; avoids all child-policy obligations). Do **not** tick "appeals to children". |
| News app | No |
| COVID-19 tracing | No |
| Data safety | Copy the table from `docs/STORE_LISTING.md` §Data safety |
| Government app | No |
| Financial features | None |
| Health | Declare "Health & wellness features: sleep/wake habits" only if the form asks; WakeProof is not a medical app and makes no medical claims. |

### 4b. Sensitive-permission declarations (critical — rejection risk lives here)

Play Console will detect these in the manifest and demand justification. Use
the exact texts in `docs/STORE_LISTING.md` §Permission declarations:

1. **`USE_EXACT_ALARM`** — allowed ONLY for alarm-clock apps. WakeProof *is*
   an alarm clock; declare "Alarm clock app" as the core use case. ✅ Low risk.
2. **`USE_FULL_SCREEN_INTENT`** — same justification: lock-screen alarm. ✅
3. **`PACKAGE_USAGE_STATS`** (Usage access) — the strictest one. Declare core
   use case **"Digital wellbeing: shows the user their own screen-time and
   lets them set app limits"**. You must show it's user-facing core
   functionality (it is: the Phone usage screen + distraction limits). Provide
   the demo video (see below). ⚠️ Medium risk — if rejected, appeal with the
   video, or worst case ship v1 without the usage feature and re-add later.
4. **Foreground service types** (`mediaPlayback`, `specialUse`) — declare:
   mediaPlayback = alarm ringtone/focus sounds; specialUse = "user-visible
   Pomodoro countdown timer that must survive backgrounding".
5. `SYSTEM_ALERT_WINDOW`, `READ_CALENDAR`, `CAMERA` — no dedicated form, but
   they must be reflected in Data safety + privacy policy (done).

**Demo video (needed for the usage-stats declaration):** 30–60 s screen
recording showing: open app → Phone usage → grant usage access → stats screen →
set an app limit. Record with:
```bash
adb shell screenrecord /sdcard/wakeproof-demo.mp4   # Ctrl+C to stop
adb pull /sdcard/wakeproof-demo.mp4
```
Upload to YouTube (unlisted) and paste the link in the declaration form.

### 4c. Store listing (Main store listing)

All texts are pre-written in `docs/STORE_LISTING.md` (app name, both
descriptions, release notes). Graphics live in `docs/store-assets/` — see the
README there for specs, the regeneration commands, and the screenshot recipe:

| Asset | State |
|---|---|
| App icon | ✅ `docs/store-assets/icon-512.png` |
| Feature graphic | ✅ `docs/store-assets/feature-graphic-1024x500.png` |
| Phone screenshots | capture per `docs/store-assets/README.md` (lead with alarm ringing + pushup verify) |
| Tablet screenshots | same, in portrait — the layout is phone-width and looks empty in landscape |

## Step 5 — Closed testing release (Day 2–3)

> **Upload to Internal testing FIRST, then promote the same artifact to Closed.**
> Internal has no 12-tester rule and no 14-day wait, and it is Play-signed — which
> is the only way to hit the Play App Signing / Firebase problem in step 3 of
> `docs/CLOUD_SYNC.md` *before* the 14-day clock is running. Google re-signs the
> AAB with its own key, so Google sign-in (and therefore all cross-device sync)
> fails for every Play installer until that key's SHA-1 is added to Firebase, and
> the key does not exist until after the first upload. Going straight to Closed
> means running the whole 14 days on a build where sync sign-in is broken for all
> 12 testers. Upload to Internal → add the SHA-1 → confirm sign-in on a real Play
> install → then promote.
>
> Also screen testers for **Android 14+** (`minSdk 34`). An older phone can still
> opt in and count toward the 12, but can never install the app, so it produces no
> feedback. Ask what phone each tester has before counting on them.

1. Play Console → **Testing → Closed testing → Create track** (use the default
   "Alpha" track).
2. Create an **email list** of testers (12+ Gmail addresses — classmates,
   family; they must opt in via the link and keep the app installed 14 days).
3. Upload `app-release.aab`, write release notes (see STORE_LISTING.md).
4. Fix any pre-launch report warnings Play shows (it runs the app on real
   devices automatically — crashes there block nothing but look bad in review).
5. Roll out. Send testers the opt-in link (Play generates it), then the Play
   Store link.

**Tester instructions to send out:** open opt-in link → accept → install from
Play → keep installed and open it a few times over the 14 days.

## Step 5b — Shipping an update to an existing track

Everything in steps 1–4 is one-time. An update is only this:

1. **Bump the version** in `app/build.gradle.kts`. `versionCode` must increase
   by at least 1 or Play rejects the upload outright; `versionName` is the
   string testers see. 1.1.0 is already at `versionCode = 2`.
2. **Build and check:**
   ```bash
   ./gradlew testDebugUnitTest lintDebug bundleRelease
   # → app/build/outputs/bundle/release/app-release.aab
   ```
   Confirm `app/google-services.json` exists first — without it the build
   silently ships **without cloud sync**, and Gradle only warns.
3. **Upload:** Play Console → Test and release → Testing → **Closed testing →
   Alpha → Create new release** → drop the `.aab` in.
   Release name is internal (`1.1.0 (2)`); release notes are the public bit —
   copy the block from `docs/STORE_LISTING.md` §Closed testing — 1.1.0.
4. **Review the release** → **Start rollout to Closed testing**. Testers get the
   update through Play automatically (usually within a few hours; they can force
   it from the Play Store app's My apps page).

A closed-testing update does **not** restart the 14-day clock — that counts
continuous tester opt-in, not builds. Uploading a fresh build mid-test is
expected and is what the feedback loop is for.

### Does the SHA-1 need updating?

**Not for a routine update — it is already done.** The fingerprint is tied to
the signing certificate, not the build, so it is registered once and survives
every upload. `app/google-services.json` currently carries all three:

```
5e6e0d3d…  debug (sideloading from this machine)
40286eca…  release upload key (wakeproof-release.keystore)
8f72b7bd…  Play App Signing key (Google's re-signing key)
```

Re-check it only when one of these is true:

- **A new certificate enters the picture** — a new machine's debug key, a rotated
  upload key, or a second Firebase/Play app. Add that SHA-1 and re-download
  `google-services.json`.
- **You publish from a track that was never Play-signed before.** Play App
  Signing uses the same key across internal/closed/production for one app, so
  promoting between tracks changes nothing.
- **Sign-in starts failing only on Play installs.** The symptom lies: the app
  says *"No Google account is available on this device"*. The truth is in
  logcat — `Developer console is not set up correctly` — and the fix is a
  missing fingerprint, not the device's accounts.

To add one: Play Console → Test and release → **Setup → App signing** → copy the
**App signing key certificate** SHA-1 → Firebase console → Project settings →
Your apps → **Add fingerprint** → re-download `google-services.json` into `app/`.
No rebuild and no `versionCode` bump is needed for the registration itself, since
it is server-side and `WEB_CLIENT_ID` does not change.

To verify what actually signed an installed build rather than trusting a console
page, see `docs/CLOUD_SYNC.md` step 3 (`apksigner verify --print-certs`, read the
**V3.0 Signer**).

## Step 6 — Production (after the 14-day test)

1. Play Console shows "Apply for production access" once you meet the
   requirement (12 testers, 14 days). Fill the short questionnaire honestly
   (what you tested, what feedback you got, who the app is for).
2. Approval usually takes a few days.
3. **Production → Create release** → upload the (version-bumped) AAB → roll out
   to 100 %. First production review can take up to ~7 days; updates are
   usually faster.

## Step 7 — Before you press publish (final checklist)

- [ ] Keystore + passwords backed up in two places
- [ ] `versionCode` bumped, `versionName = 1.0.0`
- [ ] `./gradlew testDebugUnitTest lintDebug bundleRelease` all green
- [ ] Release build installed and smoke-tested on the S25 Edge
      (`adb install -r` the universal APK from bundletool, or a quick
      `./gradlew installRelease`): alarm fires from lock screen, Pomodoro runs,
      flashcards review works, no crash on each screen
- [ ] Overnight test done: alarm survives Samsung battery optimization
      (Settings → Battery → WakeProof → Unrestricted on the test device;
      onboarding walks users through this)
- [ ] Privacy policy URL live and correct
- [ ] All App content forms submitted (green ticks on the dashboard)
- [ ] Screenshots + icon + feature graphic uploaded
- [ ] Emergency-dismiss works with no API key (reviewers won't have one)

## Ongoing obligations

- Keep the contact email monitored — Google sends policy notices there;
  ignoring them gets apps removed.
- Every future upload: bump `versionCode`.
- Target-API policy: bump `targetSdk` each year when Play announces the new
  minimum (usually by 31 Aug).
- If you ever add accounts/login, you must add an account-deletion flow and
  update Data safety.
- Crash monitoring: watch Play Console → Quality → Android vitals.

## Costs

| Item | Cost |
|---|---|
| Play developer registration | $25 one-time |
| Everything else (hosting policy on GitHub Pages, Gemini free tier) | $0 |
