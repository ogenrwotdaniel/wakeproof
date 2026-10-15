package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseAlertsTest {

    @Test
    fun `back to focus buzzes for about thirty seconds`() {
        val length = BuzzPattern.lengthOf(BuzzPattern.INTO_FOCUS)
        assertTrue("was $length", length in 28_000..30_000)
    }

    @Test
    fun `into a break buzzes for about ten seconds`() {
        val length = BuzzPattern.lengthOf(BuzzPattern.INTO_BREAK)
        assertTrue("was $length", length in 9_000..10_000)
    }

    @Test
    fun `focus buzz is mostly motor-on`() {
        // Odd indices are pulses. "Stronger" means close to continuous.
        val p = BuzzPattern.INTO_FOCUS
        val on = p.filterIndexed { i, _ -> i % 2 == 1 }.sum()
        assertTrue(on * 10 >= BuzzPattern.lengthOf(p) * 7)
    }

    @Test
    fun `pulses start immediately and end on a pulse`() {
        val p = BuzzPattern.pulses(totalMillis = 3_000, onMillis = 1_000, offMillis = 500)
        assertEquals(listOf(0L, 1_000L, 500L, 1_000L), p.toList())
        assertEquals(0, p.size % 2) // delay, pulse, gap, pulse: ends on a pulse
    }

    @Test
    fun `every waveform has a pulse in each odd slot`() {
        for (p in listOf(BuzzPattern.INTO_FOCUS, BuzzPattern.INTO_BREAK, BuzzPattern.SESSION_DONE, BuzzPattern.TAP)) {
            assertEquals(0L, p[0])
            assertEquals(0, p.size % 2)
            assertTrue(p.filterIndexed { i, _ -> i % 2 == 1 }.all { it > 0 })
        }
    }

    @Test
    fun `goal is crossed exactly once`() {
        assertTrue(crossedGoal(before = 350, added = 25, goal = 360))
        assertTrue(crossedGoal(before = 335, added = 25, goal = 360)) // lands exactly on it
        assertFalse(crossedGoal(before = 360, added = 25, goal = 360)) // already there
        assertFalse(crossedGoal(before = 300, added = 25, goal = 360)) // not yet
        assertFalse(crossedGoal(before = 0, added = 25, goal = 0)) // no goal set
    }

    @Test
    fun `summary reads blocks, minutes and the day against the goal`() {
        assertEquals(
            "4 blocks · 1h 40m focused\nToday 3h 20m of 6h",
            sessionSummary(4, 100, FocusDay(minutes = 200, goal = 360))
        )
        assertEquals(
            "1 block · 25m focused\nToday 6h 5m of 6h ✓",
            sessionSummary(1, 25, FocusDay(minutes = 365, goal = 360))
        )
        assertEquals("2 blocks · 50m focused\nToday 50m", sessionSummary(2, 50, FocusDay(50, 0)))
        assertEquals("2 blocks · 50m focused", sessionSummary(2, 50, null))
    }
}
