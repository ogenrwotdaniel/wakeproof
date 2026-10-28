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

/** An installed app the user can put a limit on. */
data class InstalledApp(val packageName: String, val label: String)

/** One activity going to or leaving the foreground. */
internal data class ForegroundEvent(
    val packageName: String,
    val className: String,
    val resumed: Boolean,
    val timestamp: Long
)

/**
 * Sums per-app foreground time from activity events, closing any session still
 * open at [now].
 *
 * The subtlety that makes this worth isolating: these events are per *activity*,
 * not per app. Apps routinely trampoline — WhatsApp Business resumes
 * `com.whatsapp.Main`, hands straight off to `HomeActivity`, then emits
 * `Main`'s PAUSED and STOPPED afterwards. Tracking one open timestamp per
 * package lets that trailing PAUSED close the session the *other* activity just
 * opened, collapsing hours of use to nearly zero, so a cap never trips. Apps
 * that live in a single activity (Instagram) measure fine, which is what makes
 * the bug look app-specific rather than structural.
 *
 * So the state per package is the set of activities currently resumed; the app
 * counts as foreground from the moment that set becomes non-empty until it
 * empties again. PAUSED and STOPPED both arrive for the same activity, and the
 * second one is a no-op because the class is already gone from the set.
 */
internal fun foldForegroundMillis(
    events: List<ForegroundEvent>,
    now: Long
): Map<String, Long> {
    val totals = mutableMapOf<String, Long>()
    val openActivities = mutableMapOf<String, MutableSet<String>>()
    val foregroundSince = mutableMapOf<String, Long>()

    for (event in events.sortedBy { it.timestamp }) {
        val open = openActivities.getOrPut(event.packageName) { mutableSetOf() }
        if (event.resumed) {
            if (open.isEmpty()) foregroundSince[event.packageName] = event.timestamp
            open += event.className
        } else if (open.remove(event.className) && open.isEmpty()) {
            val from = foregroundSince.remove(event.packageName) ?: event.timestamp
            totals[event.packageName] =
                (totals[event.packageName] ?: 0L) + (event.timestamp - from)
        }
    }
    // Anything still open is on screen right now, so it runs until now.
    foregroundSince.forEach { (pkg, from) ->
        totals[pkg] = (totals[pkg] ?: 0L) + (now - from)
    }
    return totals
}

/**
 * Splits [events] into the spans marked out by [edges] (ascending; N edges give
 * N-1 spans), returning per-package foreground millis for each span.
 *
 * Done by folding cumulatively up to each edge and subtracting neighbours rather
 * than folding each span in isolation. That is what makes a session running
 * across a boundary — the phone still in your hand at midnight — count towards
 * both days in the right proportion. Folding spans separately would lose it
 * entirely from the later day, since that span contains no resume event.
 *
 * [now] clamps every edge: future edges contribute nothing.
 */
internal fun foregroundMillisPerSpan(
    events: List<ForegroundEvent>,
    edges: List<Long>,
    now: Long
): List<Map<String, Long>> {
    val cumulative = edges.map { edge ->
        val upTo = minOf(edge, now)
        foldForegroundMillis(events.filter { it.timestamp < upTo }, upTo)
    }
    return (0 until edges.size - 1).map { index ->
        val before = cumulative[index]
        cumulative[index + 1].mapValues { (pkg, total) ->
            (total - (before[pkg] ?: 0L)).coerceAtLeast(0L)
        }.filterValues { it > 0L }
    }
}

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

    /**
     * Everything the usage screen shows, measured the same way enforcement
     * measures it — by walking raw resume/pause events.
     *
     * It used to use [UsageStatsManager.queryAndAggregateUsageStats], which reads
     * whole aggregation buckets and is NOT clipped to the range you ask for: a
     * query starting at midnight still returned time spent before midnight. On a
     * real device (2026-08-10) that showed Instagram at 1 h 17 m for "today" when
     * it had actually been open for 5 minutes — the previous day's 2 h 47 m
     * bleeding through. The number sat next to a "5 of 10 min" figure from the
     * event walk on the same screen, and next to a 10-minute cap that correctly
     * refused to fire, which reads exactly like a broken kill switch.
     *
     * Day totals come from cumulative folds at each midnight boundary and
     * subtracting neighbours, so a session running across midnight is split
     * between the two days instead of being lost or double-counted.
     */
    suspend fun snapshot(): UsageSnapshot = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext UsageSnapshot()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val labels = launchableAppLabels()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()

        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val weekStart = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val events = collectForegroundEvents(usm, weekStart, now, packages = labels.keys)

        val edges = days.map { it.atStartOfDay(zone).toInstant().toEpochMilli() } + now
        val spans = foregroundMillisPerSpan(events, edges, now)

        val week = days.mapIndexed { index, day ->
            DayUsage(
                epochDay = day.toEpochDay(),
                minutes = spans[index].values.sum() / 60_000
            )
        }

        val topApps = spans[days.lastIndex]
            .map { (pkg, millis) -> AppUsage(pkg, labels.getValue(pkg), millis / 60_000) }
            .filter { it.minutes >= 1 }
            .sortedByDescending { it.minutes }
            .take(TOP_APP_COUNT)

        UsageSnapshot(
            todayMinutes = week.last().minutes,
            topApps = topApps,
            week = week
        )
    }

    /**
     * Minutes per day over the last 7 days (oldest first) for [packages]
     * together, measured like [snapshot]. Feeds the week card: time in the
     * waiting apps, day by day.
     */
    suspend fun weekMinutesFor(packages: Set<String>): List<DayUsage> = withContext(Dispatchers.IO) {
        if (!hasAccess() || packages.isEmpty()) return@withContext emptyList()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val days = (6 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
        val edges = days.map { it.atStartOfDay(zone).toInstant().toEpochMilli() } + now
        val events = collectForegroundEvents(usm, edges.first(), now, packages)
        val spans = foregroundMillisPerSpan(events, edges, now)
        days.mapIndexed { index, day -> DayUsage(day.toEpochDay(), spans[index].values.sum() / 60_000) }
    }

    /**
     * Today's foreground minutes for specific [packages] — the nudge path.
     * Shares [todayForegroundMillisFor] with the block path so a nudge and a
     * block never disagree about how long an app has been open.
     */
    suspend fun todayMinutesFor(packages: Set<String>): Map<String, Long> =
        todayForegroundMillisFor(packages).mapValues { (_, millis) -> millis / 60_000 }

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

            foldForegroundMillis(collectForegroundEvents(usm, start, now, packages), now)
        }

    /**
     * Raw activity resume/pause events in `[start, end)`, restricted to
     * [packages] when given (null means every package). Kept in one place so the
     * usage screen and limit enforcement can never drift apart in what they count.
     */
    private fun collectForegroundEvents(
        usm: UsageStatsManager,
        start: Long,
        end: Long,
        packages: Set<String>?
    ): List<ForegroundEvent> {
        val collected = mutableListOf<ForegroundEvent>()
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (packages != null && event.packageName !in packages) continue
            val resumed = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> false
                else -> continue
            }
            collected += ForegroundEvent(
                packageName = event.packageName,
                className = event.className ?: "",
                resumed = resumed,
                timestamp = event.timeStamp
            )
        }
        return collected
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

    /**
     * Every launcher-visible app, alphabetically — the pool a limit can be set on.
     * The top-apps list only covers what was actually used today, which is far too
     * narrow to cap something *before* it becomes a problem.
     */
    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        launchableAppLabels()
            .filterKeys { it != context.packageName }
            .map { (pkg, label) -> InstalledApp(pkg, label) }
            .sortedBy { it.label.lowercase() }
    }

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
