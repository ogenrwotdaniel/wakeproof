package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAppQuarantineTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test
    fun `an app installed this week is held, one from before is not`() {
        val seen = mapOf("new.app" to now - 2 * day, "old.app" to now - 8 * day)
        assertEquals(setOf("new.app"), quarantinedPackages(seen, now))
    }

    @Test
    fun `the week ends exactly on time`() {
        assertTrue("new" in quarantinedPackages(mapOf("new" to now - 7 * day + 1), now))
        assertFalse("new" in quarantinedPackages(mapOf("new" to now - 7 * day), now))
    }

    @Test
    fun `apps there before WakeProof looked are never held`() {
        assertTrue(quarantinedPackages(mapOf("old" to 0L), now).isEmpty())
    }

    @Test
    fun `winding the clock back does not shorten the week`() {
        assertTrue("new" in quarantinedPackages(mapOf("new" to now + day), now))
    }

    @Test
    fun `stamps survive the round trip and garbage is dropped`() {
        val map = mapOf("a.b" to 5L, "c.d" to 0L)
        assertEquals(map, decodeFirstSeen(encodeFirstSeen(map)))
        assertEquals(mapOf("a.b" to 5L), decodeFirstSeen(setOf("a.b|5", "broken", "x|y", "|3")))
    }

    @Test
    fun `a new app is held whatever its verdict, unless it is never suspended`() {
        assertTrue(holdSuspended(neverSuspend = false, verdict = GateVerdict.ALLOW, forced = true))
        assertFalse(holdSuspended(neverSuspend = true, verdict = GateVerdict.ALLOW, forced = true))
    }
}
