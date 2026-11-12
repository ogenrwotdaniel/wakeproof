package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Data-layer contract shared with the phone app. Keys MUST match
 * `com.ogenrwotdaniel.wakeproof.wear.WearContract` in the `:app` module (kept in
 * sync by hand — there is no shared module).
 */
private object WearContract {
    const val PATH = "/wakeproof/stats"
    const val KEY_STREAK = "streak"
    const val KEY_ASTERISK = "asterisk"
    const val KEY_FOCUS = "focus_minutes"
    const val KEY_FOCUS_GOAL = "focus_goal"
    const val KEY_PLAN_COUNT = "plan_count"
    const val KEY_PLAN_TARGET = "plan_target"
    const val KEY_SHUTDOWN_COUNT = "shutdown_count"
    const val KEY_SHUTDOWN_TARGET = "shutdown_target"
    const val KEY_UPDATED = "updated_at"
    const val KEY_FOCUS_LEN = "focus_len"
    const val KEY_SHORT_BREAK_LEN = "short_break_len"
    const val KEY_LONG_BREAK_LEN = "long_break_len"
    const val KEY_SUBJECT = "active_subject"
}

/** Today's snapshot as mirrored from the phone; [present] is false before any push. */
data class WearStats(
    val streak: Int = 0,
    val asterisk: Boolean = false,
    val focusMinutes: Int = 0,
    val focusGoal: Int = 0,
    /** Day-plan photos saved today, and how many the schedule asks for (0 = none). */
    val planCount: Int = 0,
    val planTarget: Int = 0,
    /** Evening reviews written today, and how many the schedule asks for (0 = none). */
    val shutdownCount: Int = 0,
    val shutdownTarget: Int = 0,
    val present: Boolean = false,
    /** When the phone computed these numbers; 0 for a pre-1.6 phone that never said. */
    val updatedAtMillis: Long = 0L,
    /** The phone's phase lengths, or the phone's defaults when it never pushed any. */
    val durations: WatchDurations = WatchDurations(),
    val subject: String? = null
) {
    /**
     * Focus minutes for TODAY, which the snapshot only knows if it was taken
     * today. A watch that has been away from the phone since yesterday would
     * otherwise show yesterday's total as today's — exactly when it is being
     * used on its own and the number matters.
     */
    fun focusMinutesOn(epochDay: Long, zone: ZoneId): Int =
        if (updatedAtMillis == 0L || WatchTimer.epochDayOf(updatedAtMillis, zone) == epochDay) focusMinutes
        else 0
}

/**
 * Pure: the one line about today's fixed alarms, or null on a day with none.
 * One line, not two: the tile has no room for both, and the day plan matters
 * until it is photographed, the evening review after that.
 */
fun dayLine(stats: WearStats): String? {
    val planAsked = stats.planTarget > 0
    val planDone = planAsked && stats.planCount >= stats.planTarget
    val reviewAsked = stats.shutdownTarget > 0
    val reviewDone = reviewAsked && stats.shutdownCount >= stats.shutdownTarget
    return when {
        planAsked && !planDone -> "Day plan pending"
        reviewAsked && !reviewDone -> if (planAsked) "Plan ✓ · review due" else "Evening review due"
        planAsked && reviewAsked -> "Plan ✓ · review ✓"
        planAsked -> "Day plan ✓"
        reviewAsked -> "Evening review ✓"
        else -> null
    }
}

/** Pure: whether [dayLine] has nothing left to ask today. */
fun dayLineDone(stats: WearStats): Boolean =
    (stats.planTarget == 0 || stats.planCount >= stats.planTarget) &&
        (stats.shutdownTarget == 0 || stats.shutdownCount >= stats.shutdownTarget)

/** Reads the latest [WearStats] the phone pushed onto the Data Layer. */
object WearStatsReader {
    suspend fun read(context: Context): WearStats = withContext(Dispatchers.IO) {
        val buffer = runCatching {
            Wearable.getDataClient(context).dataItems.await()
        }.getOrNull() ?: return@withContext WearStats()
        try {
            val item = buffer.firstOrNull { it.uri.path == WearContract.PATH }
                ?: return@withContext WearStats()
            val map = DataMapItem.fromDataItem(item).dataMap
            val defaults = WatchDurations()
            WearStats(
                streak = map.getInt(WearContract.KEY_STREAK, 0),
                asterisk = map.getBoolean(WearContract.KEY_ASTERISK, false),
                focusMinutes = map.getInt(WearContract.KEY_FOCUS, 0),
                focusGoal = map.getInt(WearContract.KEY_FOCUS_GOAL, 0),
                planCount = map.getInt(WearContract.KEY_PLAN_COUNT, 0),
                planTarget = map.getInt(WearContract.KEY_PLAN_TARGET, 0),
                shutdownCount = map.getInt(WearContract.KEY_SHUTDOWN_COUNT, 0),
                shutdownTarget = map.getInt(WearContract.KEY_SHUTDOWN_TARGET, 0),
                present = true,
                updatedAtMillis = map.getLong(WearContract.KEY_UPDATED, 0L),
                durations = WatchDurations(
                    focusMinutes = map.getInt(WearContract.KEY_FOCUS_LEN, defaults.focusMinutes),
                    shortBreakMinutes = map.getInt(WearContract.KEY_SHORT_BREAK_LEN, defaults.shortBreakMinutes),
                    longBreakMinutes = map.getInt(WearContract.KEY_LONG_BREAK_LEN, defaults.longBreakMinutes)
                ),
                subject = map.getString(WearContract.KEY_SUBJECT)
            )
        } finally {
            buffer.release()
        }
    }
}

/**
 * Today's focus as the watch can best know it: the phone's figure (if it is from
 * today) plus blocks finished here that the phone has not acknowledged yet.
 *
 * Can briefly double-count one block: the phone's delete of an imported block
 * and its fresh stats push are separate DataItems, and the Data Layer does not
 * promise their order. It settles on the next push; under-counting a block the
 * user just sat through would be the worse error.
 */
data class WatchFocusToday(val minutes: Int, val unsyncedBlocks: Int)

fun focusToday(stats: WearStats, pending: List<FocusBlock>, epochDay: Long, zone: ZoneId): WatchFocusToday {
    val todays = pending.filter { it.epochDay == epochDay }
    return WatchFocusToday(
        minutes = stats.focusMinutesOn(epochDay, zone) + todays.sumOf { it.minutes },
        unsyncedBlocks = pending.size
    )
}
