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
    const val KEY_SUMMARY_UNTIL = "summary_until"
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
    /** Until when the phone's last session owes its summary; 0 = nothing owed. */
    val summaryUntilMillis: Long = 0L,
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

/** The longest the phone holds a summary; matches `SUMMARY_HOLD_MILLIS` in `:app`. */
const val SUMMARY_HOLD_MAX_MILLIS = 30 * 60_000L

/** Said when a start is refused because the phone's summary is owed. */
const val SUMMARY_OWED_TEXT = "First photograph your session summary on the phone."

/**
 * Pure: the phone's last session still owes its summary at [now], so no
 * session starts on the watch (a new one would be the way to never write it).
 * An end further off than the hold can last is a wrong clock on one side and
 * holds nothing, so the watch can never be locked out for long.
 */
fun summaryHolds(untilMillis: Long, now: Long): Boolean =
    untilMillis - now in 1..SUMMARY_HOLD_MAX_MILLIS

/**
 * Pure: bed hours (20:00 to 03:00; matches `bedHoursShutWork` in `:app`) take
 * no work, so no session starts or is followed on the watch either.
 */
fun bedHours(minuteOfDay: Int): Boolean = minuteOfDay >= 20 * 60 || minuteOfDay < 3 * 60

/** Said when a start is refused in bed hours. */
const val BED_HOURS_TEXT = "No focus between 20:00 and 03:00. Go to bed."

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
                summaryUntilMillis = map.getLong(WearContract.KEY_SUMMARY_UNTIL, 0L),
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
