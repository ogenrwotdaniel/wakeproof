package com.ogenrwotdaniel.wakeproof.distraction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
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
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusSound
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

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
@AndroidEntryPoint
class AppBlockService : Service() {

    @Inject lateinit var limitRepository: DistractionLimitRepository
    @Inject lateinit var usageRepository: UsageStatsRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Latest blocked caps, kept current by a collector. Read on every tick, so it
     * must not be a DataStore round trip.
     */
    @Volatile private var blockedLimits: Map<String, AppLimit> = emptyMap()

    /** The focus list (study apps, plus Spotify when it is the study sound). */
    @Volatile private var focusList: Set<String> = emptySet()

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

    override fun onCreate() {
        super.onCreate()
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
            limitRepository.limits.collect { all ->
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
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        // Give the collectors a tick to land before the first decision.
        delay(POLL_MILLIS)
        while (true) {
            // The grant went away: nothing can be seen, so stop burning cycles.
            if (!usageRepository.hasAccess()) {
                stopSelf()
                return
            }

            // Nothing can be opened while the screen is off, so there is nothing
            // to catch. This is the single largest saving in the loop: a night's
            // sleep was ~19,000 foreground queries that could only ever answer
            // "no one is using anything".
            if (!powerManager.isInteractive) {
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
            }

            // Still reading events while the lock screen is up keeps the fold
            // current, but nothing behind the lock screen can be used.
            val current = runCatching { tracker.current() }
                .onFailure { Log.w(TAG, "foreground query failed", it) }
                .getOrNull()
            if (current == null || keyguardManager.isKeyguardLocked) {
                delay(POLL_MILLIS)
                continue
            }
            if (current == packageName) {
                // Looking at our own wall (or WakeProof itself). Not a re-entry.
                delay(POLL_MILLIS)
                continue
            }
            // Genuinely somewhere else now (not just looking at our own wall), so
            // a re-entry should be walled at once rather than debounced.
            if (current != lastBlockedPackage) lastBlockedPackage = null

            val now = System.currentTimeMillis()
            val gated = gatedCache.getOrPut(current) { AppGate.isGated(this, current, untouchable) }
            if (gated) GatePasses.seen(current, now)
            val limit = blockedLimits[current]
            val verdict = gateVerdict(
                essential = current in untouchable,
                focusSessionActive = PomodoroTimerService.state.value.active,
                onFocusList = current in focusList,
                capSpent = limit != null && capSpent(current, limit),
                gated = gated,
                hasPass = GatePasses.isValid(current, now)
            )
            logVerdict(current, verdict)
            when (verdict) {
                GateVerdict.ALLOW -> Unit
                GateVerdict.FOCUS_WALL -> wall(current) {
                    BlockedActivity.focusIntent(this, usageRepository.appLabel(current))
                }
                GateVerdict.CAP_WALL -> wall(current) {
                    BlockedActivity.intent(
                        context = this,
                        label = usageRepository.appLabel(current),
                        usedMinutes = limit!!.minutes,
                        capMinutes = limit.minutes
                    )
                }
                GateVerdict.WAIT -> wall(current) {
                    GateActivity.intent(this, current, usageRepository.appLabel(current))
                }
            }
            delay(POLL_MILLIS)
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
     * Throws [intent] in front of [packageName], once: the wall pauses the app,
     * so the next tick sees WakeProof in front and leaves it alone. The debounce
     * only covers the tick or two before the wall's own resume lands.
     */
    private fun wall(packageName: String, intent: () -> Intent) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == lastBlockedPackage && now - lastBlockedAt < REBLOCK_MILLIS) return
        lastBlockedPackage = packageName
        lastBlockedAt = now
        runCatching { startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "could not raise the wall over $packageName", it) }
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
        private const val CHANNEL_ID = "wakeproof_app_block_quiet"
        private const val LEGACY_CHANNEL_ID = "wakeproof_app_block"
        private const val NOTIFICATION_ID = 43

        /**
         * Foreground checks per tick. Halved from 1 s once the wait had to beat
         * the Edge panel: this is the longest an app can show before its wall
         * lands. Cheap, because [ForegroundTracker] reads only new events.
         */
        private const val POLL_MILLIS = 500L

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
