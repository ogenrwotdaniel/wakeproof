package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts the snoozes taken per alarm per day, so the allowance survives what the
 * user is most likely to do with it: snooze, put the phone down, and let the
 * ringing activity be destroyed.
 *
 * Device-protected storage, like [DirectBootAlarmStore] — an alarm can ring
 * before the first unlock of the day, when Room is unreadable, and a snooze
 * offered there still has to be counted.
 */
@Singleton
class SnoozeStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("alarm_snoozes", Context.MODE_PRIVATE)

    /** Snoozes still available for [alarmId] today. */
    fun remaining(alarmId: Long, allowance: Int = MAX_PER_DAY): Int =
        (allowance - takenToday(alarmId)).coerceAtLeast(0)

    /** Records one snooze against today's allowance and returns what is left. */
    fun record(alarmId: Long, allowance: Int = MAX_PER_DAY): Int {
        val taken = takenToday(alarmId) + 1
        prefs.edit { putString(alarmId.toString(), encodeSnoozeCount(today(), taken)) }
        return (allowance - taken).coerceAtLeast(0)
    }

    private fun takenToday(alarmId: Long): Int =
        snoozesTakenOn(prefs.getString(alarmId.toString(), null), today())

    private fun today(): Long = LocalDate.now().toEpochDay()

    companion object {
        /** Enough to get out of bed on your own terms, few enough to still matter. */
        const val MAX_PER_DAY = 3
        const val SNOOZE_MINUTES = 5
    }
}

/** How [SnoozeStore] writes a count: the day it belongs to, then the count. */
internal fun encodeSnoozeCount(day: Long, taken: Int): String = "$day:$taken"

/**
 * Reads a stored count back, keeping only what belongs to [today].
 *
 * A count from an earlier day is stale rather than spent, so the allowance
 * renews each morning without anything having to run at midnight to reset it —
 * which is the whole reason the day is stored alongside the count.
 *
 * Anything unreadable counts as zero snoozes taken. The failure that matters
 * here is the one that strands someone in front of a ringing alarm, so a
 * corrupt entry hands back the allowance rather than withholding it.
 */
internal fun snoozesTakenOn(stored: String?, today: Long): Int {
    val parts = stored?.split(":") ?: return 0
    if (parts.getOrNull(0)?.toLongOrNull() != today) return 0
    return parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}
