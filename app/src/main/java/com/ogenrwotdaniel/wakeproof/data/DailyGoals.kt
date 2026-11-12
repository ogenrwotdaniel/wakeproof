package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.alarm.StrictAlarm
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
 * This used to be hardcoded, whatever the schedule said, so a day could demand
 * a verification that nothing would ever ring for. The schedule is the only
 * honest source for the target, so the target is read from it.
 */
data class DailyGoals(
    val pushupTarget: Int,
    val mathTarget: Int = 0,
    val planTarget: Int = 0,
    val reviewTarget: Int = 0,
    val shutdownTarget: Int = 0
) {
    val hasAny: Boolean get() = total > 0
    val total: Int get() = pushupTarget + mathTarget + planTarget + reviewTarget + shutdownTarget
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
 * days its own repeat mask covers. Each verifying type is its own target — a
 * MATH alarm asks for one solved run, and was once left out entirely, so a
 * morning's math never appeared on Home at all. [AlarmType.CUSTOM] and the retired
 * [AlarmType.WATER] are counted as none: they have no verification screen
 * behind them, so no completion log can ever satisfy them.
 *
 * Applied to past days too, since only the *current* schedule is stored — the
 * app has no record of what the alarms were three days ago. That is a real
 * approximation: adding an alarm today re-scores the whole visible week
 * against the new target.
 */
fun dailyGoalsFrom(alarms: List<Alarm>, day: DayOfWeek): DailyGoals {
    val live = alarms.filter { it.enabled && !it.deleted && AlarmDays.contains(it.daysMask, day) }
    // Rows on a locked minute of their kind ring once between them (the copies
    // are never armed), and rows on a former locked minute never ring: counting
    // them asked for pushups no alarm could bring, a miss every day. Decided by
    // minute alone so the tablet, which holds no locked ids, scores the same.
    fun rings(type: AlarmType): Int {
        val minutes = live.filter { it.type == type }.map { it.hour * 60 + it.minute }
        val slots = StrictAlarm.slotMinutes(type)
        return minutes.count { it !in StrictAlarm.silentMinutes(type) } + minutes.filter { it in slots }.toSet().size
    }
    return DailyGoals(
        pushupTarget = rings(AlarmType.PUSHUP),
        mathTarget = rings(AlarmType.MATH),
        planTarget = rings(AlarmType.PLAN),
        reviewTarget = rings(AlarmType.REVIEW),
        shutdownTarget = rings(AlarmType.SHUTDOWN)
    )
}

/** Completions that count toward a goal: anything not emergency-dismissed. */
private fun List<CompletionLog>.countOf(kind: GoalKind): Int =
    count { it.kind == kind && it.status != CompletionStatus.SKIPPED }

/** Pushups must be camera-verified. */
fun pushupsVerified(dayLogs: List<CompletionLog>): Int =
    dayLogs.count { it.kind == GoalKind.PUSHUP && it.status == CompletionStatus.VERIFIED }

/** Day-plan photos saved, at the alarm or from the library. */
fun plansLogged(dayLogs: List<CompletionLog>): Int = dayLogs.countOf(GoalKind.PLAN)

/** Evening reviews written, at the alarm or from the library. */
fun shutdownsDone(dayLogs: List<CompletionLog>): Int = dayLogs.countOf(GoalKind.SHUTDOWN)

/** Review alarms answered (one log per run, whatever its card count). */
fun reviewsDone(dayLogs: List<CompletionLog>): Int = dayLogs.countOf(GoalKind.REVIEW)

/** Math runs actually solved; an emergency-dismissed one is logged SKIPPED and does not count. */
fun mathsSolved(dayLogs: List<CompletionLog>): Int =
    dayLogs.count { it.kind == GoalKind.MATH && it.status == CompletionStatus.VERIFIED }

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
        mathsSolved(dayLogs) >= goals.mathTarget &&
        plansLogged(dayLogs) >= goals.planTarget &&
        reviewsDone(dayLogs) >= goals.reviewTarget &&
        shutdownsDone(dayLogs) >= goals.shutdownTarget -> DayOutcome.MET

    else -> DayOutcome.MISSED
}

/**
 * Scores one day for the STREAK, which since 2026-10-07 is about focus time
 * alone: a day counts once the daily focus goal is reached.
 *
 * Alarms used to decide it. That measured whether you got up, not whether you
 * studied, and the streak is the number the dashboard leads with — so it was
 * leading with the wrong thing. Alarm verifications are still logged and still
 * shown on their own cards; they just no longer make or break a day.
 *
 * Like [dailyGoalsFrom], past days are scored against the goal as it stands now,
 * since no history of the setting is stored. A goal of zero asks for nothing,
 * which is [DayOutcome.NO_GOALS] rather than a free win every day.
 */
fun focusDayOutcome(focusMinutes: Int, goalMinutes: Int): DayOutcome = when {
    goalMinutes <= 0 -> DayOutcome.NO_GOALS
    focusMinutes >= goalMinutes -> DayOutcome.MET
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
 * Returns the positions rather than just a count so a caller can look the
 * days back up.
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

/**
 * The longest run of [DayOutcome.MET] days anywhere in [outcomes], with
 * [DayOutcome.NO_GOALS] stepped over exactly as [streakDayIndices] does.
 * Direction does not matter.
 */
fun longestStreak(outcomes: List<DayOutcome>): Int {
    var best = 0
    var run = 0
    for (outcome in outcomes) {
        when (outcome) {
            DayOutcome.MET -> { run++; if (run > best) best = run }
            DayOutcome.MISSED -> run = 0
            DayOutcome.NO_GOALS -> Unit
        }
    }
    return best
}

/** Today's [DayOfWeek], extracted so callers can pass a fixed date in tests. */
internal fun dayOfWeekFor(epochDay: Long): DayOfWeek = LocalDate.ofEpochDay(epochDay).dayOfWeek
