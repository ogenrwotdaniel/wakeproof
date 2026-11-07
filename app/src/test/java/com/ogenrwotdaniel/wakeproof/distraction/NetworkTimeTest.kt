package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTimeTest {

    private val now = 1_000 * CLOCK_TOLERANCE_MILLIS

    @Test
    fun `a clock set by hand is never trusted`() {
        assertTrue(clockUntrusted(automatic = false, wallMillis = now, networkMillis = now))
        assertFalse(clockProven(automatic = false, wallMillis = now, networkMillis = now))
    }

    @Test
    fun `the automatic flag alone does not vouch for a clock set offline`() {
        val moved = now + 8 * 24 * 60 * 60_000L
        assertTrue(clockUntrusted(automatic = true, wallMillis = moved, networkMillis = now))
        assertTrue(clockUntrusted(automatic = true, wallMillis = now - CLOCK_TOLERANCE_MILLIS - 1, networkMillis = now))
        assertFalse(clockProven(automatic = true, wallMillis = moved, networkMillis = now))
    }

    @Test
    fun `small drift is tolerated`() {
        assertFalse(clockUntrusted(automatic = true, wallMillis = now + CLOCK_TOLERANCE_MILLIS, networkMillis = now))
        assertTrue(clockProven(automatic = true, wallMillis = now + CLOCK_TOLERANCE_MILLIS, networkMillis = now))
    }

    @Test
    fun `with no network time heard the clock is believed but not proven`() {
        assertFalse(clockUntrusted(automatic = true, wallMillis = now, networkMillis = null))
        assertFalse(clockProven(automatic = true, wallMillis = now, networkMillis = null))
    }
}
