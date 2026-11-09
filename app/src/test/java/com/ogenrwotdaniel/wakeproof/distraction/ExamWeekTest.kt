package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamWeekTest {
    @Test
    fun `exam week covers the seven days up to and including the exam`() {
        val today = 20_000L
        assertEquals(ExamWeek("Calculus", today), examWeekHeld(listOf("Calculus" to today), emptySet(), today))
        assertEquals(ExamWeek("Physics", today + 6), examWeekHeld(listOf("Physics" to today + 6), emptySet(), today))
        assertNull(examWeekHeld(listOf("Physics" to today + 7), emptySet(), today))
        assertNull(examWeekHeld(listOf("Past" to today - 1), emptySet(), today))
        assertNull(examWeekHeld(emptyList(), emptySet(), today))
    }

    @Test
    fun `a begun exam week holds when the exam is deleted or moved away, and lapses after its day`() {
        val today = 20_000L
        val held = setOf(ExamWeek("Calculus", today + 3))
        assertEquals(ExamWeek("Calculus", today + 3), examWeekHeld(emptyList(), held, today))
        assertEquals(ExamWeek("Calculus", today + 3), examWeekHeld(listOf("Calculus" to today + 30), held, today))
        // The wall names the last exam covering today: that is when it reopens.
        assertEquals(ExamWeek("Calculus", today + 3), examWeekHeld(listOf("Physics" to today + 1), held, today))
        assertNull(examWeekHeld(emptyList(), held, today + 4))
        assertNull(examWeekHeld(emptyList(), emptySet(), today))
    }

    @Test
    fun `an exam is held after its grace, from the day before its week`() {
        val today = 20_000L
        val t0 = 1_000_000_000L
        val later = t0 + EXAM_HOLD_GRACE_MILLIS
        fun after(
            held: Set<ExamWeek>, seen: Map<String, Long>, rows: List<ExamRow>, now: Long,
            doubted: Boolean = false, proven: Boolean = false, day: Long = today
        ) = heldExamsAfter(held, seen, rows, day, proven, now, doubted)
        val rows = listOf(
            ExamRow("a", "Calculus", today + 3), ExamRow("b", "Physics", today + 5), ExamRow("c", "Later", today + 9)
        )
        val first = after(emptySet(), emptyMap(), rows, t0)
        // First sight: nothing held yet, so a mistyped date can still be fixed. Every row is sighted.
        assertEquals(emptySet<ExamWeek>(), first.held)
        assertEquals(mapOf("a" to t0, "b" to t0, "c" to t0), first.seen)
        // A second read seconds later (a service restart) does not cut the grace short.
        assertEquals(emptySet<ExamWeek>(), after(first.held, first.seen, rows, t0 + 5_000).held)
        val second = after(first.held, first.seen, rows, later)
        assertEquals(setOf(ExamWeek("Calculus", today + 3), ExamWeek("Physics", today + 5)), second.held)
        // A planned exam is held the day before its week: renaming or deleting it at midnight is too late.
        val eve = after(second.held, second.seen, rows, later + 1, day = today + 2)
        assertTrue(ExamWeek("Later", today + 9) in eve.held)
        // Renamed and re-dated within the grace: the same row keeps its sighting.
        assertEquals(
            setOf(ExamWeek("Calc", today + 4)),
            after(first.held, first.seen, listOf(ExamRow("a", "Calc", today + 4)), later).held
        )
        // Fixed within the grace: the wrong date is never held.
        assertEquals(
            emptySet<ExamWeek>(),
            after(first.held, first.seen, listOf(ExamRow("a", "Calculus", today + 30)), later).held
        )
        // A new mistyped exam, even a week from tomorrow, waits for its own grace.
        val typo = after(second.held, second.seen, rows + ExamRow("d", "Typo", today + 7), later)
        assertEquals(second.held, typo.held)
        // Moved to a past date (no longer read) and back: its sighting stays, no fresh grace.
        val gone = after(emptySet(), first.seen, emptyList(), t0 + 60_000)
        assertEquals(t0, gone.seen.getValue("a"))
        assertTrue(ExamWeek("Calculus", today + 3) in after(emptySet(), gone.seen, rows, later).held)
        // Saved long ago: held on a first sighting in its week (a late sync, a reinstall),
        // even when saved inside the week; saved a minute ago, it waits for its grace.
        val weekAgo = t0 - 14 * 86_400_000L
        assertEquals(
            setOf(ExamWeek("Planned", today + 6)),
            after(emptySet(), emptyMap(), listOf(ExamRow("o", "Planned", today + 6, weekAgo)), t0).held
        )
        assertEquals(
            setOf(ExamWeek("Synced", today + 3)),
            after(emptySet(), emptyMap(), listOf(ExamRow("o", "Synced", today + 3, t0 - 2 * 86_400_000L)), t0).held
        )
        assertEquals(
            emptySet<ExamWeek>(),
            after(emptySet(), emptyMap(), listOf(ExamRow("o", "Typed", today + 6, t0 - 60_000)), t0).held
        )
        // A save a few seconds ahead of now (a clock correction) is not a clock set back.
        assertEquals(
            emptySet<ExamWeek>(),
            after(emptySet(), emptyMap(), listOf(ExamRow("o", "Typed", today + 6, t0 + 10_000)), t0).held
        )
        // Saved again after its sighting: the sighting still counts, no new grace.
        assertEquals(
            setOf(ExamWeek("Calculus", today + 3)),
            after(emptySet(), mapOf("a" to t0), listOf(ExamRow("a", "Calculus", today + 3, later - 1)), later).held
        )
        // A doubted clock, or one set back before the sighting, holds at once.
        val both = setOf(ExamWeek("Calculus", today + 3), ExamWeek("Physics", today + 5))
        assertEquals(both, after(emptySet(), emptyMap(), rows, t0, doubted = true).held)
        assertEquals(both, after(first.held, first.seen, rows, t0 - 60_000).held)
        // Rows from before sync (blank ids) are told apart by name and day.
        val legacy = listOf(ExamRow("", "Old A", today + 2), ExamRow("", "Old B", today + 4))
        val legacySeen = after(emptySet(), emptyMap(), legacy.take(1), t0).seen
        assertEquals(setOf(ExamWeek("Old A", today + 2)), after(emptySet(), legacySeen, legacy, later).held)
        // Both deleted after holding: the wall lasts until the later one, and says so.
        assertEquals(ExamWeek("Physics", today + 5), examWeekHeld(emptyList(), second.held, today + 4))
        // A far hold from a clock set forward does not crowd out a real one.
        val far = setOf(ExamWeek("Far", today + 30))
        assertEquals(
            far + ExamWeek("Real", today + 3),
            after(far, mapOf("r" to t0), listOf(ExamRow("r", "Real", today + 3)), later).held
        )
        // Lapsed holds stay on a doubted clock and go on a proven one.
        val lapsed = setOf(ExamWeek("Old", today - 1))
        assertEquals(lapsed, after(lapsed, emptyMap(), emptyList(), t0).held)
        assertEquals(emptySet<ExamWeek>(), after(lapsed, emptyMap(), emptyList(), t0, proven = true).held)
        // A doubted clock keeps only the latest past holds, however far back.
        val many = (1..MAX_PAST_HOLDS + 5).map { ExamWeek("E$it", today - it) }.toSet()
        val kept = after(many, emptyMap(), emptyList(), t0).held
        assertEquals(MAX_PAST_HOLDS, kept.size)
        assertEquals(today - 1, kept.maxOf { it.epochDay })
        // A sighting outlives its row however old it is; past the cap only unread rows' sightings go.
        assertEquals(mapOf("x" to t0), after(emptySet(), mapOf("x" to t0), emptyList(), t0 + 90L * 86_400_000L).seen)
        val crowd = (0 until MAX_SIGHTINGS + 5).associate { "gone$it" to t0 + it }
        val capped = after(emptySet(), crowd + ("a" to t0 - 1), listOf(ExamRow("a", "Calculus", today + 3)), later).seen
        assertEquals(MAX_SIGHTINGS, capped.size)
        assertEquals(t0 - 1, capped.getValue("a"))
        assertTrue("gone0" !in capped)
    }

    @Test
    fun `a first sighting reads back, and a damaged one is skipped`() {
        assertEquals("abc-1" to 123L, decodeSeen(encodeSeen("abc-1", 123L)))
        assertEquals("legacy:5|A@B" to 9L, decodeSeen(encodeSeen("legacy:5|A@B", 9L)))
        assertEquals(null, decodeSeen("nonsense"))
    }

    @Test
    fun `a stored hold reads back, and a damaged one is skipped`() {
        val exam = ExamWeek("Data | Structures", 20_003L)
        assertEquals(exam, decodeHeldExam(encodeHeldExam(exam)))
        assertNull(decodeHeldExam("garbage"))
        assertNull(decodeHeldExam("x|Name"))
    }

    @Test
    fun `the wall names the end of the unbroken run of exam weeks`() {
        val today = 20_000L
        assertEquals(
            "Physics",
            examWeekHeld(listOf("Physics" to today + 5, "Chemistry" to today + 2), emptySet(), today)?.name
        )
        // Biology's week starts the day after Chemistry: the wall lasts until Biology.
        assertEquals(
            ExamWeek("Biology", today + 9),
            examWeekHeld(listOf("Chemistry" to today + 2, "Biology" to today + 9), emptySet(), today)
        )
        // A gap of a free day breaks the run.
        assertEquals(
            ExamWeek("Chemistry", today + 2),
            examWeekHeld(listOf("Chemistry" to today + 2, "Later" to today + 10), emptySet(), today)
        )
    }

    @Test
    fun `a renamed exam is shown by its new name`() {
        val today = 20_000L
        assertEquals(
            ExamWeek("Calculus II", today + 3),
            examWeekHeld(listOf("Calculus II" to today + 3), setOf(ExamWeek("Calc", today + 3)), today)
        )
    }

    @Test
    fun `chrome has one row of hours, and its shells only lend it their notifications`() {
        assertEquals(CHROME_HOURS, appHoursFor(AppGate.CHROME))
        // A shell's notifications follow Chrome; the shell itself is judged as itself.
        assertNull(appHoursFor("org.chromium.webapk.a1b2c3"))
        assertEquals(CHROME_HOURS, notificationHoursFor("org.chromium.webapk.a1b2c3"))
        assertNull(appHoursFor("com.chrome.beta"))
        assertEquals(CHROME_DAILY_CAP_MINUTES, withFixedCaps(emptyMap())[AppGate.CHROME]?.minutes)
    }

    @Test
    fun `the clock is doubted for exam holds only after a run of doubted reads in one boot`() {
        val min = 60_000L
        // One doubted read begins a run and is not yet a doubt.
        val first = examClockDoubted(true, null, boot = 7, nowElapsed = 2 * min)
        assertEquals(false to ClockDoubt(7, 2 * min, 2 * min), first)
        // The next read, five minutes on: doubted. A restart seconds later is not.
        assertEquals(true to ClockDoubt(7, 2 * min, 7 * min), examClockDoubted(true, first.second, 7, 7 * min))
        assertEquals(false, examClockDoubted(true, first.second, 7, 2 * min + 5_000).first)
        // A trusted read clears the run.
        assertEquals(false to null, examClockDoubted(false, first.second, 7, 7 * min))
        // A run from another boot, or not seen for too long, begins again.
        assertEquals(false to ClockDoubt(8, 7 * min, 7 * min), examClockDoubted(true, first.second, 8, 7 * min))
        assertEquals(false, examClockDoubted(true, first.second, 7, 2 * min + EXAM_CLOCK_DOUBT_GAP_MILLIS + 1).first)
        assertEquals(false, examClockDoubted(true, ClockDoubt(7, 9 * min, 9 * min), 7, 3 * min).first)
        assertEquals(ClockDoubt(7, 120, 300), decodeClockDoubt("7|120|300"))
        assertNull(decodeClockDoubt("7|120"))
    }
}
