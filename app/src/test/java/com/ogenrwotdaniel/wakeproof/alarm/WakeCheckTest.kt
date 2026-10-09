package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.ui.home.REVIEW_STREAK_DAYS
import com.ogenrwotdaniel.wakeproof.ui.home.shouldAskForReview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCheckTest {

    private val today = 20_370L

    @Test
    fun `no entry means today's check has not run`() {
        assertFalse(wakeCheckDoneOn(null, today))
    }

    @Test
    fun `today's entry means the check already ran`() {
        // Which is what stops a verified re-ring arming another check.
        assertTrue(wakeCheckDoneOn(today.toString(), today))
    }

    @Test
    fun `yesterday's entry is stale`() {
        assertFalse(wakeCheckDoneOn((today - 1).toString(), today))
    }

    @Test
    fun `a corrupt entry reads as not yet run`() {
        assertFalse(wakeCheckDoneOn("garbage", today))
    }

    @Test
    fun `review is asked once, and only after the streak earns it`() {
        assertFalse(shouldAskForReview(REVIEW_STREAK_DAYS - 1, alreadyAsked = false))
        assertTrue(shouldAskForReview(REVIEW_STREAK_DAYS, alreadyAsked = false))
        assertFalse(shouldAskForReview(30, alreadyAsked = true))
    }
}
