package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAppsTest {

    @Test
    fun `a real reading always wins`() {
        assertEquals("new", heldOver("new", "old", blankForMillis = MAX_HOLD_MILLIS))
    }

    @Test
    fun `a blank reading keeps the last value for a while`() {
        assertEquals("old", heldOver(null, "old", blankForMillis = 0L))
        assertEquals("old", heldOver(null, "old", blankForMillis = MAX_HOLD_MILLIS - 1))
    }

    @Test
    fun `a role really cleared lets go in the end`() {
        assertNull(heldOver(null, "old", blankForMillis = MAX_HOLD_MILLIS))
    }

    @Test
    fun `nothing to keep stays nothing`() {
        assertNull(heldOver<String>(null, null, blankForMillis = 0L))
    }

    @Test
    fun `a blank run grows while seen and ends at a real reading`() {
        assertEquals(0L, blankRun(null, run = null, lastReadAt = 50L, now = 100L))
        assertEquals(30L, blankRun(null, run = 15L, lastReadAt = 115L, now = 130L))
        assertNull(blankRun("dialer", run = 15L, lastReadAt = 115L, now = 130L))
    }

    @Test
    fun `two hiccups a long way apart do not use up the hold`() {
        val run = blankRun(null, run = 0L, lastReadAt = 0L, now = 10 * MAX_READ_GAP_MILLIS)
        assertEquals(0L, run)
        assertEquals("dialer", heldOver(null, "dialer", run!!))
    }

    @Test
    fun `a role really cleared runs out across short looks at the phone`() {
        var run: Long? = 0L
        var at = 0L
        repeat(5) {
            // 15 s of reads, then the screen off for far longer than the gap.
            run = blankRun(null, run, at, at + 15_000L)
            at += 15_000L + 10 * MAX_READ_GAP_MILLIS
            run = blankRun(null, run, at - 10 * MAX_READ_GAP_MILLIS, at)
        }
        assertNull(heldOver(null, "dialer", run!!))
    }

    @Test
    fun `blanks are only waited out just after boot`() {
        assertEquals(5, firstReadAttempts(sinceBootMillis = 30_000L))
        assertEquals(2, firstReadAttempts(sinceBootMillis = 60 * 60_000L))
    }

    private fun apps(homes: Set<String> = setOf("home"), dialer: String? = "dialer", sms: String? = "sms") =
        DefaultApps(emptySet(), homes, dialer, sms)

    @Test
    fun `a phone's first reading needs its home, dialer and SMS app`() {
        assertTrue(firstReadingReady(apps(), telephony = true))
        assertFalse(firstReadingReady(apps(homes = emptySet()), telephony = true))
        assertFalse(firstReadingReady(apps(dialer = null), telephony = true))
        assertFalse(firstReadingReady(apps(sms = null), telephony = true))
    }

    @Test
    fun `a tablet's first reading needs only its home`() {
        assertTrue(firstReadingReady(apps(dialer = null, sms = null), telephony = false))
        assertFalse(firstReadingReady(apps(homes = emptySet(), dialer = null, sms = null), telephony = false))
    }
}
