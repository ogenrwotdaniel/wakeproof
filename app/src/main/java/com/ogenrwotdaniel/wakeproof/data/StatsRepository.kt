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
    val skipped: Boolean = false,
    val outcome: DayOutcome = DayOutcome.NO_GOALS
) {
    /** See [TodayStatus.pushupDone] — a day with no pushup alarm is not "done". */
    val pushupDone: Boolean get() = pushupTarget > 0 && pushupCount >= pushupTarget

    /**
     * Share of the day's goals that were met, 0..1 — what the 7-day chart draws.
     * A day that was asked for nothing draws empty rather than full: an
     * unscheduled Sunday is not an achievement.
     */
    val goalProgress: Float
        get() {
            val target = pushupTarget + waterTarget
            if (target <= 0) return 0f
            val met = pushupCount.coerceAtMost(pushupTarget) + waterCount.coerceAtMost(waterTarget)
            return met.toFloat() / target
        }
}

/** Current streak. [asterisk] = an honor-mode (unverified) water is inside it. */
data class StreakInfo(val days: Int = 0, val asterisk: Boolean = false)

/**
 * Derives dashboard stats and the streak from [CompletionLog]s, scored against
 * the goals the alarm schedule actually sets — see [dailyGoalsFrom].
 *
 * Streak rule: a day counts when every pushup alarm due that day has a
 * camera-VERIFIED completion, every water alarm due that day has a logged one
 * (VERIFIED or honor-mode UNVERIFIED), and nothing was SKIPPED via the
 * emergency dismiss. A day with no alarms due breaks nothing.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val completionRepository: CompletionRepository,
    private val alarmDao: AlarmDao
) {
    private fun window(): Flow<List<CompletionLog>> =
        completionRepository.observeSince(LocalDate.now().toEpochDay() - WINDOW_DAYS)

    /** Logs and the schedule together — every number here is scored against both. */
    private fun source(): Flow<Pair<List<CompletionLog>, List<Alarm>>> =
        combine(window(), alarmDao.observeAll()) { logs, alarms -> logs to alarms }

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
            focusMinutes = today.filter { it.kind == GoalKind.FOCUS }.sumOf { it.amount },
            anyUnverifiedWater = waters.any { it.status == CompletionStatus.UNVERIFIED }
        )
    }

    fun observeWeek(): Flow<List<DayStat>> = source().map { (logs, alarms) ->
        val today = LocalDate.now().toEpochDay()
        val byDay = logs.groupBy { it.epochDay }
        (6 downTo 0).map { offset -> dayStat(today - offset, byDay, alarms) }
    }

    fun observeStreak(): Flow<StreakInfo> = source().map { (logs, alarms) ->
        val today = LocalDate.now().toEpochDay()
        val byDay = logs.groupBy { it.epochDay }
        fun outcomeOn(day: Long) =
            dayOutcome(byDay[day].orEmpty(), dailyGoalsFrom(alarms, dayOfWeekFor(day)))

        // Today is still in progress, so a day that has not met its goals yet is
        // not a broken streak — start from yesterday unless today is already won.
        val start = if (outcomeOn(today) == DayOutcome.MET) today else today - 1
        val outcomes = (0 until WINDOW_DAYS).map { offset -> outcomeOn(start - offset) }
        val streak = streakDayIndices(outcomes)

        val asterisk = streak.any { offset ->
            byDay[start - offset].orEmpty().any {
                it.kind == GoalKind.WATER && it.status == CompletionStatus.UNVERIFIED
            }
        }
        StreakInfo(streak.size, asterisk)
    }

    private fun dayStat(
        day: Long,
        byDay: Map<Long, List<CompletionLog>>,
        alarms: List<Alarm>
    ): DayStat {
        val dayLogs = byDay[day].orEmpty()
        val goals = dailyGoalsFrom(alarms, dayOfWeekFor(day))
        return DayStat(
            epochDay = day,
            pushupCount = pushupsVerified(dayLogs),
            pushupTarget = goals.pushupTarget,
            waterCount = watersLogged(dayLogs),
            waterTarget = goals.waterTarget,
            focusMinutes = dayLogs.filter { it.kind == GoalKind.FOCUS }.sumOf { it.amount },
            skipped = dayLogs.any { it.status == CompletionStatus.SKIPPED },
            outcome = dayOutcome(dayLogs, goals)
        )
    }

    private companion object {
        const val WINDOW_DAYS = 90L
    }
}
