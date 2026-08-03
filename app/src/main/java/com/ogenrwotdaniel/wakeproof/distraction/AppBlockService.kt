package com.ogenrwotdaniel.wakeproof.distraction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.Service
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * run on every tick.
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
        scope.launch { watch() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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

            val now = SystemClock.elapsedRealtime()
            if (baselineFor != current || now - baselineTakenAt > BASELINE_REFRESH_MILLIS) {
                baselineMillis = usageRepository.todayForegroundMillisFor(setOf(current))[current] ?: 0L
                baselineTakenAt = SystemClock.elapsedRealtime()
                baselineFor = current
            }
            val usedMillis = baselineMillis + (SystemClock.elapsedRealtime() - baselineTakenAt)

            if (usedMillis >= limit.minutes * 60_000L) block(current, usedMillis, limit.minutes)
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
     * Apps a hard block must never touch. With no override, walling off the
     * launcher, the dialer or Settings would leave the phone genuinely unusable —
     * including the path to turning this feature back off. A cap set on any of
     * them still nudges; it just never blocks.
     */
    private fun undismissablePackages(): Set<String> = buildSet {
        add(packageName)
        add("com.android.systemui")
        add("com.android.settings")
        runCatching {
            getSystemService(TelecomManager::class.java).defaultDialerPackage
        }.getOrNull()?.let(::add)
        runCatching {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()?.let(::add)
    }

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
        private const val POLL_MILLIS = 1_500L

        /** How long an extrapolated usage figure is trusted before re-measuring. */
        private const val BASELINE_REFRESH_MILLIS = 30_000L

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
