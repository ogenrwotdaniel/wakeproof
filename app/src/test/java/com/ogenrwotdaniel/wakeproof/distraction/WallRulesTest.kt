package com.ogenrwotdaniel.wakeproof.distraction

import android.content.pm.ApplicationInfo
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallRulesTest {

    private fun fmt(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    @Test
    fun `a stored value counts only when it is stricter than the fixed one`() {
        val bedtime = WallRule.BEDTIME_START_MINUTES
        assertEquals(16 * 60, bedtime.fixedOrStricter(null))
        assertEquals(16 * 60, bedtime.fixedOrStricter(21 * 60))
        assertEquals(16 * 60, bedtime.fixedOrStricter(16 * 60))
        assertEquals(16 * 60, bedtime.fixedOrStricter(RULE_OFF_MINUTES))
        assertEquals(14 * 60, bedtime.fixedOrStricter(14 * 60))
        val start = WallRule.OPEN_WINDOW_START_MINUTES
        assertEquals(14 * 60, start.fixedOrStricter(8 * 60 + 10))
        assertEquals(14 * 60, start.fixedOrStricter(RULE_OFF_MINUTES))
        assertEquals(14 * 60 + 30, start.fixedOrStricter(14 * 60 + 30))
        val end = WallRule.OPEN_WINDOW_END_MINUTES
        assertEquals(16 * 60, end.fixedOrStricter(17 * 60))
        assertEquals(15 * 60 + 30, end.fixedOrStricter(15 * 60 + 30))
        assertEquals(RULE_ON, WallRule.WEEKEND_SHUT.fixedOrStricter(RULE_OFF))
        assertEquals(RULE_ON, WallRule.GATE_BROWSERS.fixedOrStricter(RULE_OFF))
    }

    @Test
    fun `a stored window the bedtime wall or new start now covers falls back to the fixed one`() {
        val rules = fixedRules { if (it == WallRule.OPEN_WINDOW_END_MINUTES) 8 * 60 + 50 else null }
        assertEquals(14 * 60 to 16 * 60, gatedOpenWindow(rules))
        val kept = fixedRules { if (it == WallRule.OPEN_WINDOW_END_MINUTES) 15 * 60 + 30 else null }
        assertEquals(14 * 60 to 15 * 60 + 30, gatedOpenWindow(kept))
    }

    @Test
    fun `gated apps open for the whole weekend window, which ends as bedtime starts`() {
        assertEquals(14 * 60 to 16 * 60, gatedOpenWindow(WallRules()))
        // An earlier window that starts before BEDTIME_END_MINUTES waits for it.
        val early = WallRules(mapOf(WallRule.OPEN_WINDOW_START_MINUTES to 8 * 60))
        assertEquals(8 * 60 + 20 to 16 * 60, gatedOpenWindow(early))
        val noBedtime = WallRules(mapOf(WallRule.BEDTIME_START_MINUTES to RULE_OFF_MINUTES))
        assertEquals(14 * 60 to 16 * 60, gatedOpenWindow(noBedtime))
        assertNull(gatedOpenWindow(WallRules(mapOf(WallRule.OPEN_WINDOW_START_MINUTES to RULE_OFF_MINUTES))))
    }

    @Test
    fun `every rule defaults to on, with Mon-Fri shut, 16 00 bedtime and 14 00 to 16 00 weekend window`() {
        val rules = WallRules()
        assertTrue(rules.gateBrowsers && rules.weekendShut && rules.sessionDnd)
        assertEquals(16 * 60, rules.bedtimeStartMinutes)
        assertEquals(14 * 60, rules.openWindowStartMinutes)
        assertEquals(16 * 60, rules.openWindowEndMinutes)
    }

    @Test
    fun `bedtime runs from 16 00 to 08 20, whatever the alarms did`() {
        val start = 16 * 60
        assertFalse(bedtimeActive(15 * 60 + 59, start))
        assertTrue(bedtimeActive(16 * 60, start))
        assertTrue(bedtimeActive(23 * 60, start))
        assertTrue(bedtimeActive(6 * 60 + 30, start))
        assertTrue(bedtimeActive(8 * 60 + 19, start))
        assertFalse(bedtimeActive(8 * 60 + 20, start))
        assertFalse(bedtimeActive(14 * 60, start))
        assertFalse(bedtimeActive(23 * 60, RULE_OFF_MINUTES))
    }

    @Test
    fun `browsers are gated only while the rule is on`() {
        val samsung = "com.sec.android.app.sbrowser"
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertTrue(AppGate.isGated(samsung, other, null, isBrowser = true, gateBrowsers = true))
        assertFalse(AppGate.isGated(samsung, other, null, isBrowser = true, gateBrowsers = false))
    }

    @Test
    fun `chrome is gated like every browser, and so are the sites saved from it as apps`() {
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertTrue(AppGate.isGated(AppGate.CHROME, other, null, isBrowser = true, gateBrowsers = true))
        assertTrue(AppGate.isGated("com.chrome.beta", other, null, isBrowser = true, gateBrowsers = true))
        // A shell answers for no web link, so only its name marks it.
        assertTrue(AppGate.isGated("org.chromium.webapk.a1b2c3", other, null, isBrowser = false, gateBrowsers = true))
        assertFalse(AppGate.isGated("org.chromium.webapk.a1b2c3", other, null, isBrowser = false, gateBrowsers = false))
    }

    @Test
    fun `gated apps open Saturday and Sunday 14 00 to 16 00 and stay shut Monday to Friday`() {
        val start = 14 * 60
        val end = 16 * 60
        for (day in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            assertFalse(weekendShutActive(day, weekendShut = true))
            assertTrue(windowShutActive(day, 13 * 60 + 59, weekendShut = true, start, end))
            assertFalse(windowShutActive(day, 14 * 60, weekendShut = true, start, end))
            assertFalse(windowShutActive(day, 15 * 60 + 59, weekendShut = true, start, end))
            assertTrue(windowShutActive(day, 16 * 60, weekendShut = true, start, end))
        }
        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            assertTrue(weekendShutActive(day, weekendShut = true))
            // Even right inside 14:00–16:00, Monday–Friday stays completely shut.
            assertTrue(windowShutActive(day, 15 * 60, weekendShut = true, start, end))
            assertTrue(windowShutActive(day, 0, weekendShut = true, start, end))
            assertTrue(windowShutActive(day, 23 * 60 + 59, weekendShut = true, start, end))
            // And even if the daily window were turned off, weekday shut still closes Mon–Fri.
            assertTrue(windowShutActive(day, 12 * 60, weekendShut = true, RULE_OFF_MINUTES, end))
            // Turning shut off restores the daily window on Mon–Fri.
            assertFalse(windowShutActive(day, 15 * 60, weekendShut = false, start, end))
        }
    }

    @Test
    fun `nextOpenAfterToday skips Monday through Friday when weekday shut is on`() {
        val start = 14 * 60
        val end = 16 * 60
        assertEquals(
            "Saturday at 14:00",
            nextOpenAfterToday(true, DayOfWeek.MONDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Saturday at 14:00",
            nextOpenAfterToday(true, DayOfWeek.WEDNESDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Saturday at 14:00",
            nextOpenAfterToday(true, DayOfWeek.THURSDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "tomorrow at 14:00",
            nextOpenAfterToday(true, DayOfWeek.FRIDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "tomorrow at 14:00",
            nextOpenAfterToday(true, DayOfWeek.SATURDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Saturday at 14:00",
            nextOpenAfterToday(true, DayOfWeek.SUNDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Saturday",
            nextOpenAfterToday(true, DayOfWeek.SUNDAY, weekendShut = true, RULE_OFF_MINUTES, end, ::fmt)
        )
        assertNull(nextOpenAfterToday(false, DayOfWeek.THURSDAY, weekendShut = true, start, end, ::fmt))
    }

    @Test
    fun `windowWallCopy explains Monday-to-Friday shutdown and Sunday post-window reopen`() {
        val start = 14 * 60
        val end = 16 * 60
        val (monTitle, monBody) = windowWallCopy(
            "Instagram", DayOfWeek.MONDAY, 14 * 60 + 20, weekendShut = true, start, end, ::fmt
        )
        assertEquals("Shut Monday to Friday", monTitle)
        assertTrue(monBody.contains("Saturday and Sunday between 14:00 and 16:00"))

        val (sunTitle, sunBody) = windowWallCopy(
            "Instagram", DayOfWeek.SUNDAY, 17 * 60, weekendShut = true, start, end, ::fmt
        )
        assertEquals("Not open now", sunTitle)
        assertTrue(sunBody.contains("Saturday at 14:00"))
    }

    @Test
    fun `the walls stand in front of a gated app inside the window and leave other apps alone`() {
        fun verdict(bedtime: Boolean = false) =
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, bedtime = bedtime
            )
        assertEquals(GateVerdict.ALLOW, verdict())
        assertEquals(GateVerdict.BEDTIME_WALL, verdict(bedtime = true))

        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = false, bedtime = true
            )
        )
    }
}

class OpenWindowTest {

    @Test
    fun `gated apps open only inside the configured window`() {
        val start = 8 * 60 + 10
        val end = 8 * 60 + 50
        assertTrue(outsideOpenWindow(8 * 60 + 9, start, end))
        assertFalse(outsideOpenWindow(8 * 60 + 10, start, end))
        assertFalse(outsideOpenWindow(8 * 60 + 49, start, end))
        assertTrue(outsideOpenWindow(8 * 60 + 50, start, end))
        assertTrue(outsideOpenWindow(3 * 60, start, end))
        assertFalse(outsideOpenWindow(3 * 60, RULE_OFF_MINUTES, end))
        // A window that ends before it starts is open at no time.
        assertTrue(outsideOpenWindow(15 * 60, 16 * 60, 15 * 60))
    }

    @Test
    fun `outside the window comes before bedtime`() {
        fun verdict(gated: Boolean, outsideWindow: Boolean = true) = gateVerdict(
            essential = false, focusSessionActive = false, onFocusList = false,
            capSpent = false, gated = gated,
            outsideWindow = outsideWindow, bedtime = true
        )
        assertEquals(GateVerdict.WINDOW_WALL, verdict(gated = true))
        assertEquals(GateVerdict.WINDOW_WALL, verdict(gated = false))
        assertEquals(GateVerdict.ALLOW, verdict(gated = false, outsideWindow = false))
    }

}
