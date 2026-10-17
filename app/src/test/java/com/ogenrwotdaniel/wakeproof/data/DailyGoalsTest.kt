package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.alarm.AlarmDays
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyGoalsTest {

    private fun alarm(
        type: AlarmType,
        daysMask: Int = AlarmDays.EVERY_DAY,
        enabled: Boolean = true,
        deleted: Boolean = false
    ) = Alarm(
        type = type,
        hour = 6,
        minute = 0,
        enabled = enabled,
        daysMask = daysMask,
        deleted = deleted
    )

    private fun log(kind: GoalKind, status: CompletionStatus = CompletionStatus.VERIFIED) =
        CompletionLog(epochDay = 20_000L, kind = kind, status = status, amount = 0)

    private fun logs(vararg entries: CompletionLog) = entries.toList()

    // --- targets come from the schedule ---------------------------------------

    @Test
    fun `targets count the alarms actually set`() {
        val goals = dailyGoalsFrom(
            listOf(
                alarm(AlarmType.PUSHUP),
                alarm(AlarmType.WATER),
                alarm(AlarmType.WATER)
            ),
            DayOfWeek.MONDAY
        )
        // The bug this replaced: one pushup and two waters scored against a
        // hardcoded three-water target, so the day could never be completed.
        assertEquals(DailyGoals(pushupTarget = 1, waterTarget = 2), goals)
    }

    @Test
    fun `disabled and deleted alarms ask for nothing`() {
        val goals = dailyGoalsFrom(
            listOf(
                alarm(AlarmType.WATER),
                alarm(AlarmType.WATER, enabled = false),
                alarm(AlarmType.WATER, deleted = true)
            ),
            DayOfWeek.MONDAY
        )
        assertEquals(1, goals.waterTarget)
    }

    @Test
    fun `custom alarms are counted as neither goal`() {
        // Nothing can log a completion for one, so counting it would freeze the
        // streak at zero forever.
        val goals = dailyGoalsFrom(listOf(alarm(AlarmType.CUSTOM)), DayOfWeek.MONDAY)
        assertFalse(goals.hasAny)
    }

    @Test
    fun `math alarms are their own target`() {
        // They used to count toward nothing, so a solved math mission never
        // appeared on Home — the morning looked as though nothing was done.
        val goals = dailyGoalsFrom(
            listOf(alarm(AlarmType.MATH), alarm(AlarmType.PUSHUP)),
            DayOfWeek.MONDAY
        )
        assertEquals(DailyGoals(pushupTarget = 1, waterTarget = 0, mathTarget = 1), goals)
    }

    @Test
    fun `only a solved math run counts, not an emergency dismiss`() {
        val day = logs(log(GoalKind.MATH), log(GoalKind.MATH, CompletionStatus.SKIPPED))
        assertEquals(1, mathsSolved(day))
    }

    @Test
    fun `an alarm only asks on the days it repeats`() {
        val weekdayPushups = listOf(alarm(AlarmType.PUSHUP, AlarmDays.WEEKDAYS))
        assertEquals(1, dailyGoalsFrom(weekdayPushups, DayOfWeek.FRIDAY).pushupTarget)
        assertEquals(0, dailyGoalsFrom(weekdayPushups, DayOfWeek.SATURDAY).pushupTarget)
    }

    // --- scoring one day ------------------------------------------------------

    @Test
    fun `a day meeting every goal is MET`() {
        val goals = DailyGoals(pushupTarget = 1, waterTarget = 2)
        val met = logs(
            log(GoalKind.PUSHUP),
            log(GoalKind.WATER),
            log(GoalKind.WATER)
        )
        assertEquals(DayOutcome.MET, dayOutcome(met, goals))
    }

    @Test
    fun `honor-mode water counts toward the target`() {
        val goals = DailyGoals(pushupTarget = 0, waterTarget = 1)
        val honor = logs(log(GoalKind.WATER, CompletionStatus.UNVERIFIED))
        assertEquals(DayOutcome.MET, dayOutcome(honor, goals))
    }

    @Test
    fun `an unverified pushup does not count`() {
        val goals = DailyGoals(pushupTarget = 1, waterTarget = 0)
        val honor = logs(log(GoalKind.PUSHUP, CompletionStatus.UNVERIFIED))
        assertEquals(DayOutcome.MISSED, dayOutcome(honor, goals))
    }

    @Test
    fun `an emergency dismiss anywhere in the day misses it`() {
        val goals = DailyGoals(pushupTarget = 1, waterTarget = 1)
        val skipped = logs(
            log(GoalKind.PUSHUP),
            log(GoalKind.WATER),
            log(GoalKind.WATER, CompletionStatus.SKIPPED)
        )
        assertEquals(DayOutcome.MISSED, dayOutcome(skipped, goals))
    }

    @Test
    fun `a math target is met by a solved run`() {
        val goals = DailyGoals(pushupTarget = 0, waterTarget = 0, mathTarget = 1)
        assertEquals(DayOutcome.MET, dayOutcome(logs(log(GoalKind.MATH)), goals))
        assertEquals(DayOutcome.MISSED, dayOutcome(emptyList(), goals))
    }

    @Test
    fun `a day with no goals is neither won nor lost`() {
        assertEquals(DayOutcome.NO_GOALS, dayOutcome(emptyList(), DailyGoals(0, 0)))
    }

    @Test
    fun `extra completions beyond the target still count as met`() {
        val goals = DailyGoals(pushupTarget = 0, waterTarget = 1)
        val plenty = logs(log(GoalKind.WATER), log(GoalKind.WATER), log(GoalKind.WATER))
        assertEquals(DayOutcome.MET, dayOutcome(plenty, goals))
    }

    // --- the streak walk ------------------------------------------------------

    @Test
    fun `the streak stops at the first missed day`() {
        val outcomes = listOf(
            DayOutcome.MET,
            DayOutcome.MET,
            DayOutcome.MISSED,
            DayOutcome.MET
        )
        assertEquals(2, streakLength(outcomes))
        assertEquals(listOf(0, 1), streakDayIndices(outcomes))
    }

    @Test
    fun `unscheduled days are stepped over, not counted, and do not break it`() {
        // A weekday-only schedule: Sat and Sun ask for nothing. Before this the
        // streak reset every weekend for doing exactly what was intended.
        val outcomes = listOf(
            DayOutcome.MET,       // Friday
            DayOutcome.NO_GOALS,  // Thursday off? no — the weekend, going back
            DayOutcome.NO_GOALS,
            DayOutcome.MET,       // the Friday before
            DayOutcome.MET
        )
        assertEquals(3, streakLength(outcomes))
        assertEquals(listOf(0, 3, 4), streakDayIndices(outcomes))
    }

    @Test
    fun `a schedule asking for nothing at all is a streak of zero`() {
        assertEquals(0, streakLength(List(7) { DayOutcome.NO_GOALS }))
    }

    @Test
    fun `an empty history is a streak of zero`() {
        assertEquals(0, streakLength(emptyList()))
        assertTrue(streakDayIndices(emptyList()).isEmpty())
    }

    @Test
    fun `focus day is met only once the goal is reached`() {
        assertEquals(DayOutcome.MET, focusDayOutcome(focusMinutes = 120, goalMinutes = 120))
        assertEquals(DayOutcome.MET, focusDayOutcome(focusMinutes = 300, goalMinutes = 120))
        assertEquals(DayOutcome.MISSED, focusDayOutcome(focusMinutes = 119, goalMinutes = 120))
    }

    @Test
    fun `a zero focus goal asks for nothing rather than handing out free days`() {
        assertEquals(DayOutcome.NO_GOALS, focusDayOutcome(focusMinutes = 0, goalMinutes = 0))
        assertEquals(DayOutcome.NO_GOALS, focusDayOutcome(focusMinutes = 50, goalMinutes = 0))
    }

    @Test
    fun `longest streak finds the best run anywhere and steps over empty days`() {
        val outcomes = listOf(
            DayOutcome.MISSED,
            DayOutcome.MET,
            DayOutcome.MET,
            DayOutcome.NO_GOALS,
            DayOutcome.MET,
            DayOutcome.MISSED,
            DayOutcome.MET
        )
        assertEquals(3, longestStreak(outcomes))
        assertEquals(0, streakLength(outcomes))
        assertEquals(0, longestStreak(emptyList()))
    }
}
