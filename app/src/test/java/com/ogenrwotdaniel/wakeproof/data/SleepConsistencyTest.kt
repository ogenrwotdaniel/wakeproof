package com.ogenrwotdaniel.wakeproof.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepConsistencyTest {

    private val min = 60_000L
    private val start = 1_000 * min
    private val end = start + 420 * min
    private fun on(at: Long) = ScreenEvent(start + at * min, true)
    private fun off(at: Long) = ScreenEvent(start + at * min, false)

    @Test
    fun `a screen off before the night and never on again is zero`() {
        assertEquals(0L, screenOnMillis(listOf(on(-60), off(-30)), start, end))
    }

    @Test
    fun `a screen still on at the night's start counts until it goes off`() {
        assertEquals(25 * min, screenOnMillis(listOf(on(-10), off(25)), start, end))
    }

    @Test
    fun `every spell inside the night is added up`() {
        val events = listOf(off(-5), on(10), off(14), on(200), off(203))
        assertEquals(7 * min, screenOnMillis(events, start, end))
    }

    @Test
    fun `a screen left on runs to the night's end, not past it`() {
        assertEquals(20 * min, screenOnMillis(listOf(off(-5), on(400), off(500)), start, end))
    }

    @Test
    fun `with nothing before the night, the first event inside says how it began`() {
        // It turns off at 30: it must have been on from the start.
        assertEquals(30 * min, screenOnMillis(listOf(off(30)), start, end))
        assertEquals(5 * min, screenOnMillis(listOf(on(30), off(35)), start, end))
    }

    @Test
    fun `no events at all is not measured`() {
        assertNull(screenOnMillis(emptyList(), start, end))
        // Only events after the night say nothing about it.
        assertNull(screenOnMillis(listOf(on(500)), start, end))
    }

    @Test
    fun `a few minutes of screen is still on time, more is not, unmeasured is neither`() {
        assertTrue(nightOnTime(NightScreen(1, 0)))
        assertTrue(nightOnTime(NightScreen(1, SLEEP_GRACE_MINUTES)))
        assertFalse(nightOnTime(NightScreen(1, SLEEP_GRACE_MINUTES + 1)))
        assertFalse(nightOnTime(NightScreen(1, null)))
        val score = sleepScore(listOf(NightScreen(1, 0), NightScreen(2, 40), NightScreen(3, null), NightScreen(4, 3)))
        assertEquals(SleepScore(onTime = 2, measured = 3), score)
        assertEquals(66, score.percent)
        assertEquals(0, sleepScore(emptyList()).percent)
    }
}
