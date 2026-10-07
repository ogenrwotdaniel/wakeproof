package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
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
    val waterCount: Int = 0,
    val waterMl: Int = 0,
    val waterTargetCount: Int = 0,
    val focusMinutes: Int = 0,
    val anyUnverifiedWater: Boolean = false
) {
    /**
     * Requires a target: with no pushup alarm scheduled today, `0 >= 0` would
     * read as "done" and quietly credit a day that was never asked for one.
     */
    val pushupDone: Boolean get() = pushupTarget > 0 && pushupCount >= pushupTarget
    val waterDone: Boolean get() = waterTargetCount > 0 && waterCount >= waterTargetCount
}

/** One day of history for the 7-day chart. */
data class DayStat(
    val epochDay: Long,
    val pushupCount: Int = 0,
    val pushupTarget: Int = 0,
    val waterCount: Int = 0,
    val waterTarget: Int = 0,
    val focusMinutes: Int = 0,
    /** The daily focus goal this day is scored against — the current one. */
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
    private val settingsRepository: SettingsRepository
) {
    private fun window(): Flow<List<CompletionLog>> =
        completionRepository.observeSince(LocalDate.now().toEpochDay() - WINDOW_DAYS)

    private data class Source(
        val logs: List<CompletionLog>,
        val alarms: List<Alarm>,
        val focusGoalMinutes: Int
    )

    /** Logs, the schedule and the focus goal together — every number here is scored against them. */
    private fun source(): Flow<Source> = combine(
        window(),
        alarmDao.observeAll(),
        settingsRepository.settings.map { it.dailyFocusGoalMinutes }.distinctUntilChanged()
    ) { logs, alarms, goal -> Source(logs, alarms, goal) }

    fun observeToday(): Flow<TodayStatus> = source().map { (logs, alarms) ->
        val epochDay = LocalDate.now().toEpochDay()
        val today = logs.filter { it.epochDay == epochDay }
        val goals = dailyGoalsFrom(alarms, dayOfWeekFor(epochDay))
        val waters = today.filter { it.kind == GoalKind.WATER && it.status != CompletionStatus.SKIPPED }
        TodayStatus(
            pushupCount = pushupsVerified(today),
            pushupTarget = goals.pushupTarget,
            waterCount = waters.size,
            waterMl = waters.sumOf { it.amount },
            waterTargetCount = goals.waterTarget,
            focusMinutes = today.focusMinutes(),
            anyUnverifiedWater = waters.any { it.status == CompletionStatus.UNVERIFIED }
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
            focusDayOutcome(byDay[day].orEmpty().focusMinutes(), src.focusGoalMinutes)

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
            waterCount = watersLogged(dayLogs),
            waterTarget = goals.waterTarget,
            focusMinutes = focus,
            focusGoalMinutes = src.focusGoalMinutes,
            // An emergency-dismissed ALARM; a withered focus tree is not one.
            skipped = dayLogs.any { it.kind != GoalKind.FOCUS && it.status == CompletionStatus.SKIPPED },
            outcome = focusDayOutcome(focus, src.focusGoalMinutes)
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
