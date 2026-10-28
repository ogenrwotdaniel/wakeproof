package com.ogenrwotdaniel.wakeproof.distraction

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallRulesTest {

    @Test
    fun `switching a wall off is loosening, switching it on is not`() {
        assertTrue(WallRulesRepository.isLoosening(WallRule.EARN_THE_FEED, RULE_ON, RULE_OFF))
        assertFalse(WallRulesRepository.isLoosening(WallRule.EARN_THE_FEED, RULE_OFF, RULE_ON))
        assertFalse(WallRulesRepository.isLoosening(WallRule.SESSION_DND, RULE_ON, RULE_ON))
    }

    @Test
    fun `a bigger budget or none at all is loosening, a smaller one is not`() {
        val budget = WallRule.DAILY_BUDGET_MINUTES
        assertTrue(WallRulesRepository.isLoosening(budget, 25, 30))
        assertTrue(WallRulesRepository.isLoosening(budget, 25, 0))
        assertFalse(WallRulesRepository.isLoosening(budget, 25, 20))
        // Off to any budget is tightening.
        assertFalse(WallRulesRepository.isLoosening(budget, 0, 120))
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
            WallRule.DAILY_BUDGET_MINUTES to PendingRule(60, 2_000L)
        )
        val encoded = WallRulesRepository.encodePendingRules(pending) + "nonsense" + "unknown|1|2"
        assertEquals(pending, WallRulesRepository.parsePendingRules(encoded))
    }

    @Test
    fun `every rule defaults to on, with a 25 minute budget and a 17 00 bedtime`() {
        val rules = WallRules()
        assertTrue(rules.gateBrowsers && rules.escalatingWait && rules.earnTheFeed && rules.sessionDnd)
        assertEquals(25, rules.dailyBudgetMinutes)
        assertEquals(17 * 60, rules.bedtimeStartMinutes)
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
    fun `the wait grows by five minutes a visit up to half an hour`() {
        assertEquals(5 * 60, gateWaitSeconds(0, escalating = true))
        assertEquals(10 * 60, gateWaitSeconds(1, escalating = true))
        assertEquals(30 * 60, gateWaitSeconds(5, escalating = true))
        assertEquals(30 * 60, gateWaitSeconds(40, escalating = true))
        assertEquals(5 * 60, gateWaitSeconds(7, escalating = false))
    }

    @Test
    fun `browsers wait only while the rule is on`() {
        val chrome = "com.android.chrome"
        val other = ApplicationInfo.CATEGORY_UNDEFINED
        assertTrue(AppGate.isGated(chrome, other, null, isBrowser = true, gateBrowsers = true))
        assertFalse(AppGate.isGated(chrome, other, null, isBrowser = true, gateBrowsers = false))
    }

    @Test
    fun `the new walls stand in front of a waiting app, pass or not`() {
        fun verdict(bedtime: Boolean = false, feedEarned: Boolean = true, budgetSpent: Boolean = false) =
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = true, hasPass = true,
                bedtime = bedtime, feedEarned = feedEarned, budgetSpent = budgetSpent
            )
        assertEquals(GateVerdict.ALLOW, verdict())
        assertEquals(GateVerdict.BEDTIME_WALL, verdict(bedtime = true, feedEarned = false))
        assertEquals(GateVerdict.EARN_WALL, verdict(feedEarned = false, budgetSpent = true))
        assertEquals(GateVerdict.BUDGET_WALL, verdict(budgetSpent = true))
    }

    @Test
    fun `the walls for waiting apps leave other apps alone`() {
        assertEquals(
            GateVerdict.ALLOW,
            gateVerdict(
                essential = false, focusSessionActive = false, onFocusList = false,
                capSpent = false, gated = false, hasPass = false,
                bedtime = true, feedEarned = false, budgetSpent = true
            )
        )
    }

    @Test
    fun `the gate log keeps four weeks and skips junk`() {
        val today = 20_000L
        val log = (0L..40L).associate { (today - it) to GateDay(today - it, opened = 1, walkedAway = 2) }
        val parsed = parseGateLog(encodeGateLog(log, today) + ",junk,1:x:2")
        assertEquals(GateLog.KEEP_DAYS, parsed.size)
        assertEquals(GateDay(today, 1, 2), parsed[today])
    }
}
