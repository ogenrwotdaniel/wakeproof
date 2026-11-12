package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayLineTest {

    private fun stats(plan: Int, planTarget: Int, review: Int, reviewTarget: Int) = WearStats(
        planCount = plan, planTarget = planTarget, shutdownCount = review, shutdownTarget = reviewTarget
    )

    @Test
    fun `a day with neither alarm has no line`() {
        assertNull(dayLine(stats(0, 0, 0, 0)))
        // A photo taken with no alarm asking is not worth a line either.
        assertNull(dayLine(stats(1, 0, 0, 0)))
    }

    @Test
    fun `the plan is asked for first, then the evening review`() {
        assertEquals("Day plan pending", dayLine(stats(0, 1, 0, 1)))
        assertEquals("Plan ✓ · review due", dayLine(stats(1, 1, 0, 1)))
        assertEquals("Plan ✓ · review ✓", dayLine(stats(1, 1, 1, 1)))
        assertFalse(dayLineDone(stats(1, 1, 0, 1)))
        assertTrue(dayLineDone(stats(1, 1, 1, 1)))
    }

    @Test
    fun `one alarm alone reads as before`() {
        assertEquals("Day plan ✓", dayLine(stats(1, 1, 0, 0)))
        assertEquals("Evening review due", dayLine(stats(0, 0, 0, 1)))
        assertEquals("Evening review ✓", dayLine(stats(0, 0, 1, 1)))
        // A phone on an older build sends no review keys: the plan line alone.
        assertTrue(dayLineDone(stats(1, 1, 0, 0)))
    }
}
