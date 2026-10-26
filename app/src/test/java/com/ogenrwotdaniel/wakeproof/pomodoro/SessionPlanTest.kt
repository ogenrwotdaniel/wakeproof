package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPlanTest {

    @Test
    fun `three hours is six fixed pomodoros with the long break after the third`() {
        // Settings of 50/10/30 must not stretch the preset to nearly six hours.
        val plan = sessionPlan(THREE_HOUR_SESSION_MINUTES, 50, 10, 30)
        assertEquals(SessionPlan(25, 5, 15, focusBlocks = 6, longBreakEvery = 3), plan)
        // 6 × 25 focus + 4 short breaks + 1 long break, no trailing break.
        val total = 6 * plan.focusMinutes + 4 * plan.shortBreakMinutes + plan.longBreakMinutes
        assertEquals(185, total)
    }

    @Test
    fun `other lengths run the user's own blocks, long break every fourth`() {
        assertEquals(SessionPlan(25, 5, 15, focusBlocks = 4, longBreakEvery = 4), sessionPlan(120, 25, 5, 15))
    }

    @Test
    fun `a start with no length is an hour, never a session that cannot end`() {
        assertEquals(2, sessionPlan(0, 25, 5, 15).focusBlocks)
    }
}
