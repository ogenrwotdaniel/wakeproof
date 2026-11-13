package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightRulesTest {

    private fun hm(h: Int, m: Int) = h * 60 + m

    @Test
    fun `the sleep wall runs 20 00 to 03 00 across midnight`() {
        assertFalse(sleepWallActive(hm(19, 59)))
        assertTrue(sleepWallActive(hm(20, 0)))
        assertTrue(sleepWallActive(hm(0, 30)))
        assertTrue(sleepWallActive(hm(2, 59)))
        assertFalse(sleepWallActive(hm(3, 0)))
        assertFalse(sleepWallActive(hm(12, 0)))
    }

    @Test
    fun `the phone's night wall runs 18 00 to 08 00 and the tablet keeps the sleep wall's hours`() {
        assertFalse(nightWallActive(hm(17, 59), phone = true))
        assertTrue(nightWallActive(hm(18, 0), phone = true))
        assertTrue(nightWallActive(hm(0, 0), phone = true))
        assertTrue(nightWallActive(hm(7, 59), phone = true))
        assertFalse(nightWallActive(hm(8, 0), phone = true))
        assertFalse(nightWallActive(hm(12, 0), phone = true))
        // The tablet: exactly the sleep wall, at every minute of the day.
        for (minute in 0 until 24 * 60) {
            assertEquals(sleepWallActive(minute), nightWallActive(minute, phone = false))
        }
    }

    @Test
    fun `the phone's extra hours shut the app and leave its notifications`() {
        assertFalse(nightWallStrips(hm(18, 30)))
        assertTrue(nightWallStrips(hm(20, 0)))
        assertTrue(nightWallStrips(hm(2, 59)))
        assertFalse(nightWallStrips(hm(3, 0)))
        assertFalse(nightWallStrips(hm(7, 30)))
    }

    @Test
    fun `the night wall says go to bed only in bed hours`() {
        val clock = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
        val evening = nightWallCopy(hm(18, 30), phone = true, formatClock = clock)
        assertEquals("Phone shut for the night", evening.first)
        assertTrue(evening.second.contains("18:00") && evening.second.contains("08:00"))
        val bed = nightWallCopy(hm(22, 0), phone = true, formatClock = clock)
        assertEquals("Time to sleep", bed.first)
        assertTrue(bed.second.contains("08:00"))
        val tablet = nightWallCopy(hm(22, 0), phone = false, formatClock = clock)
        assertTrue(tablet.second.contains("03:00"))
        assertFalse(tablet.second.contains("tablet"))
    }

    @Test
    fun `quiet hours start with the wall at 20 00 and end at the last alarm`() {
        assertFalse(nightQuiet(hm(19, 59), lastSlotMinutes = hm(5, 40)))
        assertTrue(nightQuiet(hm(20, 0), lastSlotMinutes = hm(5, 40)))
        assertTrue(nightQuiet(hm(5, 39), lastSlotMinutes = hm(5, 40)))
        assertFalse(nightQuiet(hm(5, 40), lastSlotMinutes = hm(5, 40)))
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
        // Bed hours take no work: the session's own list is shut too.
        assertEquals(
            GateVerdict.SLEEP_WALL,
            gateVerdict(
                essential = false, focusSessionActive = true, onFocusList = true,
                capSpent = false, gated = false, sleeping = true, sleepKeepsSession = false
            )
        )
        assertTrue(bedHoursShutWork(hm(20, 0)))
        assertTrue(bedHoursShutWork(hm(2, 59)))
        assertFalse(bedHoursShutWork(hm(3, 0)))
        assertFalse(bedHoursShutWork(hm(18, 30)))
        // Nothing starts that would still be running at 20:00.
        assertFalse(sessionReachesBed(hm(18, 0), minutes = 120))
        assertTrue(sessionReachesBed(hm(18, 1), minutes = 120))
        assertTrue(sessionReachesBed(hm(19, 30), minutes = 180))
        assertTrue(sessionReachesBed(hm(21, 0), minutes = 0))
        assertFalse(sessionReachesBed(hm(4, 0), minutes = 180))
        assertEquals(GateVerdict.SLEEP_WALL, v(session = true, onList = false).let {
            // Off the list in a session: the focus wall would also stand; sleep comes first.
            it
        })
    }

    @Test
    fun `lights out once, from 20 00`() {
        assertTrue(lightsOutDue(hm(20, 0), sentOnDay = 9, today = 10))
        assertTrue(lightsOutDue(hm(20, 29), sentOnDay = 9, today = 10))
        assertFalse(lightsOutDue(hm(20, 30), sentOnDay = 9, today = 10))
        assertFalse(lightsOutDue(hm(19, 59), sentOnDay = 9, today = 10))
        assertFalse(lightsOutDue(hm(20, 5), sentOnDay = 10, today = 10))
    }

    @Test
    fun `charging nag at night, low, unplugged, at most every half hour`() {
        val now = 10_000_000L
        assertTrue(chargeNagDue(hm(22, 0), 30, charging = false, lastNagAt = 0L, now = now))
        assertTrue(chargeNagDue(hm(20, 30), 30, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(20, 0), 30, charging = false, lastNagAt = 0L, now = now))
        assertTrue(chargeNagDue(hm(2, 59), 30, charging = false, lastNagAt = 0L, now = now))
        // Between the two locked alarms the phone must stay on too.
        assertTrue(chargeNagDue(hm(5, 0), 30, false, lastNagAt = 0L, now = now, lastSlotMinutes = hm(5, 40)))
        assertFalse(chargeNagDue(hm(5, 40), 30, false, lastNagAt = 0L, now = now, lastSlotMinutes = hm(5, 40)))
        assertTrue(chargeNagDue(hm(1, 0), 30, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), 30, charging = true, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), 40, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(15, 0), 10, charging = false, lastNagAt = 0L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), 10, charging = false, lastNagAt = now - 60_000L, now = now))
        assertFalse(chargeNagDue(hm(22, 0), -1, charging = false, lastNagAt = 0L, now = now))
    }
}
