package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.ExamDao
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Today's goal progress for the dashboard. */
data class TodayStatus(
    val pushupCount: Int = 0,
    val pushupTarget: Int = 0,
    val focusMinutes: Int = 0,
    val mathCount: Int = 0,
    val mathTarget: Int = 0,
    /** Review alarms answered today, the cards graded in them, and how many alarms ask. */
    val reviewCount: Int = 0,
    val reviewCards: Int = 0,
    val reviewTarget: Int = 0
) {
    /**
     * Requires a target: with no pushup alarm scheduled today, `0 >= 0` would
     * read as "done" and quietly credit a day that was never asked for one.
     */
    val pushupDone: Boolean get() = pushupTarget > 0 && pushupCount >= pushupTarget
    val mathDone: Boolean get() = mathTarget > 0 && mathCount >= mathTarget
    val reviewDone: Boolean get() = reviewTarget > 0 && reviewCount >= reviewTarget
}

/** One day of history for the 7-day chart. */
data class DayStat(
    val epochDay: Long,
    val pushupCount: Int = 0,
    val pushupTarget: Int = 0,
    val focusMinutes: Int = 0,
    /** The focus goal this day is scored against: the current one, raised before an exam. */
    val focusGoalMinutes: Int = 0,
    val skipped: Boolean = false,
    /** Streak outcome, i.e. [focusDayOutcome]. */
    val outcome: DayOutcome = DayOutcome.NO_GOALS
) {
    /** See [TodayStatus.pushupDone] — a day with no pushup alarm is not "done". */
    val pushupDone: Boolean get() = pushupTarget > 0 && pushupCount >= pushupTarget
}

/**
 * Current streak, and the longest one inside the stats window.
 *
 * [best] is shown beside [days] because a streak that has just broken otherwise
 * reads as nothing ever having been achieved.
 */
data class StreakInfo(val days: Int = 0, val best: Int = 0)

/**
 * Derives dashboard stats and the streak from [CompletionLog]s.
 *
 * Streak rule (2026-10-07): a day counts when its FOCUS minutes reach the daily
 * focus goal — see [focusDayOutcome]. Alarm targets still come from the schedule
 * ([dailyGoalsFrom]) and are still shown, but no longer decide the streak.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val completionRepository: CompletionRepository,
    private val alarmDao: AlarmDao,
    private val settingsRepository: SettingsRepository,
    private val examDao: ExamDao
) {
    private fun window(): Flow<List<CompletionLog>> =
        completionRepository.observeSince(LocalDate.now().toEpochDay() - WINDOW_DAYS)

    private data class Source(
        val logs: List<CompletionLog>,
        val alarms: List<Alarm>,
        /** The goal the user set; [goalOn] is what a day is scored against. */
        val focusGoalMinutes: Int,
        val exams: List<ExamDay>
    ) {
        fun goalOn(day: Long): Int = focusGoalOn(day, focusGoalMinutes, exams)
    }

    /** Logs, the schedule and the focus goal together — every number here is scored against them. */
    private fun source(): Flow<Source> = combine(
        window(),
        alarmDao.observeAll(),
        settingsRepository.settings.map { it.dailyFocusGoalMinutes }.distinctUntilChanged(),
        // Past exams too: the days before them were scored against a raised goal.
        examDao.observeUpcoming(LocalDate.now().toEpochDay() - WINDOW_DAYS)
            .map { rows -> rows.map { ExamDay(it.name, it.epochDay) } }
    ) { logs, alarms, goal, exams -> Source(logs, alarms, goal, exams) }

    /** Today's focus goal: the user's own, raised before an exam ([focusGoalOn]). */
    fun observeFocusGoal(): Flow<FocusGoalToday> = source().map { src ->
        focusGoalToday(LocalDate.now().toEpochDay(), src.focusGoalMinutes, src.exams)
    }.distinctUntilChanged()

    fun observeToday(): Flow<TodayStatus> = source().map { (logs, alarms) ->
        val epochDay = LocalDate.now().toEpochDay()
        val today = logs.filter { it.epochDay == epochDay }
        val goals = dailyGoalsFrom(alarms, dayOfWeekFor(epochDay))
        TodayStatus(
            pushupCount = pushupsVerified(today),
            pushupTarget = goals.pushupTarget,
            focusMinutes = today.focusMinutes(),
            mathCount = mathsSolved(today),
            mathTarget = goals.mathTarget,
            reviewCount = reviewsDone(today),
            reviewCards = today
                .filter { it.kind == GoalKind.REVIEW && it.status != CompletionStatus.SKIPPED }
                .sumOf { it.amount },
            reviewTarget = goals.reviewTarget
        )
    }

    fun observeWeek(): Flow<List<DayStat>> = source().map { src ->
        val today = LocalDate.now().toEpochDay()
        val byDay = src.logs.groupBy { it.epochDay }
        (6 downTo 0).map { offset -> dayStat(today - offset, byDay, src) }
    }

    fun observeStreak(): Flow<StreakInfo> = source().map { src ->
        val today = LocalDate.now().toEpochDay()
        val byDay = src.logs.groupBy { it.epochDay }
        fun outcomeOn(day: Long) =
            focusDayOutcome(byDay[day].orEmpty().focusMinutes(), src.goalOn(day))

        // Today is still in progress, so a day that has not met its goal yet is
        // not a broken streak — start from yesterday unless today is already won.
        val start = if (outcomeOn(today) == DayOutcome.MET) today else today - 1
        val outcomes = (0 until WINDOW_DAYS).map { offset -> outcomeOn(start - offset) }
        StreakInfo(days = streakLength(outcomes), best = longestStreak(outcomes))
    }

    private fun dayStat(day: Long, byDay: Map<Long, List<CompletionLog>>, src: Source): DayStat {
        val dayLogs = byDay[day].orEmpty()
        val goals = dailyGoalsFrom(src.alarms, dayOfWeekFor(day))
        val focus = dayLogs.focusMinutes()
        return DayStat(
            epochDay = day,
            pushupCount = pushupsVerified(dayLogs),
            pushupTarget = goals.pushupTarget,
            focusMinutes = focus,
            focusGoalMinutes = src.goalOn(day),
            // An emergency-dismissed ALARM; a withered focus tree is not one.
            skipped = dayLogs.any { it.kind != GoalKind.FOCUS && it.status == CompletionStatus.SKIPPED },
            outcome = focusDayOutcome(focus, src.goalOn(day))
        )
    }

    /**
     * Only VERIFIED blocks count. A block whose tree withered (the user left for
     * a distracting app mid-focus, see PomodoroTimerService) is logged SKIPPED so
     * the forest can draw it, but it is not focus time.
     */
    private fun List<CompletionLog>.focusMinutes(): Int =
        filter { it.kind == GoalKind.FOCUS && it.status == CompletionStatus.VERIFIED }.sumOf { it.amount }

    private companion object {
        const val WINDOW_DAYS = 90L
    }
}
