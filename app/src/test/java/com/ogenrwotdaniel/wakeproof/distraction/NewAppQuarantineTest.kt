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
    fun `a reinstall is a changed install time, whatever the clock says`() {
        val recorded = mapOf("reinstalled" to 100 * day, "re.enabled" to 5 * day, "future" to now + 365 * day)
        val current = mapOf(
            "reinstalled" to 200 * day, "re.enabled" to 5 * day,
            "future" to now + 365 * day, "unrecorded" to now
        )
        val all = setOf("reinstalled", "re.enabled", "future", "unrecorded")
        assertEquals(setOf("reinstalled"), reinstalledPackages(all, recorded, current))
    }

    @Test
    fun `a held default home gets its own wall first, except in a session`() {
        fun verdict(session: Boolean) = gateVerdict(
            essential = true, focusSessionActive = session, onFocusList = false, capSpent = false,
            gated = false, sleeping = true, quarantined = true, clockUntrusted = true
        )
        assertEquals(GateVerdict.QUARANTINE_WALL, verdict(session = false))
        assertEquals(GateVerdict.ALLOW, verdict(session = true))
    }

    @Test
    fun `a stamp waiting for a trusted clock still holds the app`() {
        assertTrue("new" in quarantinedPackages(mapOf("new" to NewAppQuarantine.PENDING), now))
    }

    @Test
    fun `stamps survive the round trip and garbage is dropped`() {
        val map = mapOf("a.b" to 5L, "c.d" to 0L)
        assertEquals(map, decodeFirstSeen(encodeFirstSeen(map)))
        assertEquals(mapOf("a.b" to 5L), decodeFirstSeen(setOf("a.b|5", "broken", "x|y", "|3")))
    }

    @Test
    fun `a held app is walled whatever it is, but a session's sleep and focus walls say so first`() {
        fun verdict(session: Boolean = false, listed: Boolean = false) = gateVerdict(
            essential = false, focusSessionActive = session, onFocusList = listed,
            capSpent = false, gated = false, quarantined = true
        )
        assertEquals(GateVerdict.QUARANTINE_WALL, verdict())
        assertEquals(GateVerdict.QUARANTINE_WALL, verdict(session = true, listed = true))
        assertEquals(GateVerdict.FOCUS_WALL, verdict(session = true))
    }
}
