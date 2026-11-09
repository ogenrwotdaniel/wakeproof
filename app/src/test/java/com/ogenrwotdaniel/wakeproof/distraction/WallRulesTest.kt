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
        assertEquals(16 * 60, bedtime.fixedOrStricter(RULE_OFF_MINUTES))
        assertEquals(14 * 60, bedtime.fixedOrStricter(14 * 60))
        val start = WallRule.OPEN_WINDOW_START_MINUTES
        assertEquals(8 * 60 + 10, start.fixedOrStricter(8 * 60))
        assertEquals(8 * 60 + 10, start.fixedOrStricter(RULE_OFF_MINUTES))
        assertEquals(8 * 60 + 20, start.fixedOrStricter(8 * 60 + 20))
        val end = WallRule.OPEN_WINDOW_END_MINUTES
        assertEquals(8 * 60 + 50, end.fixedOrStricter(10 * 60))
        assertEquals(8 * 60 + 45, end.fixedOrStricter(8 * 60 + 45))
        assertEquals(RULE_ON, WallRule.WEEKEND_SHUT.fixedOrStricter(RULE_OFF))
        assertEquals(RULE_ON, WallRule.GATE_BROWSERS.fixedOrStricter(RULE_OFF))
    }

    @Test
    fun `a stored window the bedtime wall now covers falls back to the fixed one`() {
        val rules = fixedRules { if (it == WallRule.OPEN_WINDOW_END_MINUTES) 8 * 60 + 30 else null }
        assertEquals(8 * 60 + 40 to 8 * 60 + 50, gatedOpenWindow(rules))
        val kept = fixedRules { if (it == WallRule.OPEN_WINDOW_END_MINUTES) 8 * 60 + 45 else null }
        assertEquals(8 * 60 + 40 to 8 * 60 + 45, gatedOpenWindow(kept))
    }

    @Test
    fun `gated apps really open from the end of bedtime, not the window's start`() {
        assertEquals(8 * 60 + 40 to 8 * 60 + 50, gatedOpenWindow(WallRules()))
        val noBedtime = WallRules(mapOf(WallRule.BEDTIME_START_MINUTES to RULE_OFF_MINUTES))
        assertEquals(8 * 60 + 10 to 8 * 60 + 50, gatedOpenWindow(noBedtime))
        assertNull(gatedOpenWindow(WallRules(mapOf(WallRule.OPEN_WINDOW_START_MINUTES to RULE_OFF_MINUTES))))
    }

    @Test
    fun `every rule defaults to on, with weekend shut, 16 00 bedtime and 08 10 to 08 50 window`() {
        val rules = WallRules()
        assertTrue(rules.gateBrowsers && rules.weekendShut && rules.sessionDnd)
        assertEquals(16 * 60, rules.bedtimeStartMinutes)
        assertEquals(8 * 60 + 10, rules.openWindowStartMinutes)
        assertEquals(8 * 60 + 50, rules.openWindowEndMinutes)
    }

    @Test
    fun `bedtime runs from 16 00 to 08 40, whatever the alarms did`() {
        val start = 16 * 60
        assertFalse(bedtimeActive(15 * 60 + 59, start))
        assertTrue(bedtimeActive(16 * 60, start))
        assertTrue(bedtimeActive(23 * 60, start))
        assertTrue(bedtimeActive(6 * 60 + 30, start))
        assertTrue(bedtimeActive(8 * 60 + 39, start))
        assertFalse(bedtimeActive(8 * 60 + 40, start))
        assertFalse(bedtimeActive(12 * 60, start))
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
    fun `chrome alone is never gated, but chrome beta is`() {
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertFalse(AppGate.isGated(AppGate.CHROME, other, null, isBrowser = true, gateBrowsers = true))
        assertTrue(AppGate.isGated("com.chrome.beta", other, null, isBrowser = true, gateBrowsers = true))
    }

    @Test
    fun `chrome opens 08 40 to 16 00 on weekdays and is shut otherwise`() {
        val mon = DayOfWeek.MONDAY
        assertTrue(chromeHoursShut(mon, 8 * 60 + 39))
        assertFalse(chromeHoursShut(mon, 8 * 60 + 40))
        assertFalse(chromeHoursShut(DayOfWeek.FRIDAY, 15 * 60 + 59))
        assertTrue(chromeHoursShut(mon, 16 * 60))
        assertTrue(chromeHoursShut(mon, 0))
        assertTrue(chromeHoursShut(DayOfWeek.SATURDAY, 10 * 60))
        assertTrue(chromeHoursShut(DayOfWeek.SUNDAY, 10 * 60))
    }

    @Test
    fun `chrome always carries an hour block cap, tighter if one was stored`() {
        val other = "com.example" to com.ogenrwotdaniel.wakeproof.data.AppLimit(30)
        assertEquals(
            com.ogenrwotdaniel.wakeproof.data.AppLimit(60, com.ogenrwotdaniel.wakeproof.data.LimitMode.BLOCK),
            withFixedCaps(mapOf(other))[AppGate.CHROME]
        )
        assertEquals(other.second, withFixedCaps(mapOf(other))["com.example"])
        val looser = AppGate.CHROME to com.ogenrwotdaniel.wakeproof.data.AppLimit(120)
        assertEquals(60, withFixedCaps(mapOf(looser))[AppGate.CHROME]?.minutes)
        val tighter = AppGate.CHROME to com.ogenrwotdaniel.wakeproof.data.AppLimit(20)
        assertEquals(20, withFixedCaps(mapOf(tighter))[AppGate.CHROME]?.minutes)
    }

    @Test
    fun `chrome's next opening`() {
        val mon = DayOfWeek.MONDAY
        assertEquals(ChromeOpening.TODAY, chromeNextOpening(mon, 7 * 60, afterToday = false))
        assertEquals(ChromeOpening.TOMORROW, chromeNextOpening(mon, 17 * 60, afterToday = false))
        assertEquals(ChromeOpening.TOMORROW, chromeNextOpening(mon, 7 * 60, afterToday = true))
        assertEquals(ChromeOpening.MONDAY, chromeNextOpening(DayOfWeek.FRIDAY, 17 * 60, afterToday = false))
        assertEquals(ChromeOpening.MONDAY, chromeNextOpening(DayOfWeek.FRIDAY, 7 * 60, afterToday = true))
        assertEquals(ChromeOpening.MONDAY, chromeNextOpening(DayOfWeek.SATURDAY, 7 * 60, afterToday = false))
        assertEquals(ChromeOpening.TOMORROW, chromeNextOpening(DayOfWeek.SUNDAY, 7 * 60, afterToday = false))
    }

    @Test
    fun `gated apps open Monday to Thursday 08 10 to 08 50 and stay shut Friday to Sunday`() {
        val start = 8 * 60 + 10
        val end = 8 * 60 + 50
        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) {
            assertFalse(weekendShutActive(day, weekendShut = true))
            assertTrue(windowShutActive(day, 8 * 60 + 9, weekendShut = true, start, end))
            assertFalse(windowShutActive(day, 8 * 60 + 10, weekendShut = true, start, end))
            assertFalse(windowShutActive(day, 8 * 60 + 49, weekendShut = true, start, end))
            assertTrue(windowShutActive(day, 8 * 60 + 50, weekendShut = true, start, end))
        }
        for (day in listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            assertTrue(weekendShutActive(day, weekendShut = true))
            // Even right inside 08:10–08:50, Friday–Sunday stays completely shut.
            assertTrue(windowShutActive(day, 8 * 60 + 25, weekendShut = true, start, end))
            assertTrue(windowShutActive(day, 0, weekendShut = true, start, end))
            assertTrue(windowShutActive(day, 23 * 60 + 59, weekendShut = true, start, end))
            // And even if the daily window were turned off, weekend shut still closes Fri–Sun.
            assertTrue(windowShutActive(day, 12 * 60, weekendShut = true, RULE_OFF_MINUTES, end))
            // Turning weekend shut off restores the daily window on Fri–Sun.
            assertFalse(windowShutActive(day, 8 * 60 + 25, weekendShut = false, start, end))
        }
    }

    @Test
    fun `nextOpenAfterToday skips Friday through Sunday when weekend shut is on`() {
        val start = 8 * 60 + 10
        val end = 8 * 60 + 50
        assertEquals(
            "tomorrow at 08:10",
            nextOpenAfterToday(true, DayOfWeek.MONDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "tomorrow at 08:10",
            nextOpenAfterToday(true, DayOfWeek.WEDNESDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Monday at 08:10",
            nextOpenAfterToday(true, DayOfWeek.THURSDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Monday at 08:10",
            nextOpenAfterToday(true, DayOfWeek.FRIDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Monday at 08:10",
            nextOpenAfterToday(true, DayOfWeek.SATURDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "tomorrow at 08:10",
            nextOpenAfterToday(true, DayOfWeek.SUNDAY, weekendShut = true, start, end, ::fmt)
        )
        assertEquals(
            "Monday",
            nextOpenAfterToday(true, DayOfWeek.THURSDAY, weekendShut = true, RULE_OFF_MINUTES, end, ::fmt)
        )
        assertNull(nextOpenAfterToday(false, DayOfWeek.THURSDAY, weekendShut = true, start, end, ::fmt))
    }

    @Test
    fun `windowWallCopy explains weekend shutdown and Thursday post-window reopen`() {
        val start = 8 * 60 + 10
        val end = 8 * 60 + 50
        val (friTitle, friBody) = windowWallCopy(
            "Instagram", DayOfWeek.FRIDAY, 8 * 60 + 20, weekendShut = true, start, end, ::fmt
        )
        assertEquals("Shut Friday to Sunday", friTitle)
        assertTrue(friBody.contains("Monday between 08:10 and 08:50"))

        val (thuTitle, thuBody) = windowWallCopy(
            "Instagram", DayOfWeek.THURSDAY, 9 * 60, weekendShut = true, start, end, ::fmt
        )
        assertEquals("Not open now", thuTitle)
        assertTrue(thuBody.contains("Monday at 08:10"))
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
