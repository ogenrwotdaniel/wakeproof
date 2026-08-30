package com.ogenrwotdaniel.wakeproof.alarm

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Which days of the week an alarm repeats on, as a 7-bit mask.
 *
 * Monday is bit 0 through Sunday at bit 6, so the shift is
 * `DayOfWeek.value - 1` and no lookup table is needed. Stored as a plain `Int`
 * column, which keeps it a value the sync layer can carry as-is.
 */
object AlarmDays {

    const val EVERY_DAY = 0b111_1111

    /** Monday through Friday. */
    const val WEEKDAYS = 0b001_1111

    /** Saturday and Sunday. */
    const val WEEKENDS = 0b110_0000

    /** Monday-first, matching both [DayOfWeek] and the bit order. */
    val ORDER: List<DayOfWeek> = DayOfWeek.entries.toList()

    /**
     * An empty mask reads as every day rather than never.
     *
     * The UI does not allow clearing every day, so a zero here is corruption —
     * a partial write, or a row from some future version. The house rule for an
     * alarm clock that is unsure is that it still wakes the user, so the safe
     * reading of "no days" is "all of them", never silence.
     */
    fun normalise(mask: Int): Int = (mask and EVERY_DAY).takeIf { it != 0 } ?: EVERY_DAY

    fun contains(mask: Int, day: DayOfWeek): Boolean =
        (normalise(mask) shr (day.value - 1)) and 1 == 1

    /** Toggling the last remaining day is refused — see [normalise]. */
    fun toggle(mask: Int, day: DayOfWeek): Int {
        val toggled = normalise(mask) xor (1 shl (day.value - 1))
        return if (toggled == 0) mask else toggled
    }

    /** "Every day", "Weekdays", "Weekends", or "Mon, Wed, Fri". */
    fun label(mask: Int): String = when (val m = normalise(mask)) {
        EVERY_DAY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        else -> ORDER.filter { contains(m, it) }.joinToString(", ") { shortName(it) }
    }

    fun shortName(day: DayOfWeek): String =
        day.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
}

/**
 * The next wall-clock date-time at [hour]:[minute] falling on a day in
 * [daysMask], strictly after [from].
 *
 * Pure and timezone-free on purpose: [AlarmScheduler] supplies `LocalDateTime.now()`
 * and converts the answer, so every awkward case — "today, but the time has
 * passed", "Friday's alarm on a Friday evening", a mask with one day in it —
 * is testable without a device or a clock.
 *
 * Bounded at 7 look-aheads because a normalised mask always has a day set, so a
 * week can never pass without a match.
 */
fun nextAlarmTrigger(from: LocalDateTime, hour: Int, minute: Int, daysMask: Int): LocalDateTime {
    val mask = AlarmDays.normalise(daysMask)
    var candidate = from.toLocalDate().atTime(hour, minute)
    if (!candidate.isAfter(from)) candidate = candidate.plusDays(1)
    repeat(DAYS_IN_WEEK) {
        if (AlarmDays.contains(mask, candidate.dayOfWeek)) return candidate
        candidate = candidate.plusDays(1)
    }
    return candidate
}

private const val DAYS_IN_WEEK = 7
