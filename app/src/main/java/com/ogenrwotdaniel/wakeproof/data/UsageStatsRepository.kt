package com.ogenrwotdaniel.wakeproof.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One app's foreground time today. */
data class AppUsage(val packageName: String, val label: String, val minutes: Long)

/** One day's total screen time across launchable apps. */
data class DayUsage(val epochDay: Long, val minutes: Long)

/** Everything the phone-usage screen shows. */
data class UsageSnapshot(
    val todayMinutes: Long = 0,
    val topApps: List<AppUsage> = emptyList(),
    val week: List<DayUsage> = emptyList()
)

/**
 * Screen-time stats from [UsageStatsManager]. Requires the special "usage
 * access" grant (Settings > Apps > Special access); callers must check
 * [hasAccess] and deep-link the user there when false.
 *
 * Times are per-day aggregates of foreground time for launcher-visible apps —
 * close to, but not exactly, the system's own Digital Wellbeing numbers.
 */
@Singleton
class UsageStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasAccess(): Boolean {
        val mode = context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun snapshot(): UsageSnapshot = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext UsageSnapshot()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val labels = launchableAppLabels()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()

        val week = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
            val total = usm.queryAndAggregateUsageStats(start, end)
                .filterKeys { it in labels }
                .values.sumOf { it.totalTimeInForeground }
            DayUsage(epochDay = day.toEpochDay(), minutes = total / 60_000)
        }

        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val topApps = usm.queryAndAggregateUsageStats(startOfToday, now)
            .filterKeys { it in labels }
            .map { (pkg, stats) ->
                AppUsage(pkg, labels.getValue(pkg), stats.totalTimeInForeground / 60_000)
            }
            .filter { it.minutes >= 1 }
            .sortedByDescending { it.minutes }
            .take(TOP_APP_COUNT)

        UsageSnapshot(
            todayMinutes = week.last().minutes,
            topApps = topApps,
            week = week
        )
    }

    /** Today's foreground minutes for specific [packages] (limit enforcement). */
    suspend fun todayMinutesFor(packages: Set<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            if (!hasAccess() || packages.isEmpty()) return@withContext emptyMap()
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
                .filterKeys { it in packages }
                .mapValues { (_, stats) -> stats.totalTimeInForeground / 60_000 }
        }

    /**
     * Foreground milliseconds so far today for [packages], summed from raw
     * resume/pause events rather than aggregated stats.
     *
     * Enforcement needs this: `totalTimeInForeground` is only guaranteed to
     * settle once an app leaves the foreground, so a cap checked that way can sit
     * crossed for minutes while the app is still on screen — exactly the moment
     * the kill switch is meant to act. Walking events counts the session in
     * progress by closing it off at `now`.
     */
    suspend fun todayForegroundMillisFor(packages: Set<String>): Map<String, Long> =
        withContext(Dispatchers.IO) {
            if (!hasAccess() || packages.isEmpty()) return@withContext emptyMap()
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()

            val totals = mutableMapOf<String, Long>()
            val resumedAt = mutableMapOf<String, Long>()
            val events = usm.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName
                if (pkg !in packages) continue
                when (event.eventType) {
                    // Keep the earliest open resume: back-to-back resumes with no
                    // pause between them are activity swaps inside one session.
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        resumedAt.putIfAbsent(pkg, event.timeStamp)

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED ->
                        resumedAt.remove(pkg)?.let { from ->
                            totals[pkg] = (totals[pkg] ?: 0L) + (event.timeStamp - from)
                        }
                }
            }
            // Anything still open is on screen right now, so it runs until now.
            resumedAt.forEach { (pkg, from) ->
                totals[pkg] = (totals[pkg] ?: 0L) + (now - from)
            }
            totals
        }

    /**
     * The package on screen, or null when no resume event landed in the recent
     * window — which usually means the user has been sitting in the same app.
     * Callers are expected to hold on to the last non-null answer.
     */
    suspend fun foregroundPackage(): String? = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext null
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - FOREGROUND_LOOKBACK_MILLIS, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        latest
    }

    /** Human label for [packageName], falling back to the raw package. */
    fun appLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** Package → label for apps a launcher shows (needs the manifest <queries> block). */
    private fun launchableAppLabels(): Map<String, String> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0).associate {
            it.activityInfo.packageName to it.loadLabel(pm).toString()
        }
    }

    private companion object {
        const val TOP_APP_COUNT = 10

        /** How far back [foregroundPackage] looks for a resume event. */
        const val FOREGROUND_LOOKBACK_MILLIS = 60_000L
    }
}
