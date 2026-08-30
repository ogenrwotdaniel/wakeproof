package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.SubjectMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportGeneratorTest {

    private val generator = WeeklyReportGenerator()

    /** Monday 2024-09-30 is epoch day 19_996 — fixed so day names are stable. */
    private val monday = 19_996L

    private fun week(focusPerDay: List<Int>, skipped: Set<Int> = emptySet()) =
        focusPerDay.mapIndexed { index, minutes ->
            DayStat(
                epochDay = monday + index,
                pushupCount = 0,
                pushupTarget = 1,
                waterCount = 0,
                waterTarget = 3,
                focusMinutes = minutes,
                skipped = index in skipped
            )
        }

    private fun stats(
        focusPerDay: List<Int> = List(7) { 130 },
        skipped: Set<Int> = emptySet(),
        subjects: List<SubjectMinutes> = listOf(SubjectMinutes("Math", 500)),
        screen: List<DayUsage> = emptyList(),
        apps: List<AppUsage> = emptyList(),
        goal: Int = 120
    ) = WeekStats(
        days = week(focusPerDay, skipped),
        focusBySubject = subjects,
        screenByDay = screen,
        topApps = apps,
        dailyGoalMinutes = goal
    )

    @Test
    fun `report always has the three sections the screen renders`() {
        val text = generator.report(stats())
        assertTrue(text.contains("Wins:"))
        assertTrue(text.contains("Watch out:"))
        assertTrue(text.contains("Next week:"))
    }

    @Test
    fun `exactly one suggestion is offered`() {
        // A report ending in five things to fix gets none of them done.
        val nextWeek = generator.report(stats(focusPerDay = listOf(0, 0, 0, 30, 0, 0, 10)))
            .substringAfter("Next week:")
        assertEquals(1, nextWeek.trim().lines().size)
    }

    @Test
    fun `counts goal days and totals the week correctly`() {
        // 3 days at or above the 120-minute goal, 10 h 30 m total.
        val text = generator.report(
            stats(focusPerDay = listOf(120, 60, 200, 0, 130, 90, 30), goal = 120)
        )
        assertTrue(text.contains("3 days"))
        assertTrue(text.contains("10 h 30 m"))
    }

    @Test
    fun `names the best day`() {
        val text = generator.report(stats(focusPerDay = listOf(10, 10, 240, 10, 10, 10, 10)))
        assertTrue(text.contains("Wednesday"))
        assertTrue(text.contains("4 h"))
    }

    @Test
    fun `a week of pushups and no focus is not called empty`() {
        val text = generator.report(
            WeekStats(
                days = week(List(7) { 0 }).map { it.copy(pushupCount = 1) },
                focusBySubject = emptyList(),
                dailyGoalMinutes = 120
            )
        )
        assertFalse(text.contains("Nothing logged this week"))
        assertTrue(text.contains("Pushups verified on 7 days"))
    }

    @Test
    fun `watch out never runs past three points`() {
        // Every check trips at once; the section must still be readable.
        val text = generator.report(
            stats(
                focusPerDay = List(7) { 10 },
                subjects = listOf(SubjectMinutes(null, 70)),
                screen = List(7) { DayUsage(monday + it, 400) },
                apps = listOf(AppUsage("com.x", "YouTube", 200))
            )
        )
        val watchOut = text.substringAfter("Watch out:").substringBefore("Next week:")
        assertTrue(watchOut.trim().lines().size <= 3)
    }

    @Test
    fun `an empty week says so instead of inventing a win`() {
        val text = generator.report(stats(focusPerDay = List(7) { 0 }, subjects = emptyList()))
        assertTrue(text.contains("Nothing logged this week"))
        assertTrue(text.contains("one 25-minute block"))
    }

    @Test
    fun `consecutive dead days are surfaced but scattered ones are not`() {
        val run = generator.report(stats(focusPerDay = listOf(130, 0, 0, 0, 130, 130, 130)))
        assertTrue(run.contains("3 days in a row"))

        val scattered = generator.report(stats(focusPerDay = listOf(0, 130, 0, 130, 0, 130, 130)))
        assertFalse(scattered.contains("in a row"))
    }

    @Test
    fun `a deliberately skipped day does not count as a dead day`() {
        // Skipping is a decision, not a lapse — it must not read as a gap.
        val text = generator.report(
            stats(focusPerDay = listOf(130, 0, 0, 130, 130, 130, 130), skipped = setOf(1, 2))
        )
        assertFalse(text.contains("in a row"))
    }

    @Test
    fun `flags screen time that dwarfs focus time`() {
        val text = generator.report(
            stats(
                focusPerDay = List(7) { 30 },
                screen = List(7) { DayUsage(monday + it, 300) }
            )
        )
        assertTrue(text.contains("more than double"))
    }

    @Test
    fun `suggests dropping an unreachable goal to something hittable`() {
        val text = generator.report(stats(focusPerDay = List(7) { 40 }, goal = 240))
            .substringAfter("Next week:")
        // Averaging 40 min/day, so the replacement goal is the next 15-minute
        // step up from that — reachable — not the 240 being missed every day.
        assertTrue(text.contains("Drop the goal to 45 minutes"))
    }

    @Test
    fun `a strong week is told to raise the bar, not to fix something`() {
        val text = generator.report(stats(focusPerDay = List(7) { 150 }, goal = 120))
        assertTrue(text.substringAfter("Next week:").contains("Raise the goal to 150"))
        assertTrue(text.contains("Nothing stands out"))
    }

    @Test
    fun `calls out a subject that got no time at all`() {
        val text = generator.report(
            stats(
                subjects = listOf(SubjectMinutes("Math", 600), SubjectMinutes("Physics", 0))
            )
        )
        assertTrue(text.substringAfter("Next week:").contains("Physics"))
    }

    @Test
    fun `durations read as hours and minutes, never raw minutes`() {
        val text = generator.report(stats(focusPerDay = listOf(45, 0, 0, 0, 0, 0, 0)))
        assertTrue(text.contains("45 min"))
        assertFalse(text.contains("45 minutes of focused"))
    }
}
