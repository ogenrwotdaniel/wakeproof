package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.alarm.AlarmDays
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * How much a given day is asking for: one verification per alarm that rings
 * that day.
 *
 * This used to be hardcoded — one pushup and three waters, forever, whatever
 * the schedule said. Someone running one pushup alarm and two water alarms
 * therefore had a streak that could never advance: the day demanded a third
 * water that nothing would ever ring for. The schedule is the only honest
 * source for the target, so the target is read from it.
 */
data class DailyGoals(val pushupTarget: Int, val waterTarget: Int) {
    val hasAny: Boolean get() = pushupTarget > 0 || waterTarget > 0
    val total: Int get() = pushupTarget + waterTarget
}

/** What a day did with the goals it was set. */
enum class DayOutcome {
    /** Every goal met, nothing skipped. */
    MET,

    /** Goals were set and not all met — this is what ends a streak. */
    MISSED,

    /**
     * Nothing was asked of this day, so it is neither a win nor a loss.
     *
     * Real once alarms repeat weekly: a Mon–Fri pushup alarm asks nothing on a
     * Sunday, and a Sunday that counted as MISSED would reset the streak every
     * weekend for doing exactly what was intended.
     */
    NO_GOALS
}

/**
 * The goals [day] carries, given the alarms as they stand now.
 *
 * Disabled and deleted alarms ask for nothing, and an alarm only asks on the
 * days its own repeat mask covers. [AlarmType.CUSTOM] is counted as neither —
 * it has no verification screen behind it, so no completion log can ever
 * satisfy it and counting it would freeze the streak permanently.
 *
 * Applied to past days too, since only the *current* schedule is stored — the
 * app has no record of what the alarms were three days ago. That is a real
 * approximation: adding a water alarm today re-scores the whole visible week
 * against the new target.
 */
fun dailyGoalsFrom(alarms: List<Alarm>, day: DayOfWeek): DailyGoals {
    val live = alarms.filter { it.enabled && !it.deleted && AlarmDays.contains(it.daysMask, day) }
    return DailyGoals(
        pushupTarget = live.count { it.type == AlarmType.PUSHUP },
        waterTarget = live.count { it.type == AlarmType.WATER }
    )
}

/** Completions that count toward a goal: anything not emergency-dismissed. */
private fun List<CompletionLog>.countOf(kind: GoalKind): Int =
    count { it.kind == kind && it.status != CompletionStatus.SKIPPED }

/** Pushups must be camera-verified; honor-mode water still counts (with an asterisk). */
fun pushupsVerified(dayLogs: List<CompletionLog>): Int =
    dayLogs.count { it.kind == GoalKind.PUSHUP && it.status == CompletionStatus.VERIFIED }

fun watersLogged(dayLogs: List<CompletionLog>): Int = dayLogs.countOf(GoalKind.WATER)

/**
 * Scores one day. Pure, so the streak rule is testable without Room, a clock or
 * a device.
 *
 * An emergency dismiss anywhere in the day is decisive: it is the escape hatch,
 * and a day that used it did not meet its goals however the counts land.
 */
fun dayOutcome(dayLogs: List<CompletionLog>, goals: DailyGoals): DayOutcome = when {
    !goals.hasAny -> DayOutcome.NO_GOALS
    dayLogs.any { it.status == CompletionStatus.SKIPPED } -> DayOutcome.MISSED
    pushupsVerified(dayLogs) >= goals.pushupTarget &&
        watersLogged(dayLogs) >= goals.waterTarget -> DayOutcome.MET

    else -> DayOutcome.MISSED
}

/**
 * Positions in [outcomes] — newest day first — of the days making up the current
 * streak.
 *
 * [DayOutcome.NO_GOALS] days are stepped over without being counted, so a
 * weekday-only schedule keeps its streak across the weekend instead of resetting
 * every Saturday. A run of nothing but NO_GOALS is a streak of zero, which is the
 * right answer for a schedule that currently asks for nothing.
 *
 * Returns the positions rather than just a count because the caller needs to
 * look the days back up — the honor-mode asterisk is a property of which days
 * are in the streak, not of how many.
 */
fun streakDayIndices(outcomes: List<DayOutcome>): List<Int> =
    outcomes.asSequence()
        .takeWhile { it != DayOutcome.MISSED }
        .withIndex()
        .filter { it.value == DayOutcome.MET }
        .map { it.index }
        .toList()

/** How many days the current streak runs to. See [streakDayIndices]. */
fun streakLength(outcomes: List<DayOutcome>): Int = streakDayIndices(outcomes).size

/** Today's [DayOfWeek], extracted so callers can pass a fixed date in tests. */
internal fun dayOfWeekFor(epochDay: Long): DayOfWeek = LocalDate.ofEpochDay(epochDay).dayOfWeek
