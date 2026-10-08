package com.ogenrwotdaniel.wakeproof.distraction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.R
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Enforces [LimitMode.BLOCK] caps: while a walled-off app is on screen and its
 * daily allowance is spent, [BlockedActivity] is thrown in front of it. There is
 * no override — the cap lifts when the calendar day does, which falls out for
 * free because usage is measured from midnight.
 *
 * Watching happens by polling [UsageStatsRepository.foregroundPackage] rather
 * than through an AccessibilityService. Accessibility would be the more precise
 * hook, but Play restricts it to genuine accessibility use and has rejected
 * blockers built on it; polling needs only the two special grants this app
 * already asks for (usage access, and draw-over-other-apps — which is also what
 * lets a service launch an Activity from the background at all).
 *
 * Cost is kept low by measuring usage only on entering a capped app and then
 * extrapolating locally between refreshes, so the expensive event walk does not
 * run on every tick. Once an app's allowance is spent it is remembered as spent
 * until midnight, so walking back into it is walled on the very next tick
 * instead of after another measurement.
 */
@AndroidEntryPoint
class AppBlockService : Service() {

    @Inject lateinit var limitRepository: DistractionLimitRepository
    @Inject lateinit var usageRepository: UsageStatsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Latest blocked caps, kept current by a collector. Read on every tick, so it
     * must not be a DataStore round trip.
     */
    @Volatile private var blockedLimits: Map<String, AppLimit> = emptyMap()

    /** PackageManager lookups, resolved once — they cannot change under us. */
    private val untouchable: Set<String> by lazy { undismissablePackages() }

    /** Last package we saw resume; polling returns null while the user sits still. */
    private var foregroundPackage: String? = null

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

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        // Give the limits collector a tick to land before deciding there is
        // nothing to guard.
        delay(POLL_MILLIS)
        while (true) {
            val limits = blockedLimits
            // Nothing left to guard, or the grant went away: stop burning cycles.
            if (limits.isEmpty() || !usageRepository.hasAccess()) {
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

            usageRepository.foregroundPackage()?.let { foregroundPackage = it }
            val current = foregroundPackage
            val limit = limits[current]
            if (current == null || limit == null) {
                baselineFor = null
                // Genuinely somewhere else now (not just looking at our own wall),
                // so a re-entry should be blocked at once rather than debounced.
                if (current != null && current != packageName) lastBlockedPackage = null
                delay(POLL_MILLIS)
                continue
            }

            // Spent earlier today against a cap at least this strict: no need to
            // measure again — usage cannot have gone down.
            val spentAt = spentToday[current]
            if (spentAt != null && limit.minutes <= spentAt) {
                block(current, limit.minutes * 60_000L, limit.minutes)
                delay(POLL_MILLIS)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (baselineFor != current || now - baselineTakenAt > BASELINE_REFRESH_MILLIS) {
                baselineMillis = usageRepository.todayForegroundMillisFor(setOf(current))[current] ?: 0L
                baselineTakenAt = SystemClock.elapsedRealtime()
                baselineFor = current
            }
            val usedMillis = baselineMillis + (SystemClock.elapsedRealtime() - baselineTakenAt)

            if (usedMillis >= limit.minutes * 60_000L) {
                spentToday[current] = limit.minutes
                block(current, usedMillis, limit.minutes)
            }
            // Always the fast cadence, even with allowance left. Sleeping until
            // the cap was due (up to 30 s) also stopped the loop noticing a
            // switch to ANOTHER capped app whose allowance was already gone.
            // The foreground check is a one-minute event query; only the
            // midnight-onwards measurement is expensive, and that is still
            // rate-limited by BASELINE_REFRESH_MILLIS.
            delay(POLL_MILLIS)
        }
    }

    private fun block(packageName: String, usedMillis: Long, capMinutes: Int) {
        val now = SystemClock.elapsedRealtime()
        val repeat = packageName == lastBlockedPackage && now - lastBlockedAt < REBLOCK_MILLIS
        if (repeat) return
        lastBlockedPackage = packageName
        lastBlockedAt = now

        startActivity(
            BlockedActivity.intent(
                context = this,
                label = usageRepository.appLabel(packageName),
                usedMinutes = (usedMillis / 60_000L).toInt(),
                capMinutes = capMinutes
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Apps a hard block must never touch — see [essentialPackages]. A cap set on
     * any of them still nudges; it just never blocks.
     */
    private fun undismissablePackages(): Set<String> = essentialPackages(this)

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "App limit enforcement", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while blocked apps are being kept shut" }
        )
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("App limits are being enforced")
        .setContentText("Blocked apps stay shut until midnight.")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "wakeproof_app_block"
        private const val NOTIFICATION_ID = 43

        /** Foreground checks per tick. Fast enough to catch an app opening. */
        private const val POLL_MILLIS = 1_000L

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
         * Starts enforcement when there is something to enforce and the grants are
         * in place, and stops it otherwise. Safe to call on every app start; the
         * draw-over-other-apps grant is what exempts this from the background
         * foreground-service-start restriction.
         */
        suspend fun sync(context: Context) {
            val enforceable = DistractionLimitRepository(context).blockedPackages.first().isNotEmpty() &&
                UsageStatsRepository(context).hasAccess() &&
                Settings.canDrawOverlays(context)
            val intent = Intent(context, AppBlockService::class.java)
            runCatching {
                if (enforceable) context.startForegroundService(intent)
                else context.stopService(intent)
            }
        }
    }
}
