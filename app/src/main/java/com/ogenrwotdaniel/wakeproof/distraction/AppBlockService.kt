package com.ogenrwotdaniel.wakeproof.distraction

import android.app.Notification
import com.ogenrwotdaniel.wakeproof.admin.UninstallLock
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
import android.content.pm.PackageManager
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
import com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingActivity
import com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService
import com.ogenrwotdaniel.wakeproof.alarm.StrictAlarm
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.sync.LiveCloudFeed
import com.ogenrwotdaniel.wakeproof.data.sync.LiveFocusSession
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimer
import com.ogenrwotdaniel.wakeproof.data.sync.LiveWatchTimerRules
import com.ogenrwotdaniel.wakeproof.wear.WatchSessionFeed
import com.ogenrwotdaniel.wakeproof.wear.WatchSessionNotifier
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.SessionDnd
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The one thing that stands in front of other apps. Decided per tick by
 * [gateVerdict]:
 *
 * * NUCLEAR FOCUS — while a Pomodoro session is active (breaks included), any
 *   app not on the focus list (Settings → Study apps) is walled until the
 *   session ends. The timer has no pause or stop, so neither does this.
 * * [LimitMode.BLOCK] caps — once an app's daily allowance is spent,
 *   [BlockedActivity] is thrown in front of it until midnight (or next open
 *   window for gated apps). No override.
 * * The [WallRules] in front of gated apps ([AppGate]): open only Monday to
 *   Thursday inside the open window (08:20–08:50 by default) with no countdown
 *   wait or separate daily budget, completely shut Friday through Sunday, shut
 *   at bedtime, and shut until the day's first focus block when no window is set.
 *
 * Losing either grant ends enforcement, so it is announced loudly and repeatedly
 * ([announceWallDown], [sync]) and, mid-session, costs the block's tree.
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
    @Inject lateinit var examDao: com.ogenrwotdaniel.wakeproof.data.db.ExamDao
    @Inject lateinit var liveFocusSession: LiveFocusSession
    @Inject lateinit var liveCloudFeed: LiveCloudFeed
    @Inject lateinit var alarmDevicePolicy: com.ogenrwotdaniel.wakeproof.alarm.AlarmDevicePolicy
    @Inject lateinit var summaryStore: com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
    @Inject lateinit var liveSummaryHold: com.ogenrwotdaniel.wakeproof.data.sync.LiveSummaryHold

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Latest blocked caps, kept current by a collector. Read on every tick, so it
     * must not be a DataStore round trip.
     */
    @Volatile private var blockedLimits: Map<String, AppLimit> = emptyMap()

    /** The focus list (Settings → Study apps). */
    @Volatile private var focusList: Set<String> = emptySet()

    @Volatile private var rules: WallRules = WallRules()


    /** When a session on another of the user's devices ends; null = none known. */
    @Volatile private var peerSessionEndsAt: Long? = null

    /** That session itself, when the other device says where it is; see [PeerSessions]. */
    @Volatile private var peerShared: com.ogenrwotdaniel.wakeproof.pomodoro.SharedSession? = null

    /** When the paired watch's session ends, heard over Bluetooth; 0 = none. */
    @Volatile private var watchSessionEndsAt = 0L

    /** The watch's own cloud timer: the tablet's way to hear of a watch session. */
    @Volatile private var cloudWatchTimer: LiveWatchTimer? = null

    /** The watch's live timer heard over Bluetooth (2026-11-05): the offline phone's way. */
    @Volatile private var bluetoothWatchTimer: LiveWatchTimer? = null

    /** Whichever copy of the watch timer is newer. */
    private val watchTimer: LiveWatchTimer?
        get() = LiveWatchTimerRules.newest(bluetoothWatchTimer, cloudWatchTimer)

    private var ticks = 0L

    /** A phone, whose night wall is the longer one; see [nightWallActive]. */
    private val phone by lazy { longNightDevice(this) }

    /**
     * Open between 03:00 and 20:00 (including through the phone's 03:00–08:00
     * and 18:00–20:00 hours and during focus sessions): Camera, Notes, Claude,
     * Gemini, Smart-Check, AnyOTP, Outlook, KakaoBank, Clock, Calendar, and
     * system file/photo pickers. Between 20:00 and 03:00 ([sleepWallActive]),
     * only [sleepExempt] remains open.
     */
    private val dayToolsExempt: Set<String>
        get() {
            nightCameras?.let { return it }
            val now = SystemClock.elapsedRealtime()
            val base = sleepExempt +
                com.ogenrwotdaniel.wakeproof.launcher.NAMED_TOOLS +
                com.ogenrwotdaniel.wakeproof.launcher.SYSTEM_DOCUMENT_PICKERS
            if (now - nightCamerasTriedAt < NIGHT_CAMERAS_RETRY_MILLIS) return nightCamerasGuess ?: base
            nightCamerasTriedAt = now
            val cameras = runCatching {
                packageManager.queryIntentActivities(
                    Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0
                ).mapNotNull { it.activityInfo }
                    .filter { it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
                    .map { it.packageName }
            }.getOrNull().orEmpty()
            val all = base + cameras
            if (cameras.isNotEmpty()) nightCameras = all
            nightCamerasGuess = all
            return all
        }

    private fun nightExemptAt(minuteOfDay: Int): Set<String> =
        if (sleepWallActive(minuteOfDay)) sleepExempt else dayToolsExempt

    @Volatile private var nightCamerasTriedAt = -NIGHT_CAMERAS_RETRY_MILLIS
    @Volatile private var nightCamerasGuess: Set<String>? = null

    /** [dayToolsExempt] once it has been read with a camera in it. */
    @Volatile private var nightCameras: Set<String>? = null

    /** Open through the 20:00–03:00 sleep wall: the clock (to check the alarm) and the calendar. */
    private val sleepExempt: Set<String> by lazy {
        (listOfNotNull(
            runCatching {
                packageManager.resolveActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), 0)
                    ?.activityInfo?.packageName
            }.getOrNull(),
            runCatching {
                packageManager.resolveActivity(
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), 0
                )?.activityInfo?.packageName
            }.getOrNull()
        ).filter { it != "android" } + listOf(
            com.ogenrwotdaniel.wakeproof.launcher.SAMSUNG_CLOCK,
            com.ogenrwotdaniel.wakeproof.launcher.GOOGLE_CLOCK,
            com.ogenrwotdaniel.wakeproof.launcher.SAMSUNG_CALENDAR,
            com.ogenrwotdaniel.wakeproof.launcher.GOOGLE_CALENDAR
        )).toSet()
    }

    /** Never quarantined: losing them costs more than any app is worth. See [refreshDefaults]. */
    @Volatile private var quarantineExempt: Set<String> = emptySet()

    /** Web browsers on this phone; re-resolved when an app is installed. */
    @Volatile private var browsers: Set<String> = emptySet()

    /** Apps in their first week on the phone; see [NewAppQuarantine]. */
    @Volatile private var quarantined: Set<String> = emptySet()

    /**
     * Notification history is on: it keeps the text of notifications the
     * walls strip, so while it is on every waiting app stays shut.
     */
    @Volatile private var historyOn = false

    /**
     * The local clock cannot be believed ([clockUntrusted]). Every wall reads it,
     * so a hand-set one could put the phone inside 08:20-08:50, or outside the
     * night, or past a session's end, at will.
     */
    @Volatile private var clockDoubted = false

    /** An app came or went: it may be a browser or a new feed, so look again. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED) {
                intent.data?.schemeSpecificPart?.let { pkg ->
                    scope.launch(Dispatchers.IO) { NewAppQuarantine.forget(context, pkg) }
                }
                // The removed app may have been the default home, SMS app or dialer;
                // its replacement must be essential before a wall can land on it.
                browsers = AppGate.browserPackages(this@AppBlockService)
                gatedCache.clear()
                scope.launch { refreshDefaults() }
                return
            }
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            browsers = AppGate.browserPackages(this@AppBlockService)
            gatedCache.clear()
            refreshQuarantine(checkReinstalls = true)
        }
    }

    /** The default SMS app, for sign-in codes, unless it is a named messenger; see [refreshDefaults]. */
    @Volatile private var smsApp: String? = null
    private var defaultSms: String? = null
    @Volatile private var homeApps: Set<String> = emptySet()

    /** Essential apps; see [refreshDefaults]. Nothing walls before [defaultsLoaded]. */
    @Volatile private var untouchable: Set<String> = emptySet()
    private val defaultsLoaded = CompletableDeferred<Unit>()
    // Until these land the walls would judge on default rules, no caps and an
    // empty quarantine, letting a stricter stored rule or a held app through.
    private val rulesLoaded = CompletableDeferred<Unit>()
    private val limitsLoaded = CompletableDeferred<Unit>()
    private val quarantineLoaded = CompletableDeferred<Unit>()

    /**
     * Waits for everything a verdict reads. Bounded, so a store that never
     * answers costs the walls a few seconds at start, not the walls themselves.
     */
    private suspend fun awaitReady() {
        defaultsLoaded.await()
        withTimeoutOrNull(READY_TIMEOUT_MILLIS) {
            rulesLoaded.await()
            limitsLoaded.await()
            quarantineLoaded.await()
        }
    }

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
            // Counted here, not only by the tick: a sleep that begins and ends inside
            // one tick's delay is never seen there, and the broadcast for it often
            // lands only after waking. Only counted, so a tick suspended mid-way does
            // not resume on state pulled from under it; see [resetHistory].
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                screenOffsSeen++
                return
            }
            // The clock may have been changed while the screen was off, when the
            // 2 s loop leaves it alone; the first tick after waking must not act
            // on the old verdict and open a gated app inside a faked window.
            recheckClock()
            screenOn.trySend(Unit)
        }
    }

    /** Last verdict logged, so the log says what changed rather than every tick. */
    private var lastLogged: String? = null

    /** Suppresses a second wall for the same app while the first is still up. */
    private var lastBlockedPackage: String? = null
    private var lastBlockedAt = 0L

    /**
     * The last wall was the uninstall lock's: once it is seen in front, the
     * debounce is dropped, so going straight back to the off screen is walled on
     * the next tick. Dropped only then, so a wall that never lands cannot be
     * relaunched every tick.
     */
    private var rearmOnLanding = false

    /** The cover over a walled app left running in a pop-up; see [popupCoverWanted]. */
    private val popupCover by lazy { PopupCover(this) }
    /** The cover over a walled app the user has screen-pinned; see [pinCoverWanted]. */
    private val pinCover by lazy { PopupCover(this) }
    private val activityManager by lazy { getSystemService(ActivityManager::class.java) }
    /** The app last walled, for the cover and the Recents kill; null = none pending. */
    private var coverFor: String? = null
    private var coverWallAt = 0L
    private var coverGraceUntil = 0L

    /** When each app's "Let me close it" was last pressed; see [coverGraceOffered]. */
    private val graceUsedAt = mutableMapOf<String, Long>()
    private var coverHasButton = false
    /** Kill [coverFor]'s background process once it leaves the screen (not for a wait). */
    private var killPending = false
    /** Walled apps seen resumed beside the front app on the last tick; see [wallBeside]. */
    private var besideLastTick: Set<String> = emptySet()
    private var besideTick = -1L
    private var lastBesideWallAt = 0L
    private val besideConfirmedAt = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        wallPrefs(this).edit().putBoolean(KEY_ARMED, true).apply()
        browsers = AppGate.browserPackages(this)
        StrictAlarm.resumeIfPending(this)
        // A summary still owed when the process died goes on holding the wall.
        reloadSummary()
        // WakeProof Clip: LAN server, mDNS, cloud listener, screenshots — all
        // self-contained, and idle unless the feature is on and paired.
        com.ogenrwotdaniel.wakeproof.clip.ClipRuntime.start(this, scope)
        registerReceiver(
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            },
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
                focusList = cfg.studyApps
            }
        }
        scope.launch {
            wallRulesRepository.rules.collect { next ->
                val browsersChanged = next.gateBrowsers != rules.gateBrowsers
                rules = next
                rulesLoaded.complete(Unit)
                if (browsersChanged) gatedCache.clear()
            }
        }
        // Re-read each new day, so exam week starts and lifts at midnight.
        scope.launch {
            while (true) {
                val today = LocalDate.now().toEpochDay()
                // A failed read keeps what was known: exam week must not lift on an I/O error.
                // Holds and seen ids are read first, apart from the exams, and off the main
                // thread (the first read loads the prefs file). Holds null when unreadable:
                // then nothing is written back, or the stored holds would be replaced by this
                // week's rows alone and a deleted exam's hold lost. An unreadable seen-list
                // only delays a new hold by one read.
                val (heldRead, seen) = withContext(Dispatchers.IO) {
                    val heldSet = runCatching {
                        nightPrefs.getStringSet(KEY_EXAMS_HELD, emptySet()).orEmpty()
                            .mapNotNullTo(mutableSetOf()) { decodeHeldExam(it) }
                    }.getOrNull()
                    val seenAt = runCatching {
                        nightPrefs.getStringSet(KEY_EXAMS_SEEN, emptySet()).orEmpty()
                            .mapNotNull { decodeSeen(it) }.toMap()
                    }.getOrNull()
                    // Either unreadable: nothing is written back. A lost seen-list written back
                    // would restart every exam's grace.
                    if (seenAt == null) null to emptyMap() else heldSet to seenAt
                }
                val held = heldRead.orEmpty()
                examWeek = try {
                    val rows = examDao.observeUpcoming(today).first()
                        .map { ExamRow(it.syncId, it.name, it.epochDay, it.updatedAt) }
                    val exams = rows.map { it.name to it.epochDay }
                    // Kept, so deleting, moving or undercutting an exam mid-week cannot lift the wall.
                    // Sideloaded only: on Play automatic time zone off is an ordinary
                    // traveller's setting, and must not cost a new exam its grace. A check
                    // that throws is no doubt: the grace then still ends by the clock.
                    val doubtedNow = SIDELOADED_BUILD &&
                        runCatching { clockUntrusted(this@AppBlockService) }.getOrDefault(false)
                    // Doubted on two reads running: a clock a few minutes off after a reboot
                    // or a zone change rights itself, and must not hold a date just typed
                    // with no grace. A clock kept wound is still doubted at the next read.
                    // The run of doubted reads is stored ([examClockDoubted]), so a restart
                    // neither begins it again nor counts as its second read. An unreadable
                    // store is no doubt and is not written back.
                    val storedDoubt = runCatching { nightPrefs.getString(KEY_EXAM_CLOCK_DOUBT, null) }
                    val boot = runCatching {
                        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT)
                    }.getOrDefault(-1)
                    val doubt = examClockDoubted(
                        doubtedNow, storedDoubt.getOrNull()?.let { decodeClockDoubt(it) }, boot, SystemClock.elapsedRealtime()
                    )
                    val doubted = doubt.first && storedDoubt.isSuccess
                    if (storedDoubt.isSuccess && heldRead != null) runCatching {
                        val next = doubt.second?.let { "${it.boot}|${it.since}|${it.lastSeen}" }
                        if (next != storedDoubt.getOrNull()) {
                            nightPrefs.edit().putString(KEY_EXAM_CLOCK_DOUBT, next).apply()
                        }
                    }
                    val holds = heldExamsAfter(
                        held, seen, rows, today, clockProven(this@AppBlockService), System.currentTimeMillis(),
                        clockDoubted = doubted
                    )
                    val keep = holds.held
                    if (heldRead != null && (keep != held || holds.seen != seen)) {
                        nightPrefs.edit()
                            .putStringSet(KEY_EXAMS_HELD, keep.mapTo(mutableSetOf()) { encodeHeldExam(it) })
                            .putStringSet(KEY_EXAMS_SEEN, holds.seen.mapTo(mutableSetOf()) { (k, t) -> encodeSeen(k, t) })
                            .apply()
                    }
                    // Holds unreadable: the week known before still stands until its exam.
                    val fromMemory = if (heldRead == null) listOfNotNull(examWeek) else emptyList()
                    examWeekHeld(exams, keep + fromMemory, today)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The last week known, too, while its exam has not passed: it may come
                    // from an exam not yet held.
                    examWeekHeld(emptyList(), held + listOfNotNull(examWeek?.takeIf { it.epochDay >= today }), today)
                }
                delay(EXAM_WEEK_REFRESH_MILLIS)
            }
        }
        // The phone's owed summary, heard here so the tablet starts no session through it.
        scope.launch {
            liveSummaryHold.peerUntil().collect {
                com.ogenrwotdaniel.wakeproof.data.sync.PeerSummaryHold.untilMillis = it
            }
        }
        scope.launch {
            liveFocusSession.peerSession().collect {
                peerSessionEndsAt = it?.endsAtMillis
                peerShared = it?.shared
                sessionsChanged()
            }
        }
        // A session on the watch walls this device too (2026-11-01): over
        // Bluetooth on the phone, which relays it to the tablet; the tablet also
        // reads the watch's own cloud timer, for a watch with its own Wi-Fi.
        scope.launch {
            WatchSessionFeed.endsAt(applicationContext).collect { endsAt ->
                watchSessionEndsAt = endsAt
                runCatching { liveFocusSession.relayWatch(endsAt, bluetoothWatchTimer?.toShared()) }
            }
        }
        scope.launch {
            liveCloudFeed.watchTimer().collect {
                cloudWatchTimer = it
                sessionsChanged()
            }
        }
        scope.launch {
            WatchSessionFeed.timer(applicationContext).collect {
                bluetoothWatchTimer = it
                // The tablet joins the watch's session through this phone's document.
                runCatching { liveFocusSession.relayWatch(watchSessionEndsAt, it?.toShared()) }
                sessionsChanged()
            }
        }
        // "Focus on your watch" also has to go when the watch falls silent past
        // its phase end (no push will say so), so it is re-judged on a clock too.
        scope.launch {
            while (true) {
                delay(WATCH_NOTIFICATION_RECHECK_MILLIS)
                sessionsChanged()
                // A new default dialer or home must be essential before it is needed.
                if (powerManager.isInteractive) refreshDefaults()
            }
        }
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
                // Read by the tick and the notification guard; neither acts on a
                // sleeping phone's clock, so it is left alone until the screen is on.
                if (powerManager.isInteractive) recheckClock()
                // Screen off too: audio is exactly what plays with the screen off.
                pauseWalledMedia()
                delay(MEDIA_CHECK_MILLIS)
            }
        }
        scope.launch {
            while (true) {
                // Ends a new app's quarantine week on time.
                refreshQuarantine()
                // A locked-alarm ring whose service died is started again.
                runCatching { StrictAlarm.resumeIfPending(this@AppBlockService) }
                runCatching { nightReminders() }
                delay(HOUSEKEEPING_EVERY_MILLIS)
            }
        }
        scope.launch {
            // An uninstall and reinstall while this service was down shows only here.
            refreshQuarantine(checkReinstalls = true)
        }
        // A session the process lost (a reboot, a force-stop) comes back here
        // too: this service is restarted by boot and by the 15-minute worker.
        PomodoroTimerService.restore(applicationContext)
        scope.launch {
            limitRepository.limits.collect { all ->
                blockedLimits = all.filterValues { it.mode == LimitMode.BLOCK }
                limitsLoaded.complete(Unit)
            }
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED
        )
        // The default apps are read off the main thread, and nothing walls until
        // they are in: with an empty essential set the dialer or the home screen
        // could be judged an ordinary app.
        scope.launch {
            applyFirstDefaults()
            awaitReady()
            watch()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** A device with no SMS app to wait for; see [walledNow]. */
    private val telephony by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    override fun onDestroy() {
        if (instance === this) instance = null
        // Stopped by sync for a lost grant before the tick saw it: the loss still
        // costs this block's tree. A session started while the walls were already
        // down never had this service running, so it loses nothing.
        // The alert sync posted may have been throttled; a lost tree is always news.
        if (!wallDownAnnounced && !enforceable(this) && PomodoroTimerService.breakout()) {
            postWallsDown(this, treeLost = true)
        }
        popupCover.hide()
        pinCover.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        com.ogenrwotdaniel.wakeproof.clip.ClipRuntime.stop()
        // Nothing would update them once this service is gone.
        com.ogenrwotdaniel.wakeproof.pomodoro.PeerSessions.update(null, null)
        WatchSessionNotifier.update(applicationContext, null)
        scope.cancel()
        super.onDestroy()
    }

    private fun recheckClock() {
        // A failed lookup keeps the last verdict: from the screen-on receiver or the
        // tick, a throw would crash the process or end the walls.
        clockDoubted = runCatching { clockUntrusted(this) }.getOrDefault(clockDoubted)
    }

    /** The front app at the last tick, so the clock is re-read when it changes. */
    private var lastFront: String? = null

    private suspend fun watch() {
        // Give the collectors a tick to land before the first decision.
        delay(POLL_MILLIS)
        while (true) {
            // A grant went away: nothing can be seen or covered any more. Say
            // so loudly, and stop burning cycles.
            if (!usageRepository.hasAccess() ||
                (ticks++ % OVERLAY_CHECK_EVERY == 0L && !Settings.canDrawOverlays(this))
            ) {
                // Nothing can be seen any more, so nothing can be judged. Every
                // wall is down until the grant returns. The alert comes back with
                // each [sync] until then, and [sync] is what brings the walls back.
                announceWallDown()
                stopSelf()
                return
            }

            // Nothing can be opened while the screen is off, so there is nothing
            // to catch. This is the single largest saving in the loop: a night's
            // sleep was ~19,000 foreground queries that could only ever answer
            // "no one is using anything".
            if (!powerManager.isInteractive) {
                // Once per time off, not on every minute's poll.
                if (!offResetDone) screenOffReset()
                offResetDone = true
                withTimeoutOrNull(SCREEN_OFF_POLL_MILLIS) { screenOn.receive() }
                // The broadcast for this same screen-off is dealt with already.
                screenOffsReset = screenOffsSeen
                continue
            }
            offResetDone = false
            // Off and on again inside one tick: only the broadcast saw it.
            if (screenOffsSeen != screenOffsReset) resetHistory()

            val today = LocalDate.now().toEpochDay()
            if (today != spentDay) {
                spentToday.clear()
                spentDay = today
            }
            // Still reading events while the lock screen is up keeps the fold
            // current, but nothing behind the lock screen can be used.
            val current = runCatching { tracker.current() }
                .onFailure { Log.w(TAG, "foreground query failed", it) }
                .getOrNull()
            if (current == null || keyguardManager.isKeyguardLocked) {
                popupCover.hide()
                pinCover.hide()
                delay(POLL_MILLIS)
                continue
            }
            // A clock changed in Settings and a quick switch to a gated app must
            // not slip in on the 2 s loop's old verdict.
            if (current != lastFront) {
                lastFront = current
                recheckClock()
            }
            measureBesideCaps(tracker.resumedPackages() - current - packageName)
            // A wall just went up beside the front app: the next tick sees what it left.
            if (followWalledApp(current)) {
                delay(POLL_MILLIS)
                continue
            }
            if (current == packageName) {
                // Looking at our own wall (or WakeProof itself). Not a re-entry.
                // The wall itself, not any WakeProof screen: with WakeProof in front and
                // the off screen beside it, clearing before the wall resumes would raise
                // it again on every tick.
                if (rearmOnLanding && tracker.newestClass(packageName) == BlockedActivity::class.java.name) {
                    rearmOnLanding = false
                    lastBlockedPackage = null
                }
                pinCover.hide()
                // Pop-ups float above WakeProof too, and using one records nothing, so
                // this is all that walls them here: over the home screen, the wall (a
                // second app's wall must not forget the first's pop-up) and the rest of
                // the app. Never over the alarm.
                wallBeside(current)
                delay(POLL_MILLIS)
                continue
            }
            // Genuinely somewhere else now (not just looking at our own wall), so
            // a re-entry should be walled at once rather than debounced.
            if (current != lastBlockedPackage) lastBlockedPackage = null

            val limit = blockedLimit(current)
            val judgedFrom = SystemClock.elapsedRealtime()
            val verdict = verdictFor(
                current = current,
                gated = isGated(current),
                capSpent = limit != null && capSpent(current, limit)
            )
            logVerdict(current, verdict)
            // capSpent may have waited on a slow read from midnight: neither a wall nor
            // a cover may land on a call or an alarm that came up during it. A read
            // inside a tick risks no more than the tick itself, and is not checked
            // again, as every entry into a capped app reads.
            if (frontMayHaveChanged(judgedFrom, SystemClock.elapsedRealtime()) &&
                runCatching { tracker.current() }.getOrNull() != current
            ) {
                delay(POLL_MILLIS)
                continue
            }
            // A pinned task refuses every activity from outside it, wall
            // included, so a pinned app is covered by an overlay instead.
            val pinned = userPinned()
            if (pinCoverWanted(pinned, verdict)) {
                if (!pinCover.isShowing) Log.i(TAG, "pin cover UP for $current ($verdict)")
                pinCover.show(
                    "${usageRepository.appLabel(current)} is walled.\n\n" +
                        "Screen pinning does not get past it. Unpin the app to carry on."
                )
            } else {
                if (pinCover.isShowing) Log.i(TAG, "pin cover down ($current, pinned=$pinned)")
                pinCover.hide()
                if (verdict != GateVerdict.ALLOW) {
                    wall(current, again = verdict == GateVerdict.ADMIN_LOCK_WALL) {
                        wallIntent(current, verdict, limit)
                    }
                } else {
                    wallBeside(current)
                }
            }
            delay(POLL_MILLIS)
        }
    }

    /** What the other devices run changed: the timer, Home and the notification all read it from here. */
    private fun sessionsChanged() {
        val watch = watchTimer
        val before = com.ogenrwotdaniel.wakeproof.pomodoro.PeerSessions.current.value
        com.ogenrwotdaniel.wakeproof.pomodoro.PeerSessions.update(watch?.toShared(), peerShared)
        val after = com.ogenrwotdaniel.wakeproof.pomodoro.PeerSessions.current.value
        // A phase ending on the watch is heard here too, wherever the user is working.
        com.ogenrwotdaniel.wakeproof.pomodoro.peerNews(before, after, System.currentTimeMillis())?.let { news ->
            (after ?: before)?.let {
                runCatching { com.ogenrwotdaniel.wakeproof.pomodoro.PeerSessionNotifier.post(this, news, it) }
            }
        }
        WatchSessionNotifier.update(applicationContext, watch)
    }

    /**
     * A focus session anywhere the user has a device: this one, a peer phone or
     * tablet (cloud), or the watch (Bluetooth, or its own cloud timer).
     */
    private fun anySessionActive(): Boolean =
        PomodoroTimerService.state.value.active || otherDeviceSessionActive()

    /** The summary notice was looked for once since this service started. */
    private var summaryNoticeChecked = false

    /**
     * Reads the owed summary. A restart removes every notification, so the
     * first reading puts the notice back when the summary is still owed: once,
     * or each screen-on would ring it again.
     */
    private fun reloadSummary() {
        scope.launch {
            runCatching {
                val state = summaryStore.load()
                if (summaryNoticeChecked) return@runCatching
                summaryNoticeChecked = true
                val owed = summaryStore.owed(state) ?: return@runCatching
                val manager = getSystemService(android.app.NotificationManager::class.java)
                if (manager.activeNotifications.none { it.id == com.ogenrwotdaniel.wakeproof.study.SUMMARY_NOTIFICATION_ID }) {
                    PomodoroTimerService.postSummaryNotice(this@AppBlockService, owed.askedAtMillis)
                }
            }
        }
    }

    /**
     * A session that ended here still owes its summary photo: the focus wall
     * holds through it ([gateVerdict]), or "mandatory" would mean ten minutes
     * on a feed.
     */
    private fun summaryOwed(): Boolean =
        summaryStore.owed() != null

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
        capSpent: Boolean,
        // For a notification the clock wall covers gated and capped apps only; see [walledNow].
        forNotification: Boolean = false,
        // False for a notification: the phone's extra night hours shut the app
        // and leave what it posted; see [nightWallStrips].
        wholeNight: Boolean = !forNotification,
        // False at launch: Settings is not on screen yet, and a stale admin screen
        // left in the remembered set would wall every Settings page. The tick
        // judges the screen once it is really up.
        judgeAdminScreen: Boolean = true
    ): GateVerdict {
        val now = LocalDateTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        return gateVerdict(
            // Settings is the one essential app a focus session walls
            // (2026-11-04, "once focus starts you can't run away"): it is where
            // grants, defaults and the date live. Calls, the home screen and
            // the quick-settings shade stay usable.
            essential = current in untouchable &&
                !(current in FOCUS_WALLED_ESSENTIALS && anySessionActive()),
            focusSessionActive = anySessionActive(),
            // Never for a notification: a removed one is lost for good, and the
            // summary shuts apps for half an hour, it does not cost a bank alert.
            summaryOwed = !forNotification && summaryOwed(),
            onFocusList = current in focusList || current in dayToolsExempt,
            capSpent = capSpent,
            gated = gated,
            outsideWindow = gated && windowShutActive(
                dayOfWeek = now.dayOfWeek,
                minuteOfDay = minuteOfDay,
                weekendShut = rules.weekendShut,
                startMinutes = rules.openWindowStartMinutes,
                endMinutes = rules.openWindowEndMinutes
            ),
            // Exam week shuts gated apps all day, window or not; see [ExamWeek].
            examWeek = gated && examWeek != null,
            bedtime = gated && bedtimeActive(
                minuteOfDay = minuteOfDay,
                startMinutes = rules.bedtimeStartMinutes
            ),
            sleeping = nightWallActive(minuteOfDay, phone) &&
                (wholeNight || nightWallStrips(minuteOfDay)) && current !in nightExemptAt(minuteOfDay) &&
                !(clockDoubted && current in CAPTIVE_PORTAL_PACKAGES),
            // Bed hours take no work: a session's list is open only in the phone's extra hours.
            sleepKeepsSession = !bedHoursShutWork(minuteOfDay),
            quarantined = held(current),
            clockUntrusted = clockDoubted && current !in sleepExempt && current != smsApp &&
                current !in CAPTIVE_PORTAL_PACKAGES &&
                ((SIDELOADED_BUILD && !forNotification) || gated || blockedLimit(current) != null),
            historyOn = historyOn && current != smsApp,
            capped = blockedLimit(current) != null,
            // Never for a notification: Settings' own must not go because of a screen.
            adminOffSwitch = !forNotification && judgeAdminScreen && deactivationShowing(current)
        )
    }

    /**
     * [pkg] is Settings with the screen that would switch [UninstallLock] off
     * resumed, in front or beside. The class test comes first, so the admin check
     * (a binder call) runs only on that screen.
     */
    private fun deactivationShowing(pkg: String): Boolean {
        if (!UninstallLock.AVAILABLE || pkg != UninstallLock.SETTINGS_PACKAGE) return false
        // The newest screen only: a lost PAUSED must not wall every Settings page
        // (the date and time one is the way out of the clock wall).
        val classes = listOfNotNull(tracker.newestClass(pkg))
        if (!UninstallLock.coversDeactivation(lockOn = true, pkg, classes)) return false
        // Not over the activation just made: its screen is still up for a moment.
        // Only onEnabled starts the grace. Stamping it here when the tick sees the
        // lock come on was tried and dropped: that can be seen hours late, on the
        // off screen, and would leave Deactivate unwalled. Worst case now is the
        // closing activation screen walled once, before onEnabled lands.
        return UninstallLock.isActive(this) && !UninstallLock.justEnabled(this)
    }

    /** In its first week, and not one of the apps that never waits it out. */
    private fun held(pkg: String): Boolean = pkg in quarantined && pkg !in quarantineExempt

    private fun isGated(pkg: String): Boolean = gatedCache.getOrPut(pkg) {
        AppGate.isGated(this, pkg, untouchable, browsers, rules.gateBrowsers, defaultSms)
    }

    /** Read here rather than filtered once, because which apps are essential can change. */
    private fun blockedLimit(pkg: String): AppLimit? = blockedLimits[pkg]?.takeIf { pkg !in untouchable }

    /** The screen thrown in front of [current] for [verdict]; never called for ALLOW. */
    private fun wallIntent(current: String, verdict: GateVerdict, limit: AppLimit?): Intent {
        val label = usageRepository.appLabel(current)
        val now = LocalDateTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        return when (verdict) {
            GateVerdict.ALLOW -> BlockedActivity.ruleIntent(
                this,
                "Not open now",
                "$label is shut right now. The phone and SMS always work."
            )
            GateVerdict.FOCUS_WALL -> BlockedActivity.focusIntent(this, label)
            GateVerdict.SUMMARY_WALL -> BlockedActivity.ruleIntent(
                this,
                "Session summary",
                "$label stays shut until your session summary is photographed on the focus " +
                    "timer, or half an hour after the session ended. The phone always works."
            )
            GateVerdict.CAP_WALL -> BlockedActivity.intent(
                context = this,
                label = label,
                usedMinutes = limit?.minutes ?: 0,
                capMinutes = limit?.minutes ?: 0,
                reopensAt = nextOpenFor(current, now.dayOfWeek)
            )
            GateVerdict.EXAM_WALL -> {
                // Read once: the state is volatile. Gone by now means the week just ended;
                // the next tick lets the app through.
                val exam = examWeek
                BlockedActivity.ruleIntent(
                    this,
                    "Exam week",
                    (exam?.let { "${it.name} is on ${LocalDate.ofEpochDay(it.epochDay).format(EXAM_DAY_FORMAT)}. " } ?: "") +
                        "$label and every other gated app stay shut until it is over. " +
                        "The phone and SMS always work."
                )
            }
            GateVerdict.WINDOW_WALL -> {
                val open = openWindow()
                val (headline, body) = windowWallCopy(
                    label = label,
                    today = now.dayOfWeek,
                    minuteOfDay = minuteOfDay,
                    weekendShut = rules.weekendShut,
                    startMinutes = open.first,
                    endMinutes = open.second,
                    formatClock = ::clock
                )
                BlockedActivity.ruleIntent(this, headline, body)
            }
            GateVerdict.BEDTIME_WALL -> BlockedActivity.ruleIntent(
                this,
                "Bedtime",
                "$label and every other gated app are behind the bedtime wall until " +
                    "${clock(BEDTIME_END_MINUTES)}."
            )
            GateVerdict.SLEEP_WALL -> {
                val copy = nightWallCopy(
                    minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute },
                    phone = phone,
                    formatClock = ::clock
                )
                BlockedActivity.ruleIntent(this, copy.first, copy.second)
            }
            GateVerdict.QUARANTINE_WALL -> BlockedActivity.ruleIntent(
                this,
                "New app quarantine",
                "$label was installed less than a week ago and stays shut until its 7-day quarantine ends." +
                    if (isHeldDefault(current)) " Choose another default in Android Settings." else "",
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS.takeIf { isHeldDefault(current) }
            )
            GateVerdict.CLOCK_WALL -> BlockedActivity.ruleIntent(
                this,
                "The clock can't be trusted",
                "Turn on automatic date, time and time zone in Android Settings and connect " +
                    "to the internet before opening $label.",
                Settings.ACTION_DATE_SETTINGS
            )
            GateVerdict.ADMIN_LOCK_WALL -> BlockedActivity.ruleIntent(
                this,
                "Uninstall lock is on",
                "Euler's Lab stays a device admin, so it cannot be uninstalled from the phone."
            )
            GateVerdict.HISTORY_WALL -> BlockedActivity.ruleIntent(
                this,
                "Notification history is on",
                "Turn off Notification history in Android Settings before opening $label."
            )
        }
    }

    /** A home or SMS app sitting out its quarantine week as the default; its wall offers the way out. */
    private fun isHeldDefault(pkg: String) = pkg in untouchable || pkg == defaultSms

    /**
     * Checks whether [packageName] is walled right now when tapped on the
     * WakeProof home screen ([com.ogenrwotdaniel.wakeproof.launcher.LauncherActivity]).
     *
     * Returning [wallIntent] opens [BlockedActivity] with the real reason, before
     * the app has shown anything.
     */
    private suspend fun wallBefore(packageName: String): Intent? {
        // Seen in a reading already, so nothing is gained by holding the dialer back
        // for the blanks in the others.
        // Not Settings mid-session, though: a session walls it, essential or not.
        if (!defaultsLoaded.isCompleted && packageName in untouchable &&
            !(packageName in FOCUS_WALLED_ESSENTIALS && anySessionActive())
        ) {
            return null
        }
        // A tap is never held for long: without the default apps nothing can be
        // judged safely, so it opens, and the tick walls it once they are in. The
        // first readings can take 10 s after boot, so only a session, which walls
        // nearly everything, is worth waiting for; any other tap opens at once.
        // An owed summary shuts as much as a session and waits the same way.
        if (!defaultsLoaded.isCompleted && !anySessionActive() && !summaryOwed()) return null
        withTimeoutOrNull(READY_TIMEOUT_MILLIS) { awaitReady() }
        if (!defaultsLoaded.isCompleted) return null
        val limit = blockedLimit(packageName)
        val capSpent = limit != null &&
            (usageRepository.todayForegroundMillisFor(setOf(packageName))[packageName] ?: 0L) >=
            limit.minutes * 60_000L
        val verdict = verdictFor(packageName, isGated(packageName), capSpent, judgeAdminScreen = false)
        logVerdict(packageName, verdict)
        return if (verdict == GateVerdict.ALLOW) null else wallIntent(packageName, verdict, limit)
    }

    /**
     * Whether [pkg] stands behind a wall right now, whichever one [gateVerdict]
     * finds. Asked by [WindowNotificationGuard] so a walled app's notifications
     * go too (2026-11-01): the shade, the Edge panel and an icon's long-press
     * preview were all ways to read an app the wall kept shut.
     *
     * The clock wall counts only for gated and capped apps here. On the sideloaded
     * build it shuts everything else as well, but those apps' notifications are lost for good
     * once removed, and a clock doubted by mistake must not cost a bank alert.
     *
     * No measuring: a cap counts only once [watch] has found it spent today,
     * so this can run on every posted notification.
     */
    private fun walledNow(pkg: String, wholeNight: Boolean = false): Boolean {
        // Unsure yet which apps are essential, or what the stored rules, caps and
        // quarantine say: keep it, as a removed notification is lost for good.
        if (pkg == packageName || !defaultsLoaded.isCompleted || !rulesLoaded.isCompleted ||
            !limitsLoaded.isCompleted || !quarantineLoaded.isCompleted
        ) {
            return false
        }
        // Believed for the walls by now, but a blank SMS app so soon after boot may be
        // a system still starting, and the code it would cost cannot be got back. The
        // minute's sweep takes anything walled once it reads. Only for an app that
        // could be the SMS app: a named messenger is walled even as the default.
        if (defaultSms == null && telephony && pkg !in AppGate.KNOWN_GATED_PACKAGES &&
            bootSettling(SystemClock.elapsedRealtime())
        ) {
            return false
        }
        // The 2 s loop leaves the clock alone with the screen off, but notifications
        // still arrive then, and must be judged on the clock as it is now.
        if (!powerManager.isInteractive) recheckClock()
        // Where the clock does not count, the verdict falls through to whatever
        // wall stands beneath it; doubting the clock must not lift that one.
        if (standingVerdict(pkg, forNotification = true, wholeNight = wholeNight) == GateVerdict.ALLOW) return false
        // Whatever wall stands over it (a session, the night, a new app's week),
        // the default SMS app's texts carry one-time codes and are never stripped.
        // The cached SMS app may be 15 s old, so one fresh reading before removing:
        // a default changed moments ago must not cost the code just asked for.
        return pkg != smsApp && pkg != AppGate.sparedSms(defaultSms(this))
    }

    /**
     * [pkg]'s verdict were it opened now, without measuring: a cap counts only
     * once [watch] has found it spent today.
     */
    private fun standingVerdict(
        pkg: String,
        forNotification: Boolean = false,
        wholeNight: Boolean = !forNotification
    ): GateVerdict {
        val capMinutes = blockedLimit(pkg)?.minutes
        val spentAt = spentToday[pkg]
        return verdictFor(
            current = pkg,
            gated = isGated(pkg),
            capSpent = capMinutes != null && spentAt != null && capMinutes <= spentAt,
            forNotification = forNotification,
            wholeNight = wholeNight
        )
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

    /** Next opening phrase after today for [current] if gated (`"tomorrow at 08:20"`, `"Monday at 08:20"`). */
    private fun nextOpenFor(current: String, today: java.time.DayOfWeek): String? {
        // Exam week shuts gated apps all day until the exam is over.
        examWeek?.takeIf { gatedCache[current] == true }?.let { exam ->
            return "after ${exam.name} (${LocalDate.ofEpochDay(exam.epochDay).format(EXAM_DAY_FORMAT)})"
        }
        val gated = gatedCache[current] ?: false
        val open = openWindow()
        return nextOpenAfterToday(
            gated = gated,
            today = today,
            weekendShut = rules.weekendShut,
            startMinutes = open.first,
            endMinutes = open.second,
            formatClock = ::clock
        )
    }

    /**
     * The exam that makes today exam week ([examWeekHeld]), or null. It can name a
     * held exam since deleted or moved: a begun week never lifts early.
     */
    @Volatile private var examWeek: ExamWeek? = null

    /** Night reminders and the held exam week share one file. */
    private val nightPrefs by lazy {
        createDeviceProtectedStorageContext().getSharedPreferences(NIGHT_PREFS, Context.MODE_PRIVATE)
    }

    /** When gated apps really open and close, for wall copy; see [gatedOpenWindow]. */
    private fun openWindow(): Pair<Int, Int> = gatedOpenWindow(rules) ?: (RULE_OFF_MINUTES to RULE_OFF_MINUTES)

    /** "08:20" or "8:20 AM", as the phone tells time. */
    private fun clock(minutes: Int): String =
        com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime(this, minutes / 60, minutes % 60)

    /**
     * Re-reads the default apps. Device owner used to freeze them; now a new
     * launcher or SMS app must not stay essential after it is retired.
     *
     * A home or SMS app is not exempt from the quarantine, so one made the
     * default in its first week still waits it out. The dialer is: a wall must
     * never stand in front of a call, and no wall ever looks at the in-call screen.
     *
     * The lookups run off the main thread, where the 500 ms tick runs.
     */
    private suspend fun refreshDefaults() = defaultsLock.withLock {
        // One read at a time: an older one finishing last would put a retired
        // home or SMS app back. A failed read keeps the last good one.
        withContext(Dispatchers.Default) { readDefaults() }?.let(::applyDefaults)
    }

    /**
     * The first readings. A system still starting answers with blanks, so nothing
     * acts on them ([defaultsLoaded], [defaultApps]) until the blanks fill: a blank
     * SMS app would cost a sign-in code for good. After [firstReadAttempts] what can
     * be read is believed: a phone with a real gap, or a lasting failure, must still
     * get its walls.
     */
    private suspend fun applyFirstDefaults() {
        val attempts = firstReadAttempts(SystemClock.elapsedRealtime())
        for (attempt in 1..attempts) {
            // A failed read must not leave the walls unstarted for good.
            runCatching { refreshDefaults() }.onFailure { Log.w(TAG, "default apps unreadable", it) }
            if (lastDefaults?.let { firstReadingReady(it, telephony) } == true) break
            if (attempt < attempts) delay(DEFAULTS_RETRY_MILLIS)
        }
        if (lastDefaults != null) {
            defaultsLoaded.complete(Unit)
            return
        }
        val bare = withContext(Dispatchers.Default) {
            val dialer = defaultDialer(this@AppBlockService)
            // One last try at the home: without it a sleep wall lands on the home screen.
            val homes = homePackages(this@AppBlockService)
            val apps = DefaultApps(
                essentialPackages(this@AppBlockService, homes, dialer),
                homes, dialer, defaultSms(this@AppBlockService)
            )
            defaultsFrom(apps)
        }
        defaultsLock.withLock { if (lastDefaults == null) applyDefaults(bare) }
        defaultsLoaded.complete(Unit)
    }

    private val defaultsLock = Mutex()

    private class Defaults(val apps: DefaultApps, val exempt: Set<String>)

    /** PackageManager, Telecom and Settings lookups: any thread. */
    private fun readDefaults(): Defaults? = readDefaultApps(this, lastDefaults)?.let(::defaultsFrom)

    private fun defaultsFrom(apps: DefaultApps): Defaults {
        val exempt = (apps.essential - apps.homes) + AppGate.QUARANTINE_EXEMPT +
            com.ogenrwotdaniel.wakeproof.launcher.NAMED_TOOLS + listOfNotNull(
            // The keyboard: every text field on the phone.
            runCatching {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.substringBefore('/')
            }.getOrNull(),
            // Web content inside every other app, should a browser provide it.
            runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()
        )
        // An app that is the dialer as well as the SMS app (Truecaller, some carrier
        // apps) keeps the dialer's exemption: no wall may stand in front of a call.
        return Defaults(apps, exempt - setOfNotNull(apps.sms?.takeUnless { it == apps.dialer }))
    }

    /** The last reading, which may still be partial until [defaultsLoaded]; see [applyFirstDefaults]. */
    @Volatile private var lastDefaults: DefaultApps? = null

    /** Main thread: [gatedCache] and the sets are read by the tick. */
    private fun applyDefaults(d: Defaults) {
        lastDefaults = d.apps
        // isGated reads both.
        if (d.apps.essential != untouchable || d.apps.sms != defaultSms) gatedCache.clear()
        untouchable = d.apps.essential
        homeApps = d.apps.homes
        defaultSms = d.apps.sms
        smsApp = d.apps.sparedSms
        quarantineExempt = d.exempt
    }

    /** When [pkg] was installed (not updated); null when unreadable. */
    private fun firstInstalledAt(pkg: String): Long? =
        runCatching { packageManager.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()

    /**
     * Recomputes [quarantined] from the installed apps. [checkReinstalls] reads every app's install time, so only at start and on an
     * install, not on the periodic refresh that only ends weeks on time.
     */
    private fun refreshQuarantine(checkReinstalls: Boolean = false) {
        // One pass at a time: two installs in a row start two passes, and an older
        // snapshot finishing last would drop the newer app from [quarantined].
        scope.launch { quarantineLock.withLock { refreshQuarantinePass(checkReinstalls) } }
    }

    private val quarantineLock = Mutex()

    private suspend fun refreshQuarantinePass(checkReinstalls: Boolean) {
        try {
            quarantinePass(checkReinstalls)
        } finally {
            // Also when the pass gives up: an unreadable app list is no reason to hold the walls back.
            quarantineLoaded.complete(Unit)
        }
    }

    private suspend fun quarantinePass(checkReinstalls: Boolean) {
        if (powerManager.isInteractive || checkReinstalls) refreshDefaults()
        if (!NewAppQuarantine.ENFORCED) return
        val snapshotAt = SystemClock.elapsedRealtime()
        val installed = runCatching {
            usageRepository.installedApps().map { it.packageName }.toSet()
        }.getOrNull() ?: return
        // Neither a stamp nor the end of a week may come from a clock nothing vouches for.
        val proven = clockProven(this@AppBlockService)
        val held = withContext(Dispatchers.Default) {
            NewAppQuarantine.observe(
                this@AppBlockService, installed, proven,
                firstInstalledAt = if (checkReinstalls) ::firstInstalledAt else null,
                snapshotAt = snapshotAt
            )
            NewAppQuarantine.quarantined(this@AppBlockService, proven)
        }
        quarantined = held.filter { it in installed }.toSet()
    }

    /**
     * Usage access or draw-over-apps was taken away, which switches every wall
     * off at once. Not stoppable from here, so at least never silent; and in
     * the middle of a focus block it costs that block's tree — the session
     * itself carries on, since it cannot be stopped.
     */
    private fun announceWallDown() {
        wallDownAnnounced = true
        postWallsDown(this, treeLost = PomodoroTimerService.breakout())
    }

    /** The tick already told of this loss, so [onDestroy] must not tell it again. */
    private var wallDownAnnounced = false

    /**
     * The lights-out notice and the charging nag ([NightRules]). The nag only
     * where alarms ring: the tablet going flat costs nothing at the alarm.
     */
    private fun nightReminders() {
        val now = System.currentTimeMillis()
        val minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute }
        val today = LocalDate.now().toEpochDay()
        val prefs = nightPrefs
        if (lightsOutDue(minuteOfDay, prefs.getLong(KEY_LIGHTS_OUT_DAY, -1L), today)) {
            prefs.edit().putLong(KEY_LIGHTS_OUT_DAY, today).apply()
            nightNotice(
                LIGHTS_OUT_NOTIFICATION_ID,
                "Lights out",
                "It's ${clock(BEDTIME_MINUTES)}. Phone on charge, into bed. " +
                    "Your alarm is at ${clock(SLEEP_END_MINUTES)}."
            )
        }
        if (!alarmDevicePolicy.ringsAlarms) return
        val cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val strictHeld = StrictAlarm.syncIds(this).isNotEmpty()
        // A warning that is no longer true must not stay in the shade.
        if (cameraGranted || !strictHeld) {
            getSystemService(NotificationManager::class.java).cancel(CAMERA_NOTIFICATION_ID)
        }
        if (cameraNagDue(strictHeld, cameraGranted, prefs.getLong(KEY_CAMERA_NAG_AT, 0L), now)) {
            prefs.edit().putLong(KEY_CAMERA_NAG_AT, now).apply()
            nightNotice(
                CAMERA_NOTIFICATION_ID,
                "Camera access is off",
                "Your locked alarms need the camera to count pushups. Tap to allow it.",
                PendingIntent.getActivity(
                    this, 2,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", packageName, null)),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
                silent = quietNow()
            )
        }
        val battery = getSystemService(android.os.BatteryManager::class.java)
        val level = runCatching {
            battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val charging = runCatching { battery.isCharging }.getOrDefault(true)
        if (chargeNagDue(
                minuteOfDay, level, charging, prefs.getLong(KEY_CHARGE_NAG_AT, 0L), now,
                lastSlotMinutes = StrictAlarm.lastSlotMinute
            )
        ) {
            prefs.edit().putLong(KEY_CHARGE_NAG_AT, now).apply()
            nightNotice(
                CHARGE_NOTIFICATION_ID,
                "Charge your phone — $level %",
                "Your locked alarms need a phone that is still on. Plug it in now."
            )
        }
    }

    private fun nightNotice(id: Int, title: String, text: String, tap: PendingIntent? = null, silent: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        // A quiet one goes on a low-importance channel: still in the shade on
        // waking, with no heads-up or sound at night.
        val channel = if (silent) {
            quietChannel()
        } else {
            NotificationChannel(NIGHT_CHANNEL_ID, "Bedtime", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Lights-out and charging reminders before the morning alarm" }
        }
        manager.createNotificationChannel(channel)
        manager.notify(
            id,
            Notification.Builder(this, channel.id)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }

    /**
     * Pauses media a walled app is still playing (2026-11-04): a
     * video dropped into picture-in-picture or playing in the background
     * outlived the visit it was opened on. Needs notification access, which
     * the notification guard already holds; without it this does nothing.
     */
    private fun pauseWalledMedia() {
        val sessions = runCatching {
            getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, WindowNotificationGuard::class.java))
        }.getOrNull() ?: return
        sessions.forEach { controller ->
            val pkg = controller.packageName
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            // The whole night, unlike a notification: paused audio is not lost,
            // and a shut app that goes on playing is not shut.
            if (playing && walledNow(pkg, wholeNight = true)) {
                Log.i(TAG, "pausing $pkg's media")
                runCatching { controller.transportControls.pause() }
            }
        }
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
     * only covers the tick or two before the wall's own resume lands. True when
     * the wall went up. [again] marks a screen that outlives its wall (Settings,
     * which is essential and so not killed outside a session): see [rearmOnLanding].
     */
    private fun wall(packageName: String, again: Boolean = false, intent: () -> Intent): Boolean {
        // The one place every wall passes: nothing may hide a ringing alarm's screen.
        if (alarmHoldsScreen()) return false
        val now = SystemClock.elapsedRealtime()
        // The off screen gets a debounce of just over one tick: Back off the wall before a
        // tick saw it would otherwise leave Deactivate unwalled for 1.5 s. BlockedActivity
        // is singleTask, so raising it again brings the same wall forward, never a stack.
        val debounce = if (again) ADMIN_REBLOCK_MILLIS else REBLOCK_MILLIS
        if (packageName == lastBlockedPackage && now - lastBlockedAt < debounce) return false
        lastBlockedPackage = packageName
        lastBlockedAt = now
        return runCatching { startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess {
                // Only for a wall that started: a failed one must not drop a later debounce.
                rearmOnLanding = again
                coverFor = packageName
                coverWallAt = now
                coverGraceUntil = 0L
                // A default home sitting out its quarantine is walled, not killed:
                // Home would only start it again, round and round.
                killPending = packageName !in untouchable
                // With the wall, not a tick or three later: a pop-up floats above the
                // wall and was usable until the cover came. The next tick takes it
                // down again if the app has left the screen.
                showCover(packageName, now)
            }
            .onFailure { Log.w(TAG, "could not raise the wall over $packageName", it) }
            .isSuccess
    }

    /** Puts the cover over [pkg], with or without its close button ([coverGraceOffered]). */
    private fun showCover(pkg: String, now: Long) {
        // The button lifts the cover so the pop-up's X can be reached, and for
        // those seconds the app can be used: pressed again and again it was a
        // way through every wall (the user's report, 2026-11-13). So it is
        // offered once per app in [COVER_GRACE_EVERY_MILLIS]; after that the
        // cover stays and the window is closed from Recents.
        val offer = coverGraceOffered(graceUsedAt[pkg], now)
        // An existing cover only takes new text, so one with the wrong button goes first.
        if (popupCover.isShowing && coverHasButton != offer) popupCover.hide()
        coverHasButton = offer
        if (offer) {
            popupCover.show(
                "${usageRepository.appLabel(pkg)} is walled.\n\nClose its pop-up window to carry on.",
                "Let me close it"
            ) {
                val pressedAt = SystemClock.elapsedRealtime()
                graceUsedAt[pkg] = pressedAt
                coverGraceUntil = pressedAt + COVER_GRACE_MILLIS
                popupCover.hide()
            }
        } else {
            popupCover.show(
                "${usageRepository.appLabel(pkg)} is walled.\n\n" +
                    "Open Recents and swipe its pop-up window away to carry on."
            )
        }
    }

    private enum class OpenCheck { OPEN, CLOSED, UNKNOWN }

    /**
     * Until when each app's history is left alone after a failed read or a refused
     * wall: the tick must not retry every 500 ms. Per app, as one app's trouble says
     * nothing about a pop-up beside it. A key per app ever refused, so it stays small.
     */
    private val historyBackoff = mutableMapOf<String, Long>()

    private fun historyHeldOff(pkg: String, now: Long): Boolean {
        val until = historyBackoff[pkg] ?: return false
        if (now < until) return true
        historyBackoff.remove(pkg)
        return false
    }

    /** See [alarmHoldsWalls]. */
    private fun alarmHoldsScreen(): Boolean = alarmHoldsWalls(
        ringing = AlarmRingingService.ringing,
        // First hand from the activity: usage events land too late to vouch for it.
        screenInFront = AlarmRingingActivity.resumed,
        ringAgeMillis = SystemClock.elapsedRealtime() - AlarmRingingService.ringStartedAt
    )

    /** Whether a wall for [walled] may land while [front] is in front, as things stand. */
    private fun mayLandOver(front: String, walled: String): Boolean =
        wallMayLandOver(front, walled, packageName, alarmHoldsScreen(), untouchable, homeApps)

    /** A finished whole-history read, waiting for the tick to act on it. */
    private class HistoryAnswer(val open: Boolean, val at: Long, val lastResumedAt: Long)

    /**
     * Per app: the grace check and the beside check can each be waiting on a
     * different app, and one slot shared between them would have each throw the
     * other's read away.
     */
    private val historyReads = mutableMapOf<String, Job>()

    /**
     * A read for [pkg] is under way. No timeout: the tick asks the same service on
     * the main thread, so a call hung for good has stopped the walls anyway, and
     * a slow read finishes. The slot is the read's ticket; one no longer in it
     * stays silent.
     */
    private fun readRunning(pkg: String): Boolean = historyReads[pkg]?.isActive == true

    /** Screen-offs the broadcast saw, and the last one [resetHistory] dealt with. Main thread. */
    private var screenOffsSeen = 0
    private var screenOffsReset = 0

    /**
     * A read or a confirmation from before the screen went off says nothing about
     * after it. Cancelled: one still queued never runs, and one already blocked in
     * the system finishes unheard ([screenOnRun]), its slot freed so its app gets a
     * fresh check on waking. Also from the tick's next pass after a screen-off it
     * did not see; then, with the screen on, only this, and one landing late
     * costs no more than a check run again.
     */
    private fun resetHistory() {
        screenOffsReset = screenOffsSeen
        screenOnRun++
        historyReads.values.forEach { it.cancel() }
        historyReads.clear()
        historyAnswers.clear()
        besideConfirmedAt.clear()
    }

    /** Whether the screen-off branch already reset for this time the screen is off. */
    private var offResetDone = false

    /**
     * Everything the tick knows only while the screen is on. From the tick's
     * screen-off branch, once per time off, and only there: with the screen on
     * again, forgetting the resumed set would lose the app in front and leave it
     * unwalled until it resumes again.
     */
    private fun screenOffReset() {
        popupCover.hide()
        pinCover.hide()
        // Nothing stays resumed with the screen off, and whatever really
        // is on screen resumes again on waking.
        tracker.forgetResumed()
        // Kept across a screen-off only the broadcast saw: a broadcast landing late
        // for one already dealt with would otherwise drop them mid screen-on, and a
        // phantom still in the resumed set would lose its back-off.
        besideRepeats.clear()
        historyBackoff.clear()
        resetHistory()
        // Force a fresh measurement on wake rather than extrapolating
        // across the whole time the phone was down.
        baselineFor = null
    }

    /** Counts [resetHistory]s, so a read begun before one is not heard after it. */
    private var screenOnRun = 0
    private val historyAnswers = mutableMapOf<String, HistoryAnswer>()

    /**
     * Whether [pkg] is really still open, from its whole history, and may be walled
     * over [front]. The read can walk as far back as the screen has been on, so it
     * runs on its own coroutine and the tick never waits: enforcement of the front
     * app must not stall behind it. The first call starts the read and answers
     * [OpenCheck.UNKNOWN], as it does while the read runs, when the history was
     * unreadable, or when [front] may no longer take a wall; a later tick acts on
     * the answer, against the front app as it is then.
     */
    private fun confirmedOpen(pkg: String, front: String): OpenCheck {
        val now = SystemClock.elapsedRealtime()
        // An old answer says nothing about now: the app may have closed since.
        historyAnswers.entries.removeAll { now - it.value.at > HISTORY_ANSWER_MILLIS }
        historyReads.entries.removeAll { !it.value.isActive }
        val answer = historyAnswers[pkg]
        if (answer != null) {
            historyAnswers.remove(pkg)
            // The tick kept reading events meanwhile. A resume it saw says the app is
            // open again; forgetting it now would lose it, with the event already read.
            if (!answer.open && tracker.lastResumedAt(pkg) != answer.lastResumedAt) return OpenCheck.UNKNOWN
            return judgeOpen(pkg, front, answer.open, now)
        }
        if (readRunning(pkg)) return OpenCheck.UNKNOWN
        val from = tracker.confirmFrom(pkg) ?: return OpenCheck.CLOSED
        val lastResumedAt = tracker.lastResumedAt(pkg)
        val run = screenOnRun
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val read = withContext(Dispatchers.IO) { runCatching { tracker.resumedIn(pkg, from) }.getOrNull() }
            val at = SystemClock.elapsedRealtime()
            val stillTicketed = historyReads[pkg] === coroutineContext[Job]
            if (run != screenOnRun || !stillTicketed) {
                // Begun before the screen went off: unheard.
            } else if (read == null) {
                historyBackoff[pkg] = at + BESIDE_CONFIRM_MILLIS
            } else {
                historyAnswers[pkg] = HistoryAnswer(read, at, lastResumedAt)
            }
        }
        // In its slot before it runs, so its own check of the ticket finds it.
        historyReads[pkg] = job
        job.start()
        return OpenCheck.UNKNOWN
    }

    /** Acts on a whole-history answer on the thread that owns the tracker's state. */
    private fun judgeOpen(pkg: String, front: String, stillOpen: Boolean, now: Long): OpenCheck {
        // Only entries inside the window matter, so the map stays small however
        // long the service runs.
        besideConfirmedAt.entries.removeAll { now - it.value > BESIDE_CONFIRM_MILLIS }
        if (!tracker.settle(pkg, stillOpen)) {
            // Forgotten by the tracker, so only a real reopen brings it back, and
            // that one must be checked at once, not after the 30 s wait.
            besideConfirmedAt.remove(pkg)
            return OpenCheck.CLOSED
        }
        if (!mayLandOver(front, pkg)) {
            // Not now, and the read is not to be repeated on every tick until it can.
            historyBackoff[pkg] = now + BESIDE_CONFIRM_MILLIS
            return OpenCheck.UNKNOWN
        }
        besideConfirmedAt[pkg] = now
        return OpenCheck.OPEN
    }

    /**
     * Measures the BLOCK caps of [beside], the apps open beside the front app.
     * [capSpent] only ever runs for the front app, so a capped app kept in a pop-up
     * would never be found spent. A read from midnight per app, on its own coroutine
     * so the tick never waits, and not again before the cap could be spent
     * ([nextBesideCapReadIn]): an app whose PAUSED was lost sits in the resumed set
     * until the screen goes off, and must not cost a whole day's fold every 30 s.
     */
    private fun measureBesideCaps(beside: Collection<String>) {
        if (beside.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        besideCapReads.entries.removeAll { now >= it.value.at }
        for (pkg in beside) {
            val limit = blockedLimit(pkg) ?: continue
            // A changed cap voids the old bound: a tighter one may already be spent.
            if (besideCapReads[pkg]?.limitMinutes == limit.minutes) continue
            if ((spentToday[pkg] ?: -1) >= limit.minutes) continue
            // Held while the read runs, and as the retry gap if it fails.
            besideCapReads[pkg] = BesideCapRead(now + BESIDE_CONFIRM_MILLIS, limit.minutes)
            val day = spentDay
            scope.launch {
                val used = runCatching { usageRepository.todayForegroundMillisFor(setOf(pkg))[pkg] }.getOrNull()
                    ?: return@launch
                val limitMillis = limit.minutes * 60_000L
                // A read straddling midnight must not spend the new day's cap.
                if (day == spentDay && used >= limitMillis) spentToday[pkg] = limit.minutes
                if (besideCapReads[pkg]?.limitMinutes == limit.minutes) {
                    besideCapReads[pkg] = BesideCapRead(
                        SystemClock.elapsedRealtime() + nextBesideCapReadIn(used, limitMillis),
                        limit.minutes
                    )
                }
            }
        }
    }

    /** When a beside app's cap is next read, and the cap that bound was worked out for. */
    private class BesideCapRead(val at: Long, val limitMinutes: Int)

    private val besideCapReads = mutableMapOf<String, BesideCapRead>()

    /** Beside walls over WakeProof's own screens so far this screen-on; see [besideRepeatGap]. */
    private class BesideRepeat(val walls: Int, val lastAt: Long)

    private val besideRepeats = mutableMapOf<String, BesideRepeat>()

    /**
     * Walls an app still running beside [current], which is itself allowed. A
     * pop-up or split screen outlives leaving its wall, and using it records
     * nothing, so [current] alone never shows it. Two ticks running, so the
     * moment two apps overlap in a plain app switch is not mistaken for one.
     * [followWalledApp] then covers it from the wall. The set is folded from
     * events, so a lost PAUSED could leave a phantom here; [watch] empties the
     * set at every screen-off it sees, which is the most a phantom can cost. One
     * off and on again inside a single 500 ms tick resets only the history checks
     * ([resetHistory]), so a phantom then lasts to the next, and its whole-history
     * read still has to say it is open.
     */
    private fun wallBeside(current: String) {
        val resumed = tracker.resumedPackages()
        // Closed for real, so a reopen is checked at once. A phantom stays in the set.
        besideRepeats.keys.retainAll(resumed)
        if (resumed.size < 2 ||
            // Covered already, or given its moment to be closed.
            popupCover.isShowing || SystemClock.elapsedRealtime() < coverGraceUntil
        ) {
            besideLastTick = emptySet()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // On WakeProof's own screens the cover holds a real pop-up between walls.
        val ownFront = current == packageName
        val walled = resumed.filter {
            it != packageName && mayLandOver(current, it) &&
                !leftToCover(it, coverFor, now - coverWallAt, ownFront)
        }
            .associateWith(::standingVerdict)
            .filterValues { it != GateVerdict.ALLOW }
        // Consecutive ticks: a tick that came another way must not count as the last.
        val previous = if (besideTick + 1 == ticks) besideLastTick else emptySet()
        // Picked past these, as one app waiting must not leave another beside it alone.
        // The repeat gap keeps a phantom from returning every 30 s. Every beside wall
        // needs its own whole-history read, the thing that stops a closed app whose
        // PAUSED landed late from walling the phone. One read must not vouch for later
        // walls: the app may close in between and its PAUSED be lost. The read is
        // costly, so at most twice a minute per app.
        fun waiting(app: String): Boolean {
            val repeat = besideRepeats[app]
            return (ownFront && repeat != null && now - repeat.lastAt < besideRepeatGap(repeat.walls - 1)) ||
                now - (besideConfirmedAt[app] ?: Long.MIN_VALUE / 2) <= BESIDE_CONFIRM_MILLIS ||
                historyHeldOff(app, now) ||
                // Its read is running: a second app beside it must not wait on that.
                readRunning(app)
        }
        val settled = walled.keys.firstOrNull { it in previous && !waiting(it) }
        besideLastTick = walled.keys
        besideTick = ticks
        val pkg = settled ?: return
        if (now - lastBesideWallAt < COVER_SETTLE_MILLIS) return
        when (confirmedOpen(pkg, current)) {
            OpenCheck.UNKNOWN -> return
            OpenCheck.CLOSED -> {
                Log.i(TAG, "$pkg was not really running beside $current")
                besideLastTick = emptySet()
                return
            }
            OpenCheck.OPEN -> Unit
        }
        // Judged again: a session may have ended or the window opened during the read.
        val verdict = standingVerdict(pkg)
        if (verdict == GateVerdict.ALLOW) return
        lastBesideWallAt = now
        Log.i(TAG, "$pkg is still running beside $current -> $verdict")
        if (wall(pkg, again = verdict == GateVerdict.ADMIN_LOCK_WALL) {
                wallIntent(pkg, verdict, blockedLimit(pkg))
            } && ownFront
        ) {
            besideRepeats[pkg] = BesideRepeat((besideRepeats[pkg]?.walls ?: 0) + 1, now)
        }
    }

    /**
     * After a wall: covers the walled app if it is still running beside the
     * wall in a pop-up or split screen ([popupCoverWanted]), and once it has
     * left the screen, kills its background process (2026-11-01). Recents
     * shows a thumbnail no ordinary app can blank, but a tap on a killed app
     * cold-starts it, which gives the wall the second it needs to land first.
     *
     * True when it just raised a wall.
     */
    private fun followWalledApp(current: String): Boolean {
        val pkg = coverFor ?: run {
            popupCover.hide()
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val age = now - coverWallAt
        var resumed = tracker.isResumed(pkg)
        val graceOver = coverGraceUntil != 0L && now >= coverGraceUntil
        // A pop-up left open past its grace is walled again, once its whole history
        // says it really is open: a lost PAUSED must not hold a cover asking to close
        // a window that is gone.
        if (graceOver && resumed && mayLandOver(current, pkg) && !historyHeldOff(pkg, now)) {
            val check = confirmedOpen(pkg, current)
            // Still reading: nothing is decided yet, and the cover, the kill and the
            // expiry below carry on as usual meanwhile.
            if (check != OpenCheck.UNKNOWN) resumed = check == OpenCheck.OPEN
            val verdict = if (check == OpenCheck.OPEN) standingVerdict(pkg) else GateVerdict.ALLOW
            if (verdict != GateVerdict.ALLOW) {
                Log.i(TAG, "$pkg still open after its grace -> $verdict")
                lastBlockedPackage = null // not a re-entry to debounce
                // A wall that would not rise leaves the grace pending, and the history
                // must not be read again on every tick because of it.
                val raised = wall(pkg, again = verdict == GateVerdict.ADMIN_LOCK_WALL) {
                    wallIntent(pkg, verdict, blockedLimit(pkg))
                }
                if (!raised) historyBackoff[pkg] = now + BESIDE_CONFIRM_MILLIS
                return raised
            }
            if (check == OpenCheck.OPEN) {
                // Open, but no longer walled (a session ended, the window opened): a
                // cover asking to close it would eat touches for nothing.
                coverGraceUntil = 0L
                coverFor = null
                killPending = false
                popupCover.hide()
                return false
            }
            if (check != OpenCheck.UNKNOWN) coverGraceUntil = 0L
        } else if (graceOver && !resumed) {
            // Closed. While still open the grace stays pending: a call or an alarm in
            // front only puts the wall off until one can land.
            coverGraceUntil = 0L
        }
        val alarmUp = alarmHoldsScreen()
        val want = popupCoverWanted(
            wallAgeMillis = age,
            // Touch-eating over an alarm's camera or sums, it would keep the user
            // from turning it off.
            ownWallInFront = current == packageName && !alarmUp,
            walledStillResumed = resumed,
            inGrace = now < coverGraceUntil,
            alarmOnScreen = alarmUp
        )
        // Logged on change, so one `adb logcat -s AppBlockService` run shows
        // whether a pop-up really stays resumed beside its wall on this device.
        if (want != popupCover.isShowing) {
            Log.i(TAG, "popup cover ${if (want) "UP" else "down"} for $pkg (resumed=$resumed, age=${age}ms)")
        }
        if (want) showCover(pkg, now) else popupCover.hide()
        if (killPending && !resumed && age >= COVER_SETTLE_MILLIS) {
            killPending = false
            runCatching { getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }
                .onFailure { Log.w(TAG, "could not stop $pkg in the background", it) }
        }
        if (age > COVER_MAX_MILLIS && !killPending) coverFor = null
        return false
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
     * The service is always on now, so its notification must be as quiet as
     * Android allows: IMPORTANCE_MIN folds it to a single line at the bottom of
     * the shade with no status-bar icon. A channel's importance cannot be
     * lowered once created, hence a new id and the old LOW channel deleted.
     */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        // Chrome's own hours ended on 2026-11-11; its warnings channel goes with them.
        manager.deleteNotificationChannel("wakeproof_chrome_hours")
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Distraction wall", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Shown while Euler's Lab keeps distracting apps behind a wall" }
        )
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Distractions are behind a wall")
        .setContentText("Gated apps open Sat–Sun ${clock(WallRule.OPEN_WINDOW_START_MINUTES.default)}–" +
                "${clock(WallRule.OPEN_WINDOW_END_MINUTES.default)} only. Spent and off-focus apps stay shut.")
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

        /** This service's default-app reading; null while it is not running or the first readings are not in. */
        fun defaultApps(): DefaultApps? = instance?.takeIf { it.defaultsLoaded.isCompleted }?.lastDefaults

        /** The service is running but still taking its first readings; see [applyFirstDefaults]. */
        fun defaultsPending(): Boolean = instance?.defaultsLoaded?.isCompleted == false

        /**
         * Another app than WakeProof or a home screen is in front (as of the last
         * tick): a study app, a call being dialled, a permission dialog. True too
         * when it is not known (the service down, or nothing seen yet): unsure,
         * nothing is opened over what may be work.
         */
        fun otherAppInFront(): Boolean {
            val service = instance ?: return true
            val front = service.lastFront ?: return true
            return front != service.packageName && front !in service.homeApps
        }

        /** A session on another of the user's devices walls this one; false if not running. */
        fun otherSessionActive(): Boolean = instance?.otherDeviceSessionActive() ?: false
        private const val CHANNEL_ID = "wakeproof_app_block_quiet"
        private const val LEGACY_CHANNEL_ID = "wakeproof_app_block"
        private const val NOTIFICATION_ID = 43
        private const val ALERT_CHANNEL_ID = "wakeproof_walls_down"
        private const val ALERT_NOTIFICATION_ID = 44
        private const val WATCH_NOTIFICATION_RECHECK_MILLIS = 15_000L

        /** canDrawOverlays is a binder call, so it is checked every 5 s, not every tick. */
        private const val OVERLAY_CHECK_EVERY = 10L

        /** How often the quarantine, a lost strict ring and the night reminders are checked. */
        private const val HOUSEKEEPING_EVERY_MILLIS = 5 * 60_000L
        private const val MEDIA_CHECK_MILLIS = 2_000L
        private const val NIGHT_PREFS = "night_reminders"
        /** Often enough that a new exam or midnight takes hold within a few minutes. */
        private const val EXAM_WEEK_REFRESH_MILLIS = 5 * 60_000L
        private const val KEY_EXAMS_HELD = "exam_weeks_held"
        private const val KEY_EXAMS_SEEN = "exam_week_seen_at"
        private const val KEY_EXAM_CLOCK_DOUBT = "exam_clock_doubt"
        private val EXAM_DAY_FORMAT = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")
        private const val KEY_LIGHTS_OUT_DAY = "lights_out_day"
        private const val KEY_CHARGE_NAG_AT = "charge_nag_at"
        private const val KEY_CAMERA_NAG_AT = "camera_nag_at"
        private const val NIGHT_CHANNEL_ID = "wakeproof_night"
        private const val NIGHT_QUIET_CHANNEL_ID = "wakeproof_night_quiet"
        private const val LIGHTS_OUT_NOTIFICATION_ID = 71
        private const val CHARGE_NOTIFICATION_ID = 72
        private const val CAMERA_NOTIFICATION_ID = 73
        private const val NIGHT_CAMERAS_RETRY_MILLIS = 15_000L
        private const val READY_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULTS_RETRY_MILLIS = 2_000L

        /** How long a whole-history answer may wait for the tick; a tick is [POLL_MILLIS]. */
        private const val HISTORY_ANSWER_MILLIS = 2_000L
        private const val WALL_PREFS = "walls"
        private const val KEY_ARMED = "armed"

        /**
         * Wi-Fi sign-in pages. The clock wall says to connect to the internet, and
         * on hotel or campus Wi-Fi that happens here, so walling it, at night too,
         * would leave no way to the network time that lifts the wall. Only while
         * the clock is doubted: at any other night it is a browser like the rest.
         */
        private val CAPTIVE_PORTAL_PACKAGES = setOf(
            "com.google.android.captiveportallogin",
            "com.android.captiveportallogin"
        )
        private const val KEY_ALERT_AT = "alert_at"
        private const val ALERT_UPDATE_SILENT_MILLIS = 60_000L

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
        private const val ADMIN_REBLOCK_MILLIS = 600L

        /**
         * Starts enforcement when the grants are in place, and stops it otherwise.
         * Always on with them: the wait covers every social app installed, so
         * there is effectively always something to enforce. Safe to call on
         * every app start; the draw-over-other-apps grant is what exempts this
         * from the background foreground-service-start restriction.
         */
        private fun enforceable(context: Context): Boolean =
            UsageStatsRepository(context).hasAccess() && Settings.canDrawOverlays(context)

        fun sync(context: Context) {
            val enforceable = enforceable(context)
            val intent = Intent(context, AppBlockService::class.java)
            runCatching {
                if (enforceable) {
                    context.getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
                    // The next loss is news, however soon it comes.
                    wallPrefs(context).edit().remove(KEY_ALERT_AT).apply()
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                    // The walls were up once, so this is a lost grant, not a phone
                    // still being set up.
                    if (wallPrefs(context).getBoolean(KEY_ARMED, false)) postWallsDown(context)
                }
            }
        }

        private fun quietNow(): Boolean = nightQuiet(
            LocalTime.now().let { it.hour * 60 + it.minute },
            StrictAlarm.lastSlotMinute
        )

        private fun quietChannel() =
            NotificationChannel(NIGHT_QUIET_CHANNEL_ID, "Bedtime (quiet)", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Warnings held back from sounding while you sleep" }

        private fun wallPrefs(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(WALL_PREFS, Context.MODE_PRIVATE)

        /**
         * Not swiped away for good: [sync] brings it back, at most once per
         * [wallsDownAlertEvery], until the grant returns. [sync] runs on every
         * return to the home screen, so unthrottled it would ring each time.
         * Daily on Play: a stranger may have revoked it on purpose.
         */
        private fun postWallsDown(context: Context, treeLost: Boolean = false) {
            val prefs = wallPrefs(context)
            val now = System.currentTimeMillis()
            val every = wallsDownAlertEvery(SIDELOADED_BUILD)
            val lastAt = prefs.getLong(KEY_ALERT_AT, 0L)
            if (!treeLost && !nagDue(lastAt, now, every)) return
            val manager = context.getSystemService(NotificationManager::class.java)
            // Only on a clock that can be believed: a hand-set night must not silence it.
            val held = wallsDownRepeatHeld(treeLost, lastAt, quietNow() && runCatching { !clockUntrusted(context) }.getOrDefault(false))
            // Overnight a repeat only makes sure the alert is there, as it may have
            // been swiped away or lost to a reboot, and stays unstamped so that the
            // morning's sync rings it.
            // A lookup that throws must not cost the alert, or crash the tick that posts it.
            if (held && runCatching { manager.activeNotifications.any { it.id == ALERT_NOTIFICATION_ID } }.getOrDefault(false)) return
            // sync posts the alert, then stopping the service may add the lost tree
            // moments later: that is an update to the same news, not a second ring.
            val justRang = treeLost && now - lastAt in 0 until ALERT_UPDATE_SILENT_MILLIS
            if (!held) prefs.edit().putLong(KEY_ALERT_AT, now).apply()
            val text = "Euler's Lab lost usage access or draw-over-apps, so nothing is walled. " +
                "Give it back to raise the walls." +
                if (treeLost) " This focus block's tree withered." else ""
            val channel = if (held) {
                quietChannel()
            } else {
                NotificationChannel(
                    ALERT_CHANNEL_ID, "Walls switched off", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "When Euler's Lab loses the access its walls need" }
            }
            manager.createNotificationChannel(channel)
            // Cancelled first so the alert rings again; [justRang] updates it in place.
            if (!justRang) manager.cancel(ALERT_NOTIFICATION_ID)
            manager.notify(
                ALERT_NOTIFICATION_ID,
                Notification.Builder(context, channel.id)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Your walls are down")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, 1, Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .setOngoing(true)
                    .setOnlyAlertOnce(justRang)
                    .build()
            )
        }
    }
}
