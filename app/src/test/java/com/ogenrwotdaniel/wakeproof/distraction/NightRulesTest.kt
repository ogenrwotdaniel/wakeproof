package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightRulesTest {

    private fun hm(h: Int, m: Int) = h * 60 + m

    @Test
    fun `the sleep wall runs 21 00 to 04 00 across midnight`() {
        assertFalse(sleepWallActive(hm(20, 59)))
        assertTrue(sleepWallActive(hm(21, 0)))
        assertTrue(sleepWallActive(hm(0, 30)))
        assertTrue(sleepWallActive(hm(3, 59)))
        assertFalse(sleepWallActive(hm(4, 0)))
        assertFalse(sleepWallActive(hm(12, 0)))
    }

    @Test
    fun `quiet hours start with the wall at 21 00 and end at the last alarm`() {
        assertFalse(nightQuiet(hm(20, 59), lastSlotMinutes = hm(6, 5)))
        assertTrue(nightQuiet(hm(21, 0), lastSlotMinutes = hm(6, 5)))
        assertTrue(nightQuiet(hm(6, 4), lastSlotMinutes = hm(6, 5)))
        assertFalse(nightQuiet(hm(6, 5), lastSlotMinutes = hm(6, 5)))
    }

    @Test
    fun `asleep walls every app but essentials, and a session keeps its list`() {
        fun v(essential: Boolean = false, session: Boolean = false, onList: Boolean = false, gated: Boolean = false) =
            gateVerdict(
                essential = essential, focusSessionActive = session, onFocusList = onList,
                capSpent = false, gated = gated, sleeping = true
            )
        assertEquals(GateVerdict.SLEEP_WALL, v())
        assertEquals(GateVerdict.SLEEP_WALL, v(gated = true))
        assertEquals(GateVerdict.ALLOW, v(essential = true))
        assertEquals(GateVerdict.ALLOW, v(session = true, onList = true))
        assertEquals(GateVerdict.SLEEP_WALL, v(session = true, onList = false).let {
            // Off the list in a session: the focus wall would also stand; sleep comes first.
            it
        })
    }

    @Test
    fun `wind-down once, in its half hour before the wall`() {
        assertTrue(windDownDue(hm(20, 30), sentOnDay = 9, today = 10))
        assertTrue(windDownDue(hm(20, 59), sentOnDay = 9, today = 10))
        assertFalse(windDownDue(hm(20, 45), sentOnDay = 10, today = 10))
        assertFalse(windDownDue(hm(20, 29), sentOnDay = 9, today = 10))
        assertFalse(windDownDue(hm(21, 0), sentOnDay = 9, today = 10))
    }

    @Test
    fun `lights out once, from 21 00`() {
        assertTrue(lightsOutDue(hm(21, 0), sentOnDay = 9, today = 10))
        assertTrue(lightsOutDue(hm(21, 29), sentOnDay = 9, today = 10))
        assertFalse(lightsOutDue(hm(21, 30), sentOnDay = 9, today = 10))
        assertFalse(lightsOutDue(hm(20, 59), sentOnDay = 9, today = 10))
        assertFalse(lightsOutDue(hm(21, 5), sentOnDay = 10, today = 10))
    }

    @Test
    fun `charging nag at night, low, unplugged, at most every half hour`() {
        val now = 10_000_000L
        assertTrue(chargeNagDue(hm(22, 0), 30, charging = false, lastNagAt = 0L, now = now))
        assertTrue(chargeNagDue(hm(21, 30), 30, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(21, 0), 30, charging = false, lastNagAt = 0L, now = now))
        assertTrue(chargeNagDue(hm(3, 59), 30, charging = false, lastNagAt = 0L, now = now))
        // Between the two locked alarms the phone must stay on too.
        assertTrue(chargeNagDue(hm(5, 0), 30, false, lastNagAt = 0L, now = now, lastSlotMinutes = hm(6, 5)))
        assertFalse(chargeNagDue(hm(6, 5), 30, false, lastNagAt = 0L, now = now, lastSlotMinutes = hm(6, 5)))
        assertTrue(chargeNagDue(hm(1, 0), 30, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), 30, charging = true, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), 40, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(15, 0), 10, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), 10, charging = false, lastNagAt = now - 60_000L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), -1, charging = false, lastNagAt = 0L, now = now))
    }
}
