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
    const val KEY_WATER_COUNT = "water_count"
    const val KEY_WATER_TARGET = "water_target"
    const val KEY_WATER_ML = "water_ml"
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
    val waterCount: Int = 0,
    val waterTarget: Int = 3,
    val waterMl: Int = 0,
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
                waterCount = map.getInt(WearContract.KEY_WATER_COUNT, 0),
                waterTarget = map.getInt(WearContract.KEY_WATER_TARGET, 3),
                waterMl = map.getInt(WearContract.KEY_WATER_ML, 0),
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
