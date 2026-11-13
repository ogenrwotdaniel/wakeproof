package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryHoldTest {

    @Test
    fun `a summary owed on the phone holds watch starts until its end and no longer`() {
        val now = 1_000_000_000L
        assertTrue(summaryHolds(untilMillis = now + 60_000L, now = now))
        assertTrue(summaryHolds(untilMillis = now + SUMMARY_HOLD_MAX_MILLIS, now = now))
        // Nothing owed, lapsed, or an end no hold could have: a wrong clock must not lock the watch.
        assertFalse(summaryHolds(untilMillis = 0L, now = now))
        assertFalse(summaryHolds(untilMillis = now, now = now))
        assertFalse(summaryHolds(untilMillis = now - 1L, now = now))
        assertFalse(summaryHolds(untilMillis = now + SUMMARY_HOLD_MAX_MILLIS + 1L, now = now))
    }

    @Test
    fun `bed hours run 20 00 to 03 00`() {
        assertFalse(bedHours(19 * 60 + 59))
        assertTrue(bedHours(20 * 60))
        assertTrue(bedHours(0))
        assertTrue(bedHours(2 * 60 + 59))
        assertFalse(bedHours(3 * 60))
    }
}
