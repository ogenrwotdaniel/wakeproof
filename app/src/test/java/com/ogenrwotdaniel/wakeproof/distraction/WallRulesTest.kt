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
    fun `switching a wall off is loosening, switching it on is not`() {
        assertTrue(WallRulesRepository.isLoosening(WallRule.EARN_THE_FEED, RULE_ON, RULE_OFF))
        assertFalse(WallRulesRepository.isLoosening(WallRule.EARN_THE_FEED, RULE_OFF, RULE_ON))
        assertTrue(WallRulesRepository.isLoosening(WallRule.WEEKEND_SHUT, RULE_ON, RULE_OFF))
        assertFalse(WallRulesRepository.isLoosening(WallRule.WEEKEND_SHUT, RULE_OFF, RULE_ON))
        assertFalse(WallRulesRepository.isLoosening(WallRule.SESSION_DND, RULE_ON, RULE_ON))
    }

    @Test
    fun `a later bedtime or none is loosening, an earlier one is not`() {
        val bedtime = WallRule.BEDTIME_START_MINUTES
        assertTrue(WallRulesRepository.isLoosening(bedtime, 17 * 60, 21 * 60))
        assertTrue(WallRulesRepository.isLoosening(bedtime, 17 * 60, RULE_OFF_MINUTES))
        assertFalse(WallRulesRepository.isLoosening(bedtime, 17 * 60, 16 * 60))
        assertFalse(WallRulesRepository.isLoosening(bedtime, RULE_OFF_MINUTES, 23 * 60))
    }

    @Test
    fun `pending rules survive a round trip and junk is dropped`() {
        val pending = mapOf(
            WallRule.GATE_BROWSERS to PendingRule(RULE_OFF, 1_000L),
            WallRule.WEEKEND_SHUT to PendingRule(RULE_OFF, 2_000L)
        )
        val encoded = WallRulesRepository.encodePendingRules(pending) + "nonsense" + "unknown|1|2" +
            "daily_budget_minutes|60|3000"
        assertEquals(pending, WallRulesRepository.parsePendingRules(encoded))
    }

    @Test
    fun `every rule defaults to on, with weekend shut, 17 00 bedtime and 08 10 to 08 50 window`() {
        val rules = WallRules()
        assertTrue(rules.gateBrowsers && rules.weekendShut && rules.earnTheFeed && rules.sessionDnd)
        assertEquals(17 * 60, rules.bedtimeStartMinutes)
        assertEquals(8 * 60 + 10, rules.openWindowStartMinutes)
        assertEquals(8 * 60 + 50, rules.openWindowEndMinutes)
    }

    @Test
    fun `bedtime runs from the evening until a morning alarm or seven`() {
        val start = 17 * 60
        assertFalse(bedtimeActive(16 * 60 + 59, start, alarmVerifiedToday = true))
        assertTrue(bedtimeActive(17 * 60, start, alarmVerifiedToday = true))
        assertTrue(bedtimeActive(23 * 60, start, alarmVerifiedToday = false))
        // After midnight: shut until the alarm is verified…
        assertTrue(bedtimeActive(4 * 60 + 29, start, alarmVerifiedToday = false))
        assertFalse(bedtimeActive(4 * 60 + 31, start, alarmVerifiedToday = true))
        // …or 07:00 on a day without one.
        assertTrue(bedtimeActive(6 * 60 + 59, start, alarmVerifiedToday = false))
        assertFalse(bedtimeActive(7 * 60, start, alarmVerifiedToday = false))
        assertFalse(bedtimeActive(23 * 60, RULE_OFF_MINUTES, alarmVerifiedToday = false))
    }

    @Test
    fun `browsers are gated only while the rule is on`() {
        val chrome = "com.android.chrome"
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertTrue(AppGate.isGated(chrome, other, null, isBrowser = true, gateBrowsers = true))
        assertFalse(AppGate.isGated(chrome, other, null, isBrowser = true, gateBrowsers = false))
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
        fun verdict(bedtime: Boolean = false, feedEarned: Boolean = true) =
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, bedtime = bedtime, feedEarned = feedEarned
            )
        assertEquals(GateVerdict.ALLOW, verdict())
        assertEquals(GateVerdict.BEDTIME_WALL, verdict(bedtime = true, feedEarned = false))
        assertEquals(GateVerdict.EARN_WALL, verdict(feedEarned = false))

        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = false, bedtime = true, feedEarned = false
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
    fun `opening earlier or closing later is loosening, narrowing is not`() {
        val start = WallRule.OPEN_WINDOW_START_MINUTES
        val end = WallRule.OPEN_WINDOW_END_MINUTES
        assertTrue(WallRulesRepository.isLoosening(start, 8 * 60 + 10, 8 * 60))
        assertTrue(WallRulesRepository.isLoosening(start, 8 * 60 + 10, RULE_OFF_MINUTES))
        assertFalse(WallRulesRepository.isLoosening(start, 8 * 60 + 10, 8 * 60 + 20))
        assertFalse(WallRulesRepository.isLoosening(start, RULE_OFF_MINUTES, 8 * 60 + 10))
        assertTrue(WallRulesRepository.isLoosening(end, 8 * 60 + 50, 9 * 60))
        assertFalse(WallRulesRepository.isLoosening(end, 8 * 60 + 50, 8 * 60 + 40))
    }

    @Test
    fun `outside the window comes before bedtime and earn-the-feed`() {
        fun verdict(gated: Boolean, outsideWindow: Boolean = true) = gateVerdict(
            essential = false, focusSessionActive = false, onFocusList = false,
            capSpent = false, gated = gated,
            outsideWindow = outsideWindow, bedtime = true, feedEarned = false
        )
        assertEquals(GateVerdict.WINDOW_WALL, verdict(gated = true))
        assertEquals(GateVerdict.WINDOW_WALL, verdict(gated = false))
        assertEquals(GateVerdict.ALLOW, verdict(gated = false, outsideWindow = false))
    }

    @Test
    fun `the open window counts as earned, without one the focus block is needed`() {
        assertTrue(feedEarned(earnTheFeed = true, focusDoneToday = false, windowStartMinutes = 490))
        assertFalse(feedEarned(earnTheFeed = true, focusDoneToday = false, windowStartMinutes = RULE_OFF_MINUTES))
        assertTrue(feedEarned(earnTheFeed = true, focusDoneToday = true, windowStartMinutes = RULE_OFF_MINUTES))
        assertTrue(feedEarned(earnTheFeed = false, focusDoneToday = false, windowStartMinutes = RULE_OFF_MINUTES))
    }
}
