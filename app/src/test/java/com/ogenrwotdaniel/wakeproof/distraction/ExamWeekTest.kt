package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a begun exam is held after the grace, and only a proven clock drops a lapsed hold`() {
        val today = 20_000L
        val t0 = 1_000_000_000L
        val later = t0 + EXAM_HOLD_GRACE_MILLIS
        val rows = listOf(
            ExamRow("a", "Calculus", today + 3), ExamRow("b", "Physics", today + 5), ExamRow("c", "Later", today + 9)
        )
        val first = heldExamsAfter(emptySet(), emptyMap(), rows, today, clockProven = false, nowMillis = t0)
        // First sight: nothing held yet, so a mistyped date can still be fixed.
        assertEquals(emptySet<ExamWeek>(), first.held)
        assertEquals(mapOf("a" to t0, "b" to t0), first.seen)
        // A second read seconds later (a service restart) does not cut the grace short.
        assertEquals(emptySet<ExamWeek>(), heldExamsAfter(first.held, first.seen, rows, today, false, t0 + 5_000).held)
        val second = heldExamsAfter(first.held, first.seen, rows, today, clockProven = false, nowMillis = later)
        assertEquals(setOf(ExamWeek("Calculus", today + 3), ExamWeek("Physics", today + 5)), second.held)
        // Renamed and re-dated within the grace: the same row keeps its first sighting.
        val moved = heldExamsAfter(first.held, first.seen, listOf(ExamRow("a", "Calc", today + 4)), today, false, later)
        assertEquals(setOf(ExamWeek("Calc", today + 4)), moved.held)
        // Fixed within the grace: the wrong date is never held.
        val fixed = heldExamsAfter(first.held, first.seen, listOf(ExamRow("a", "Calculus", today + 30)), today, false, later)
        assertEquals(emptySet<ExamWeek>(), fixed.held)
        // A new mistyped exam during a held week waits for its own grace.
        val typo = heldExamsAfter(second.held, second.seen, rows + ExamRow("d", "Typo", today + 6), today, false, later)
        assertEquals(second.held, typo.held)
        // An exam saved long before its week began is held on its first sighting there.
        val old = listOf(ExamRow("o", "Planned", today + 6, updatedAtMillis = t0 - 14 * 86_400_000L))
        assertEquals(setOf(ExamWeek("Planned", today + 6)), heldExamsAfter(emptySet(), emptyMap(), old, today, false, t0).held)
        // A clock set back before the first sighting holds at once.
        assertEquals(
            setOf(ExamWeek("Calculus", today + 3), ExamWeek("Physics", today + 5)),
            heldExamsAfter(first.held, first.seen, rows, today, false, t0 - 60_000).held
        )
        // Rows from before sync (blank ids) are told apart by name and day.
        val legacy = listOf(ExamRow("", "Old A", today + 2), ExamRow("", "Old B", today + 4))
        val legacySeen = heldExamsAfter(emptySet(), emptyMap(), legacy.take(1), today, false, t0).seen
        assertEquals(
            setOf(ExamWeek("Old A", today + 2)),
            heldExamsAfter(emptySet(), legacySeen, legacy, today, false, later).held
        )
        // Both deleted after holding: the wall lasts until the later one, and says so.
        assertEquals(ExamWeek("Physics", today + 5), examWeekHeld(emptyList(), second.held, today + 4))
        // A far hold from a clock set forward does not crowd out a real one.
        val far = setOf(ExamWeek("Far", today + 30))
        assertEquals(
            far + ExamWeek("Real", today + 3),
            heldExamsAfter(far, mapOf("r" to t0), listOf(ExamRow("r", "Real", today + 3)), today, false, later).held
        )
        // Lapsed holds stay on a doubted clock and go on a proven one.
        val lapsed = setOf(ExamWeek("Old", today - 1))
        assertEquals(lapsed, heldExamsAfter(lapsed, emptyMap(), emptyList(), today, false, t0).held)
        assertEquals(emptySet<ExamWeek>(), heldExamsAfter(lapsed, emptyMap(), emptyList(), today, true, t0).held)
        // A doubted clock keeps only the latest past holds, however far back.
        val many = (1..MAX_PAST_HOLDS + 5).map { ExamWeek("E$it", today - it) }.toSet()
        val kept = heldExamsAfter(many, emptyMap(), emptyList(), today, false, t0).held
        assertEquals(MAX_PAST_HOLDS, kept.size)
        assertEquals(today - 1, kept.maxOf { it.epochDay })
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
}
