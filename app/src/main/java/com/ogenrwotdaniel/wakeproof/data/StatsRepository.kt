package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Today's goal progress for the dashboard. */
data class TodayStatus(
    val pushupDone: Boolean = false,
    val waterCount: Int = 0,
    val waterMl: Int = 0,
    val waterTargetCount: Int = 3,
    val focusMinutes: Int = 0,
    val anyUnverifiedWater: Boolean = false
)

/** One day of history for the 7-day chart. */
data class DayStat(
    val epochDay: Long,
    val pushupDone: Boolean,
    val waterCount: Int,
    val focusMinutes: Int,
    val skipped: Boolean
)

/** Current streak. [asterisk] = an honor-mode (unverified) water is inside it. */
data class StreakInfo(val days: Int = 0, val asterisk: Boolean = false)

/**
 * Derives dashboard stats and the streak from [CompletionLog]s.
 * Streak rule: a day counts only if pushups are camera-VERIFIED, at least 3
 * waters are logged (VERIFIED or honor-mode UNVERIFIED), and nothing was
 * SKIPPED via emergency dismiss that day.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val completionRepository: CompletionRepository
) {
    private fun window(): Flow<List<CompletionLog>> =
        completionRepository.observeSince(LocalDate.now().toEpochDay() - WINDOW_DAYS)

    fun observeToday(): Flow<TodayStatus> = window().map { logs ->
        val today = logs.filter { it.epochDay == LocalDate.now().toEpochDay() }
        val waters = today.filter { it.kind == GoalKind.WATER && it.status != CompletionStatus.SKIPPED }
        TodayStatus(
            pushupDone = today.any { it.kind == GoalKind.PUSHUP && it.status == CompletionStatus.VERIFIED },
            waterCount = waters.size,
            waterMl = waters.sumOf { it.amount },
            focusMinutes = today.filter { it.kind == GoalKind.FOCUS }.sumOf { it.amount },
            anyUnverifiedWater = waters.any { it.status == CompletionStatus.UNVERIFIED }
        )
    }

    fun observeWeek(): Flow<List<DayStat>> = window().map { logs ->
        val today = LocalDate.now().toEpochDay()
        val byDay = logs.groupBy { it.epochDay }
        (6 downTo 0).map { offset ->
            val day = today - offset
            val dayLogs = byDay[day].orEmpty()
            DayStat(
                epochDay = day,
                pushupDone = dayLogs.any { it.kind == GoalKind.PUSHUP && it.status == CompletionStatus.VERIFIED },
                waterCount = dayLogs.count { it.kind == GoalKind.WATER && it.status != CompletionStatus.SKIPPED },
                focusMinutes = dayLogs.filter { it.kind == GoalKind.FOCUS }.sumOf { it.amount },
                skipped = dayLogs.any { it.status == CompletionStatus.SKIPPED }
            )
        }
    }

    fun observeStreak(): Flow<StreakInfo> = window().map { logs ->
        val today = LocalDate.now().toEpochDay()
        val byDay = logs.groupBy { it.epochDay }
        // Today still in progress doesn't break the streak; start from yesterday then.
        var day = if (dayCounts(byDay[today].orEmpty())) today else today - 1
        var days = 0
        var asterisk = false
        while (dayCounts(byDay[day].orEmpty())) {
            days++
            if (byDay[day].orEmpty().any {
                    it.kind == GoalKind.WATER && it.status == CompletionStatus.UNVERIFIED
                }
            ) {
                asterisk = true
            }
            day--
        }
        StreakInfo(days, asterisk)
    }

    private fun dayCounts(dayLogs: List<CompletionLog>): Boolean =
        dayLogs.none { it.status == CompletionStatus.SKIPPED } &&
            dayLogs.any { it.kind == GoalKind.PUSHUP && it.status == CompletionStatus.VERIFIED } &&
            dayLogs.count { it.kind == GoalKind.WATER && it.status != CompletionStatus.SKIPPED } >= 3

    private companion object {
        const val WINDOW_DAYS = 90L
    }
}
