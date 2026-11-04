package com.ogenrwotdaniel.wakeproof.distraction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.admin.AppCloser
import com.ogenrwotdaniel.wakeproof.admin.DeviceLock
import com.ogenrwotdaniel.wakeproof.alarm.StrictAlarm
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.StatsRepository
import com.ogenrwotdaniel.wakeproof.data.sync.LiveCloudFeed
import com.ogenrwotdaniel.wakeproof.data.sync.LiveFocusSession
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimer
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimerRules
import com.ogenrwotdaniel.wakeproof.wear.WatchSessionFeed
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.SessionDnd
import com.ogenrwotdaniel.wakeproof.pomodoro.SpotifyController
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * The one thing that stands in front of other apps. Three jobs, decided per
 * tick by [gateVerdict]:
 *
 * * NUCLEAR FOCUS — while a Pomodoro session is active (breaks included), any
 *   app not on the focus list (Settings → Study apps) is walled until the
 *   session ends. The timer has no pause or stop, so neither does this.
 * * [LimitMode.BLOCK] caps — once an app's daily allowance is spent,
 *   [BlockedActivity] is thrown in front of it until midnight. No override.
 * * The WAIT — social, video and messaging apps ([AppGate]) open only after
 *   five minutes on [GateActivity], however they were opened.
 * * The [WallRules] in front of those same waiting apps: shut at bedtime, shut
 *   until the day's first focus block, shut once the shared daily budget is
 *   spent.
 *
 * Losing either grant ends enforcement, so it is announced loudly
 * ([announceWallDown]) and, mid-session, costs the block's tree.
 *
 * Watching happens by polling usage events rather than through an
 * AccessibilityService. Accessibility would be the more precise hook, but Play
 * restricts it to genuine accessibility use and has rejected blockers built on
 * it; polling needs only the two special grants this app already asks for
 * (usage access, and draw-over-other-apps — which is also what lets a service
 * launch an Activity from the background at all). The cost is latency: an app
 * opened from the Edge panel can show for up to [POLL_MILLIS] before the wall
 * lands, and the Recents thumbnail is drawn by the system, out of reach.
 *
 * Cap cost is kept low by measuring usage only on entering a capped app and
 * then extrapolating locally between refreshes. Once an app's allowance is
 * spent it is remembered as spent until midnight, so walking back into it is
 * walled on the very next tick instead of after another measurement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class AppBlockService : Service() {

    @Inject lateinit var limitRepository: DistractionLimitRepository
    @Inject lateinit var usageRepository: UsageStatsRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallRulesRepository: WallRulesRepository
    @Inject lateinit var statsRepository: StatsRepository
    @Inject lateinit var liveFocusSession: LiveFocusSession
    @Inject lateinit var liveCloudFeed: LiveCloudFeed
    @Inject lateinit var alarmDevicePolicy: com.ogenrwotdaniel.wakeproof.alarm.AlarmDevicePolicy

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Latest blocked caps, kept current by a collector. Read on every tick, so it
     * must not be a DataStore round trip.
     */
    @Volatile private var blockedLimits: Map<String, AppLimit> = emptyMap()

    /** The focus list (study apps, plus Spotify when it is the study sound). */
    @Volatile private var focusList: Set<String> = emptySet()

    @Volatile private var rules: WallRules = WallRules()

    /** A focus block is logged today: the feed is earned ([WallRules.earnTheFeed]). */
    @Volatile private var focusDoneToday = false

    /** A morning alarm was verified today, which lifts the bedtime wall. */
    @Volatile private var alarmVerifiedToday = false

    /** When a session on another of the user's devices ends; null = none known. */
    @Volatile private var peerSessionEndsAt: Long? = null

    /** When the paired watch's session ends, heard over Bluetooth; 0 = none. */
    @Volatile private var watchSessionEndsAt = 0L

    /** The watch's own cloud timer: the tablet's way to hear of a watch session. */
    @Volatile private var watchTimer: LiveWatchTimer? = null

    /** Every installed app that waits — the pool the daily budget is spent from. */
    @Volatile private var allGated: Set<String> = emptySet()

    /** Budget measurement, kept apart from the per-app cap's. */
    private var budgetBaselineMillis = 0L
    private var budgetBaselineAt = 0L
    private var budgetBaselineValid = false
    /** The budget (minutes) found spent today; a raised budget re-measures. */
    private var budgetSpentAgainst: Int? = null

    private var ticks = 0L

    /**
     * The stored rules and limits have arrived. Until then the defaults stand
     * in, and [syncSuspensions] must not release a held app on their say-so.
     */
    @Volatile private var rulesLoaded = false
    @Volatile private var limitsLoaded = false

    /** Open through the sleep wall: the clock (to check the alarm) and the calendar. */
    private val sleepExempt: Set<String> by lazy {
        listOfNotNull(
            runCatching {
                packageManager.resolveActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), 0)
                    ?.activityInfo?.packageName
            }.getOrNull(),
            runCatching {
                packageManager.resolveActivity(
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), 0
                )?.activityInfo?.packageName
            }.getOrNull()
        ).filter { it != "android" }.toSet()
    }

    /** Never suspended, whatever their verdict: losing them costs more than any app is worth. */
    private val neverSuspend: Set<String> by lazy {
        untouchable + AppGate.NEVER_SUSPEND + listOfNotNull(
            // OTP codes.
            runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull(),
            // The keyboard: every text field on the phone.
            runCatching {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.substringBefore('/')
            }.getOrNull(),
            // Web content inside every other app, should a browser provide it.
            runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()
        )
    }

    /** Web browsers on this phone; re-resolved when an app is installed. */
    @Volatile private var browsers: Set<String> = emptySet()

    /** Apps in their first week on the phone; see [NewAppQuarantine]. */
    @Volatile private var quarantined: Set<String> = emptySet()

    /**
     * Notification history is on: it keeps the text of notifications the
     * walls strip, so while it is on every waiting app stays shut.
     */
    @Volatile private var historyOn = false

    /** An app was installed: it may be a browser or a new feed, so look again. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            browsers = AppGate.browserPackages(this@AppBlockService)
            gatedCache.clear()
            refreshAllGated()
        }
    }

    /** PackageManager lookups, resolved once — they cannot change under us. */
    private val untouchable: Set<String> by lazy { undismissablePackages() }

    /** Which app is in front; see [ForegroundTracker] for why it is a running fold. */
    private val tracker by lazy {
        ForegroundTracker(getSystemService(UsageStatsManager::class.java))
    }

    /** Package → whether it waits. Category lookups are cheap but not free. */
    private val gatedCache = mutableMapOf<String, Boolean>()

    /** Measured usage for [baselineFor] and the clock reading it was taken at. */
    private var baselineFor: String? = null
    private var baselineMillis = 0L
    private var baselineTakenAt = 0L

    /**
     * Apps whose allowance is spent today → the cap (minutes) it was spent
     * against. Usage only grows during a day, so these need no re-measuring: a
     * re-entry is walled at once. A RAISED cap is re-measured (see [watch]).
     */
    private val spentToday = mutableMapOf<String, Int>()
    private var spentDay = LocalDate.now().toEpochDay()

    /**
     * Woken by the screen coming on. Without it the screen-off sleep ran its
     * full 60 s after an unlock, which was exactly the window in which a capped
     * app opened straight from the lock screen stayed usable.
     */
    private val screenOn = Channel<Unit>(Channel.CONFLATED)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.trySend(Unit)
        }
    }

    /** Last verdict logged, so the log says what changed rather than every tick. */
    private var lastLogged: String? = null

    /** Suppresses a second wall for the same app while the first is still up. */
    private var lastBlockedPackage: String? = null
    private var lastBlockedAt = 0L

    /** The cover over a walled app left running in a pop-up; see [popupCoverWanted]. */
    private val popupCover by lazy { PopupCover(this) }
    /** The cover over a walled app the user has screen-pinned; see [pinCoverWanted]. */
    private val pinCover by lazy { PopupCover(this) }
    private val activityManager by lazy { getSystemService(ActivityManager::class.java) }
    /** Apps [AppCloser] is suspending right now, so a tick does not start a second close. */
    private val closing = mutableSetOf<String>()
    /** The app last walled, for the cover and the Recents kill; null = none pending. */
    private var coverFor: String? = null
    private var coverWallAt = 0L
    private var coverGraceUntil = 0L
    /** Kill [coverFor]'s background process once it leaves the screen (not for a wait). */
    private var killPending = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        browsers = AppGate.browserPackages(this)
        AppCloser.releaseStale(this)
        // Not only from MainActivity: the restrictions must stand on a phone
        // where WakeProof itself is never opened.
        DeviceLock.enforce(this)
        StrictAlarm.resumeIfPending(this)
        // WakeProof Clip: LAN server, mDNS, cloud listener, screenshots — all
        // self-contained, and idle unless the feature is on and paired.
        com.ogenrwotdaniel.wakeproof.clip.ClipRuntime.start(this, scope)
        registerReceiver(
            packageReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            RECEIVER_NOT_EXPORTED
        )
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        scope.launch {
            settingsRepository.settings.collect { cfg ->
                // Spotify as the study sound is part of the session: its
                // "Open playlist" button lands in Spotify, and walling that
                // would leave a session with music that cannot be chosen.
                focusList = if (FocusSound.isSpotify(cfg.focusSoundId)) {
                    cfg.studyApps + SpotifyController.SPOTIFY_PACKAGE
                } else {
                    cfg.studyApps
                }
            }
        }
        scope.launch {
            wallRulesRepository.rules.collect { next ->
                val browsersChanged = next.gateBrowsers != rules.gateBrowsers
                rules = next
                rulesLoaded = true
                if (browsersChanged) {
                    gatedCache.clear()
                    refreshAllGated()
                }
            }
        }
        // Re-subscribed at each new day: today's numbers only change when a log
        // is written, and nothing is written at midnight.
        scope.launch {
            flow {
                while (true) {
                    emit(LocalDate.now().toEpochDay())
                    delay(60_000)
                }
            }.distinctUntilChanged()
                .flatMapLatest { statsRepository.observeToday() }
                .collect { today ->
                    focusDoneToday = today.focusMinutes > 0
                    alarmVerifiedToday =
                        today.pushupCount > 0 || today.mathCount > 0 || today.waterCount > 0
                }
        }
        scope.launch { liveFocusSession.peerSessionEndsAt().collect { peerSessionEndsAt = it } }
        // A session on the watch walls this device too (2026-11-01): over
        // Bluetooth on the phone, which relays it to the tablet; the tablet also
        // reads the watch's own cloud timer, for a watch with its own Wi-Fi.
        scope.launch {
            WatchSessionFeed.endsAt(applicationContext).collect { endsAt ->
                watchSessionEndsAt = endsAt
                runCatching { liveFocusSession.relayWatch(endsAt) }
            }
        }
        scope.launch { liveCloudFeed.watchTimer().collect { watchTimer = it } }
        // Do Not Disturb follows a session on ANY device (2026-11-01). The
        // session's own device sets it in PomodoroTimerService; this covers the
        // others. Level-triggered: SessionDnd only undoes what it did.
        scope.launch {
            while (true) {
                val own = PomodoroTimerService.state.value.active
                if (rules.sessionDnd && (own || otherDeviceSessionActive())) {
                    SessionDnd.enter(applicationContext)
                } else if (!own) {
                    SessionDnd.exit(applicationContext)
                }
                delay(DND_CHECK_MILLIS)
            }
        }
        scope.launch {
            while (true) {
                historyOn = runCatching {
                    Settings.Secure.getInt(contentResolver, NOTIFICATION_HISTORY_SETTING, 0) == 1
                }.getOrDefault(false)
                // Screen off too: audio is exactly what plays with the screen off.
                pauseWalledMedia()
                delay(MEDIA_CHECK_MILLIS)
            }
        }
        scope.launch {
            // Queued loosenings land here too, not only when a screen opens.
            while (true) {
                runCatching { wallRulesRepository.promoteDue() }
                runCatching { limitRepository.promoteDuePending() }
                // Also ends a new app's quarantine week on time.
                refreshAllGated()
                // A locked-alarm ring whose service died is started again.
                runCatching { StrictAlarm.resumeIfPending(this@AppBlockService) }
                runCatching { nightReminders() }
                delay(PROMOTE_EVERY_MILLIS)
            }
        }
        scope.launch {
            runCatching { wallRulesRepository.seedMorningWindow() }
            refreshAllGated()
        }
        // A session the process lost (a reboot, a force-stop) comes back here
        // too: this service is restarted by boot and by the 15-minute worker.
        PomodoroTimerService.restore(applicationContext)
        scope.launch {
            limitRepository.limits.collect { all ->
                limitsLoaded = true
                blockedLimits = all
                    .filterValues { it.mode == LimitMode.BLOCK }
                    .filterKeys { it !in untouchable }
            }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED
        )
        scope.launch { watch() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    override fun onDestroy() {
        if (instance === this) instance = null
        popupCover.hide()
        pinCover.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        com.ogenrwotdaniel.wakeproof.clip.ClipRuntime.stop()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        // Give the collectors a tick to land before the first decision.
        delay(POLL_MILLIS)
        while (true) {
            // A grant went away: nothing can be seen or covered any more. Say
            // so loudly, and stop burning cycles.
            if (!usageRepository.hasAccess() ||
                (ticks++ % OVERLAY_CHECK_EVERY == 0L && !Settings.canDrawOverlays(this))
            ) {
                // Nothing can be seen any more, so nothing can be judged: shut
                // every waiting and capped app instead of opening them all.
                // The holds outlive this service and are re-decided when the
                // grant comes back and it starts again.
                holdEverything()
                announceWallDown()
                stopSelf()
                return
            }

            // Nothing can be opened while the screen is off, so there is nothing
            // to catch. This is the single largest saving in the loop: a night's
            // sleep was ~19,000 foreground queries that could only ever answer
            // "no one is using anything".
            if (!powerManager.isInteractive) {
                syncSuspensions()
                popupCover.hide()
                pinCover.hide()
                // Screen off ends a visit through the wait, like leaving the app.
                GatePasses.revokeAll()
                // Force a fresh measurement on wake rather than extrapolating
                // across the whole time the phone was down.
                baselineFor = null
                withTimeoutOrNull(SCREEN_OFF_POLL_MILLIS) { screenOn.receive() }
                continue
            }

            val today = LocalDate.now().toEpochDay()
            if (today != spentDay) {
                spentToday.clear()
                spentDay = today
                budgetSpentAgainst = null
                budgetBaselineValid = false
            }
            syncSuspensions()

            // Still reading events while the lock screen is up keeps the fold
            // current, but nothing behind the lock screen can be used.
            val current = runCatching { tracker.current() }
                .onFailure { Log.w(TAG, "foreground query failed", it) }
                .getOrNull()
            // Before the early exits below: WakeProof itself in front also ends
            // a visit, once the app it was granted for has been on screen.
            if (current != null) GatePasses.track(current, System.currentTimeMillis())
            if (keyguardManager.isKeyguardLocked) GatePasses.revokeAll()
            if (current == null || keyguardManager.isKeyguardLocked) {
                popupCover.hide()
                pinCover.hide()
                delay(POLL_MILLIS)
                continue
            }
            followWalledApp(current)
            if (current == packageName) {
                // Looking at our own wall (or WakeProof itself). Not a re-entry.
                pinCover.hide()
                delay(POLL_MILLIS)
                continue
            }
            // Genuinely somewhere else now (not just looking at our own wall), so
            // a re-entry should be walled at once rather than debounced.
            if (current != lastBlockedPackage) lastBlockedPackage = null

            val gated = gatedCache.getOrPut(current) {
                AppGate.isGated(this, current, untouchable, browsers, rules.gateBrowsers)
            }
            if (!gated) budgetBaselineValid = false
            val limit = blockedLimits[current]
            val verdict = verdictFor(
                current = current,
                gated = gated,
                hasPass = GatePasses.isValid(current),
                capSpent = limit != null && capSpent(current, limit),
                budgetSpent = gated && budgetSpent(current)
            )
            logVerdict(current, verdict)
            // A pinned task refuses every activity from outside it, wall
            // included, so a pinned app is covered by an overlay instead.
            val pinned = userPinned()
            if (pinCoverWanted(pinned, verdict)) {
                if (!pinCover.isShowing) Log.i(TAG, "pin cover UP for $current ($verdict)")
                // Closing the app ends the pin with it; the cover holds the
                // screen meanwhile, and alone where closing is not possible.
                closeBesideWall(current)
                pinCover.show(
                    "${usageRepository.appLabel(current)} is walled.\n\n" +
                        "Screen pinning does not get past it. Unpin the app to carry on."
                )
            } else {
                if (pinCover.isShowing) Log.i(TAG, "pin cover down ($current, pinned=$pinned)")
                pinCover.hide()
                if (verdict != GateVerdict.ALLOW) {
                    wall(current, killAfter = verdict != GateVerdict.WAIT) { wallIntent(current, verdict, limit) }
                }
            }
            delay(POLL_MILLIS)
        }
    }

    /**
     * A focus session anywhere the user has a device: this one, a peer phone or
     * tablet (cloud), or the watch (Bluetooth, or its own cloud timer).
     */
    private fun anySessionActive(): Boolean =
        PomodoroTimerService.state.value.active || otherDeviceSessionActive()

    /** A session on one of the user's OTHER devices — what walls (and quiets) this one. */
    private fun otherDeviceSessionActive(): Boolean {
        val now = System.currentTimeMillis()
        return (peerSessionEndsAt ?: 0L) > now ||
            watchSessionEndsAt > now ||
            LiveWatchTimerRules.walls(watchTimer, now)
    }

    /** [gateVerdict] with everything this service knows filled in. */
    private fun verdictFor(
        current: String,
        gated: Boolean,
        hasPass: Boolean,
        capSpent: Boolean,
        budgetSpent: Boolean
    ): GateVerdict {
        val minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute }
        return gateVerdict(
            // Settings is the one essential app a focus session walls
            // (2026-11-04, "once focus starts you can't run away"): it is where
            // grants, defaults and the date live. Calls, the home screen and
            // the quick-settings shade stay usable.
            essential = current in untouchable &&
                !(current in FOCUS_WALLED_ESSENTIALS && anySessionActive()),
            focusSessionActive = anySessionActive(),
            onFocusList = current in focusList,
            capSpent = capSpent,
            gated = gated,
            hasPass = hasPass,
            outsideWindow = gated && outsideOpenWindow(
                minuteOfDay = minuteOfDay,
                startMinutes = rules.openWindowStartMinutes,
                endMinutes = rules.openWindowEndMinutes
            ),
            bedtime = gated && bedtimeActive(
                minuteOfDay = minuteOfDay,
                startMinutes = rules.bedtimeStartMinutes,
                alarmVerifiedToday = alarmVerifiedToday
            ),
            feedEarned = feedEarned(rules.earnTheFeed, focusDoneToday, rules.openWindowStartMinutes),
            budgetSpent = budgetSpent,
            sleeping = sleepWallActive(minuteOfDay) && current !in sleepExempt
        )
    }

    /** The screen thrown in front of [current] for [verdict]; never called for ALLOW. */
    private fun wallIntent(current: String, verdict: GateVerdict, limit: AppLimit?): Intent {
        val label = usageRepository.appLabel(current)
        return when (verdict) {
            GateVerdict.ALLOW, GateVerdict.WAIT -> GateActivity.intent(this, current, label)
            GateVerdict.FOCUS_WALL -> BlockedActivity.focusIntent(this, label)
            GateVerdict.CAP_WALL -> BlockedActivity.intent(
                context = this,
                label = label,
                usedMinutes = limit?.minutes ?: 0,
                capMinutes = limit?.minutes ?: 0,
                reopensAt = reopensTomorrowAt(current)
            )
            GateVerdict.WINDOW_WALL -> BlockedActivity.ruleIntent(
                this,
                "Not open now",
                "$label and every other waiting app open only between " +
                    "${clock(rules.openWindowStartMinutes)} and " +
                    "${clock(rules.openWindowEndMinutes)}. The phone and SMS always work."
            )
            GateVerdict.BEDTIME_WALL -> BlockedActivity.ruleIntent(
                this,
                "Bedtime",
                "$label and every other waiting app are shut for the night. They open once " +
                    "your morning alarm is verified, or at 07:00 on a day without one."
            )
            GateVerdict.SLEEP_WALL -> BlockedActivity.ruleIntent(
                this,
                "Time to sleep",
                "Everything but calls, the clock and your alarms is shut until " +
                    "${clock(SLEEP_END_MINUTES)}. Put the phone on charge and go to bed."
            )
            GateVerdict.EARN_WALL -> BlockedActivity.ruleIntent(
                this,
                "Earn it first",
                "$label opens after today's first focus block. One pomodoro, then the feed."
            )
            GateVerdict.BUDGET_WALL -> BlockedActivity.ruleIntent(
                this,
                "Today's budget is spent",
                "${rules.dailyBudgetMinutes} minutes across every waiting app are used up. " +
                    "$label opens again " +
                    (reopensTomorrowAt(current)?.let { "tomorrow at $it." } ?: "at midnight.")
            )
        }
    }

    /**
     * The wall that stands BEFORE the wait for [packageName], if any. Asked by
     * [GateActivity] as it opens: the WakeProof home screen launches the wait
     * screen directly, and that path never passed through [watch] — so TikTok
     * tapped there at 00:52 got a five-minute countdown instead of "not open
     * now" (2026-10-29).
     *
     * Measures afresh and leaves the loop's usage baselines alone: those
     * extrapolate as though the app were on screen, and it is not — the wait is.
     */
    private suspend fun wallBefore(packageName: String): Intent? {
        val gated = gatedCache.getOrPut(packageName) {
            AppGate.isGated(this, packageName, untouchable, browsers, rules.gateBrowsers)
        }
        val limit = blockedLimits[packageName]
        val capSpent = limit != null &&
            (usageRepository.todayForegroundMillisFor(setOf(packageName))[packageName] ?: 0L) >=
            limit.minutes * 60_000L
        val budget = rules.dailyBudgetMinutes
        val budgetSpent = gated && budget > 0 &&
            usageRepository.todayForegroundMillisFor(allGated + packageName).values.sum() >=
            budget * 60_000L
        val verdict = verdictFor(packageName, gated, hasPass = false, capSpent, budgetSpent)
        logVerdict(packageName, verdict)
        return when (verdict) {
            GateVerdict.ALLOW, GateVerdict.WAIT -> null
            else -> wallIntent(packageName, verdict, limit)
        }
    }

    /**
     * Whether [pkg] stands behind a wall right now — the focus wall, a spent
     * cap, the window, bedtime, earn-the-feed or the budget; NOT the wait,
     * which is a door rather than a wall. Asked by [WindowNotificationGuard]
     * so a walled app's notifications go too (2026-11-01): the shade, the Edge
     * panel and an icon's long-press preview were all ways to read an app the
     * wall kept shut.
     *
     * No measuring: a cap or budget counts only once [watch] has found it
     * spent today, so this can run on every posted notification.
     */
    private fun walledNow(pkg: String): Boolean {
        if (pkg == packageName) return false
        val verdict = standingVerdict(pkg, hasPass = false)
        return verdict != GateVerdict.ALLOW && verdict != GateVerdict.WAIT
    }

    /**
     * [pkg]'s verdict were it opened now, without measuring: a cap or budget
     * counts only once [watch] has found it spent today.
     */
    private fun standingVerdict(pkg: String, hasPass: Boolean): GateVerdict {
        val gated = gatedCache.getOrPut(pkg) {
            AppGate.isGated(this, pkg, untouchable, browsers, rules.gateBrowsers)
        }
        val capMinutes = blockedLimits[pkg]?.minutes
        val spentAt = spentToday[pkg]
        val budget = rules.dailyBudgetMinutes
        return verdictFor(
            current = pkg,
            gated = gated,
            hasPass = hasPass,
            capSpent = capMinutes != null && spentAt != null && capMinutes <= spentAt,
            budgetSpent = gated && budget > 0 && budgetSpentAgainst?.let { budget <= it } == true
        )
    }

    /**
     * Holds every waiting or capped app SUSPENDED while it stands behind any
     * wall or the wait (2026-11-04), so it cannot be opened at all — not from
     * its icon, Recents, a pop-up window or a link. The wait's way in is the
     * WakeProof home screen's [GateActivity], which lifts the hold as it grants
     * the pass; when the visit ends, the pass goes and the hold comes back.
     * See [com.ogenrwotdaniel.wakeproof.admin.AppCloser]. Changes only, so a
     * steady state costs no system calls.
     */
    private fun syncSuspensions() {
        if (!rulesLoaded || !limitsLoaded) return
        if (!AppCloser.available(this)) return
        val held = AppCloser.held(this)
        val shutAll = historyOn
        for (pkg in allGated + blockedLimits.keys + quarantined + held) {
            val want = holdSuspended(
                neverSuspend = pkg == packageName || pkg in neverSuspend,
                verdict = standingVerdict(pkg, hasPass = GatePasses.isValid(pkg)),
                forced = pkg in quarantined || (shutAll && (pkg in allGated || pkg in blockedLimits))
            )
            if (want && pkg !in held) {
                AppCloser.hold(this, pkg)
            } else if (!want && pkg in held) {
                AppCloser.release(this, pkg)
            }
        }
    }

    /**
     * Whether [current]'s BLOCK cap is spent today. Measures on entry and
     * extrapolates between [BASELINE_REFRESH_MILLIS] refreshes; a spent app is
     * remembered until midnight against a cap at least this strict.
     */
    private suspend fun capSpent(current: String, limit: AppLimit): Boolean {
        val spentAt = spentToday[current]
        if (spentAt != null && limit.minutes <= spentAt) return true
        val now = SystemClock.elapsedRealtime()
        if (baselineFor != current || now - baselineTakenAt > BASELINE_REFRESH_MILLIS) {
            baselineMillis = usageRepository.todayForegroundMillisFor(setOf(current))[current] ?: 0L
            baselineTakenAt = SystemClock.elapsedRealtime()
            baselineFor = current
        }
        val usedMillis = baselineMillis + (SystemClock.elapsedRealtime() - baselineTakenAt)
        if (usedMillis < limit.minutes * 60_000L) return false
        spentToday[current] = limit.minutes
        return true
    }

    /**
     * Whether today's shared budget ([WallRules.dailyBudgetMinutes]) is spent.
     * Measured across every waiting app on entering one, then extrapolated
     * while a waiting app stays in front, like a single app's cap.
     */
    private suspend fun budgetSpent(current: String): Boolean {
        val budget = rules.dailyBudgetMinutes
        if (budget <= 0) return false
        budgetSpentAgainst?.let { if (budget <= it) return true }
        val now = SystemClock.elapsedRealtime()
        if (!budgetBaselineValid || now - budgetBaselineAt > BASELINE_REFRESH_MILLIS) {
            budgetBaselineMillis = usageRepository
                .todayForegroundMillisFor(allGated + current).values.sum()
            budgetBaselineAt = SystemClock.elapsedRealtime()
            budgetBaselineValid = true
        }
        val used = budgetBaselineMillis + (SystemClock.elapsedRealtime() - budgetBaselineAt)
        if (used < budget * 60_000L) return false
        budgetSpentAgainst = budget
        return true
    }

    /** Tomorrow's opening time for [current] if the open window outlasts midnight. */
    private fun reopensTomorrowAt(current: String): String? {
        val gated = gatedCache[current] ?: false
        return reopensTomorrowAtMinutes(
            windowed = gated,
            startMinutes = rules.openWindowStartMinutes,
            endMinutes = rules.openWindowEndMinutes
        )?.let(::clock)
    }

    /** "08:10" or "8:10 AM", as the phone tells time. */
    private fun clock(minutes: Int): String =
        com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime(this, minutes / 60, minutes % 60)

    /** Recomputes [allGated] from the installed apps; the budget is measured over it. */
    private fun refreshAllGated() {
        scope.launch {
            val gateBrowsers = rules.gateBrowsers
            val installed = runCatching {
                usageRepository.installedApps().map { it.packageName }.toSet()
            }.getOrNull() ?: return@launch
            allGated = installed.filter {
                AppGate.isGated(this@AppBlockService, it, untouchable, browsers, gateBrowsers)
            }.toSet()
            NewAppQuarantine.observe(this@AppBlockService, installed)
            quarantined = NewAppQuarantine.quarantined(this@AppBlockService)
                .filter { it in installed }.toSet()
        }
    }

    /**
     * Usage access or draw-over-apps was taken away, which switches every wall
     * off at once. Not stoppable from here, so at least never silent; and in
     * the middle of a focus block it costs that block's tree — the session
     * itself carries on, since it cannot be stopped.
     */
    private fun announceWallDown() {
        val treeLost = PomodoroTimerService.breakout()
        val text = "WakeProof lost usage access or draw-over-apps, so nothing is walled." +
            if (treeLost) " This focus block's tree withered." else ""
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID, "Walls switched off", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "When WakeProof loses the access its walls need" }
        )
        manager.notify(
            ALERT_NOTIFICATION_ID,
            Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Your walls are down")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 1, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
        )
    }

    /**
     * The wind-down reminder and the charging nag ([NightRules]). The nag only
     * where alarms ring: the tablet going flat costs nothing at 03:00.
     */
    private fun nightReminders() {
        val now = System.currentTimeMillis()
        val minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute }
        val today = LocalDate.now().toEpochDay()
        val prefs = createDeviceProtectedStorageContext()
            .getSharedPreferences(NIGHT_PREFS, Context.MODE_PRIVATE)
        if (windDownDue(minuteOfDay, prefs.getLong(KEY_WIND_DOWN_DAY, -1L), today)) {
            prefs.edit().putLong(KEY_WIND_DOWN_DAY, today).apply()
            nightNotice(
                WIND_DOWN_NOTIFICATION_ID,
                "Wind down",
                "Everything but calls and your alarms shuts at ${clock(SLEEP_START_MINUTES)}, " +
                    "bed at ${clock(BEDTIME_MINUTES)}. Finish up and get ready."
            )
        }
        if (lightsOutDue(minuteOfDay, prefs.getLong(KEY_LIGHTS_OUT_DAY, -1L), today)) {
            prefs.edit().putLong(KEY_LIGHTS_OUT_DAY, today).apply()
            nightNotice(
                WIND_DOWN_NOTIFICATION_ID,
                "Lights out",
                "It's ${clock(BEDTIME_MINUTES)}. Phone on charge, into bed. " +
                    "Your alarm is at ${clock(SLEEP_END_MINUTES)}."
            )
        }
        if (!alarmDevicePolicy.ringsAlarms) return
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = runCatching {
            battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val charging = runCatching { battery.isCharging }.getOrDefault(true)
        if (chargeNagDue(minuteOfDay, level, charging, prefs.getLong(KEY_CHARGE_NAG_AT, 0L), now)) {
            prefs.edit().putLong(KEY_CHARGE_NAG_AT, now).apply()
            nightNotice(
                CHARGE_NOTIFICATION_ID,
                "Charge your phone — $level %",
                "Your ${clock(SLEEP_END_MINUTES)} alarm needs a phone that is still on. Plug it in now."
            )
        }
    }

    private fun nightNotice(id: Int, title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NIGHT_CHANNEL_ID, "Bedtime", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Wind-down and charging reminders before the morning alarm" }
        )
        manager.notify(
            id,
            Notification.Builder(this, NIGHT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        )
    }

    /**
     * Holds every waiting, capped and new app; for when nothing can be judged
     * any more. During a focus session, EVERY app off the focus list too — the
     * focus wall covers them all, and losing the grant must not be the way
     * out of a session. The home screen's permanent tools (dialer, camera,
     * clock, calendar…) are spared. Released by [syncSuspensions] once the
     * grant is back and this service runs again.
     */
    private fun holdEverything() {
        if (!AppCloser.available(this)) return
        val inSession = anySessionActive()
        val everyApp = if (inSession) {
            runCatching {
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { it.activityInfo.packageName }.toSet()
            }.getOrDefault(emptySet()) - focusList -
                com.ogenrwotdaniel.wakeproof.launcher.permanentPackages(this).toSet()
        } else {
            emptySet()
        }
        (allGated + blockedLimits.keys + quarantined + everyApp)
            .filter { it != packageName && it !in neverSuspend }
            .forEach { AppCloser.hold(this, it) }
    }

    /**
     * Pauses media a walled or held app is still playing (2026-11-04): a
     * video dropped into picture-in-picture or playing in the background
     * outlived the visit it was opened on. Needs notification access, which
     * the notification guard already holds; without it this does nothing.
     */
    private fun pauseWalledMedia() {
        val sessions = runCatching {
            getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, WindowNotificationGuard::class.java))
        }.getOrNull() ?: return
        val held = AppCloser.held(this)
        sessions.forEach { controller ->
            val pkg = controller.packageName
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing && pkg != packageName && (pkg in held || walledNow(pkg))) {
                Log.i(TAG, "pausing $pkg's media")
                runCatching { controller.transportControls.pause() }
            }
        }
    }

    /**
     * Closes [pkg] where it is still on screen beside its wall (pop-up, split
     * screen, pin) — see [AppCloser]. Returns whether that is possible here;
     * false means the overlay cover is the only defence left.
     */
    private fun closeBesideWall(pkg: String): Boolean {
        if (!AppCloser.available(this)) return false
        // Never suspend Settings or the like, even for 1.5 s; the cover will do.
        if (pkg in neverSuspend) return false
        if (closing.add(pkg)) {
            scope.launch {
                try {
                    AppCloser.close(this@AppBlockService, pkg)
                } finally {
                    closing.remove(pkg)
                }
            }
        }
        return true
    }

    /**
     * The user has a task screen-pinned. System-wide, not just WakeProof's own
     * focus pin — that one has WakeProof in front and never reaches the check.
     */
    private fun userPinned(): Boolean = runCatching {
        activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED
    }.getOrDefault(false)

    /**
     * Throws [intent] in front of [packageName], once: the wall pauses the app,
     * so the next tick sees WakeProof in front and leaves it alone. The debounce
     * only covers the tick or two before the wall's own resume lands.
     */
    private fun wall(packageName: String, killAfter: Boolean, intent: () -> Intent) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == lastBlockedPackage && now - lastBlockedAt < REBLOCK_MILLIS) return
        lastBlockedPackage = packageName
        lastBlockedAt = now
        runCatching { startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess {
                coverFor = packageName
                coverWallAt = now
                coverGraceUntil = 0L
                killPending = killAfter
                // A hard wall closes the app outright, so nothing of it is left
                // to reach in a pop-up, split screen or Recents.
                if (killAfter) closeBesideWall(packageName)
            }
            .onFailure { Log.w(TAG, "could not raise the wall over $packageName", it) }
    }

    /**
     * After a wall: covers the walled app if it is still running beside the
     * wall in a pop-up or split screen ([popupCoverWanted]), and once it has
     * left the screen, kills its background process (2026-11-01). Recents
     * shows a thumbnail no ordinary app can blank, but a tap on a killed app
     * cold-starts it, which gives the wall the second it needs to land first.
     * Not after a wait — that app may be opened on a pass in a minute.
     */
    private fun followWalledApp(current: String) {
        val pkg = coverFor ?: return popupCover.hide()
        val now = SystemClock.elapsedRealtime()
        val age = now - coverWallAt
        val resumed = tracker.isResumed(pkg)
        val want = popupCoverWanted(
            wallAgeMillis = age,
            ownWallInFront = current == packageName,
            walledStillResumed = resumed,
            inGrace = now < coverGraceUntil
        )
        // Logged on change, so one `adb logcat -s AppBlockService` run shows
        // whether a pop-up really stays resumed beside its wall on this device.
        if (want != popupCover.isShowing) {
            Log.i(TAG, "popup cover ${if (want) "UP" else "down"} for $pkg (resumed=$resumed, age=${age}ms)")
        }
        if (want && closeBesideWall(pkg)) {
            popupCover.hide()
        } else if (want) {
            popupCover.show(
                "${usageRepository.appLabel(pkg)} is walled.\n\nClose its pop-up window to carry on.",
                "Let me close it"
            ) {
                coverGraceUntil = SystemClock.elapsedRealtime() + COVER_GRACE_MILLIS
                popupCover.hide()
            }
        } else {
            popupCover.hide()
        }
        if (killPending && !resumed && age >= COVER_SETTLE_MILLIS) {
            killPending = false
            runCatching { getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }
                .onFailure { Log.w(TAG, "could not stop $pkg in the background", it) }
        }
        if (age > COVER_MAX_MILLIS && !killPending) coverFor = null
    }

    /**
     * One line per change of app or verdict. The blocker failing after a phone
     * call could not be reproduced from the code alone; this is what to read
     * (`adb logcat -s AppBlockService`) the next time it happens.
     */
    private fun logVerdict(packageName: String, verdict: GateVerdict) {
        val line = "$packageName -> $verdict"
        if (line != lastLogged) {
            lastLogged = line
            Log.i(TAG, line)
        }
    }

    /**
     * Apps a hard block must never touch — see [essentialPackages]. A cap set on
     * any of them still nudges; it just never blocks.
     */
    private fun undismissablePackages(): Set<String> = essentialPackages(this)

    /**
     * The service is always on now, so its notification must be as quiet as
     * Android allows: IMPORTANCE_MIN folds it to a single line at the bottom of
     * the shade with no status-bar icon. A channel's importance cannot be
     * lowered once created, hence a new id and the old LOW channel deleted.
     */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Distraction wall", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Shown while WakeProof keeps distracting apps behind a wall" }
        )
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Distractions are behind a wall")
        .setContentText("Social apps wait 5 minutes. Spent and off-focus apps stay shut.")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val TAG = "AppBlockService"

        /** The running service, for [wallBefore]; same process, so a plain reference. */
        @Volatile private var instance: AppBlockService? = null

        /**
         * The wall [packageName] should get instead of the wait, or null when the
         * wait is the right screen (or the service is not running to say).
         */
        suspend fun wallBefore(packageName: String): Intent? = instance?.wallBefore(packageName)

        /**
         * Whether [packageName] is walled right now; null when the service is
         * not running (the caller falls back to the open window alone). Main
         * thread only, like the service's own loop.
         */
        fun walledNow(packageName: String): Boolean? = instance?.walledNow(packageName)

        /** A session on another of the user's devices walls this one; false if not running. */
        fun otherSessionActive(): Boolean = instance?.otherDeviceSessionActive() ?: false
        private const val CHANNEL_ID = "wakeproof_app_block_quiet"
        private const val LEGACY_CHANNEL_ID = "wakeproof_app_block"
        private const val NOTIFICATION_ID = 43
        private const val ALERT_CHANNEL_ID = "wakeproof_walls_down"
        private const val ALERT_NOTIFICATION_ID = 44

        /** canDrawOverlays is a binder call, so it is checked every 5 s, not every tick. */
        private const val OVERLAY_CHECK_EVERY = 10L

        /** How often queued loosenings are checked for landing. */
        private const val PROMOTE_EVERY_MILLIS = 5 * 60_000L
        private const val MEDIA_CHECK_MILLIS = 2_000L
        private const val NIGHT_PREFS = "night_reminders"
        private const val KEY_WIND_DOWN_DAY = "wind_down_day"
        private const val KEY_LIGHTS_OUT_DAY = "lights_out_day"
        private const val KEY_CHARGE_NAG_AT = "charge_nag_at"
        private const val NIGHT_CHANNEL_ID = "wakeproof_night"
        private const val WIND_DOWN_NOTIFICATION_ID = 71
        private const val CHARGE_NOTIFICATION_ID = 72

        /** Essential apps that are nonetheless walled during a focus session. */
        private val FOCUS_WALLED_ESSENTIALS = setOf("com.android.settings")
        /** Not a public constant; read like any other secure setting. */
        private const val NOTIFICATION_HISTORY_SETTING = "notification_history_enabled"

        /**
         * Foreground checks per tick. Halved from 1 s once the wait had to beat
         * the Edge panel: this is the longest an app can show before its wall
         * lands. Cheap, because [ForegroundTracker] reads only new events.
         */
        private const val POLL_MILLIS = 500L
        /** How often Do Not Disturb is lined up with sessions on other devices. */
        private const val DND_CHECK_MILLIS = 5_000L

        /** How long an extrapolated usage figure is trusted before re-measuring. */
        private const val BASELINE_REFRESH_MILLIS = 30_000L

        /**
         * Cadence while the screen is off. Nothing can be launched then, so this
         * only has to notice the screen coming back on — and [PowerManager.isInteractive]
         * is a field read, not a usage query.
         *
         * The screen-on receiver ends the sleep early on wake, so this is only a
         * fallback in case a broadcast is missed; the receiver is registered and
         * unregistered with the service, so it cannot outlive it.
         */
        private const val SCREEN_OFF_POLL_MILLIS = 60_000L

        /**
         * Grace after throwing up the wall, covering only the tick or two where
         * foreground events still name the blocked app. Leaving the app clears the
         * debounce outright, so this never becomes a usable window.
         */
        private const val REBLOCK_MILLIS = 1_500L

        /**
         * Starts enforcement when the grants are in place, and stops it otherwise.
         * Always on with them: the wait covers every social app installed, so
         * there is effectively always something to enforce. Safe to call on
         * every app start; the draw-over-other-apps grant is what exempts this
         * from the background foreground-service-start restriction.
         */
        fun sync(context: Context) {
            val enforceable = UsageStatsRepository(context).hasAccess() &&
                Settings.canDrawOverlays(context)
            val intent = Intent(context, AppBlockService::class.java)
            runCatching {
                if (enforceable) context.startForegroundService(intent)
                else context.stopService(intent)
            }
        }
    }
}
