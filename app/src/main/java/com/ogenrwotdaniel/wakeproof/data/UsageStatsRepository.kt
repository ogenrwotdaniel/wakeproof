package com.ogenrwotdaniel.wakeproof.data

import android.app.AppOpsManager
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
    }
}
