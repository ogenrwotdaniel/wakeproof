package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class ExactSessionTest {

    /** Focus + breaks of [plan], in minutes, as the timer would run it. */
    private fun lengthOf(plan: SessionPlan): Int =
        (plan.focusBlocks - 1) * (plan.focusMinutes + plan.shortBreakMinutes) + plan.lastFocusMinutes

    @Test
    fun `an hour and twenty-five is three whole pomodoros`() {
        val plan = exactSessionPlan(85)
        assertEquals(3, plan.focusBlocks)
        assertEquals(25, plan.lastFocusMinutes)
        assertEquals(85, lengthOf(plan))
    }

    @Test
    fun `the last block is trimmed so the session ends on the minute`() {
        val plan = exactSessionPlan(50)
        assertEquals(2, plan.focusBlocks)
        assertEquals(20, plan.lastFocusMinutes)
        assertEquals(50, lengthOf(plan))
    }

    @Test
    fun `a stub of a last block is folded into the one before`() {
        // 25 + 5 + 25 + 5 + 2 would be silly; 25 + 5 + 32 is not.
        val plan = exactSessionPlan(62)
        assertEquals(2, plan.focusBlocks)
        assertEquals(32, plan.lastFocusMinutes)
        assertEquals(62, lengthOf(plan))
    }

    @Test
    fun `short events are one block of their own length, never under the floor`() {
        assertEquals(SessionPlan(25, 5, 5, 1, Int.MAX_VALUE, lastFocusMinutes = 15), exactSessionPlan(15))
        assertEquals(MIN_EXACT_SESSION_MINUTES, exactSessionPlan(3).lastFocusMinutes)
    }

    @Test
    fun `every length from ten minutes to five hours comes out exact`() {
        for (total in MIN_EXACT_SESSION_MINUTES..300) {
            val plan = exactSessionPlan(total)
            assertEquals("total $total", total, lengthOf(plan))
            assert(plan.lastFocusMinutes >= MIN_EXACT_SESSION_MINUTES) { "total $total" }
        }
    }

    /**
     * total minutes -> (focus blocks, last block minutes). The SAME table is in
     * the watch's `WatchTimerTest`, whose `exactSessionPlan` is a hand-kept copy
     * of this one: change the rule and one side or the other fails.
     */
    private val sharedVectors = listOf(
        3 to (1 to 10), 10 to (1 to 10), 15 to (1 to 15), 25 to (1 to 25), 34 to (1 to 34),
        35 to (1 to 35), 40 to (2 to 10), 50 to (2 to 20), 55 to (2 to 25), 62 to (2 to 32),
        85 to (3 to 25), 90 to (3 to 30), 120 to (4 to 30), 185 to (6 to 35)
    )

    @Test
    fun `the vectors shared with the watch's copy hold`() {
        for ((total, expected) in sharedVectors) {
            val plan = exactSessionPlan(total)
            assertEquals("total $total", expected, plan.focusBlocks to plan.lastFocusMinutes)
        }
    }

    @Test
    fun `only the last block takes the trimmed length`() {
        assertEquals(1_500, focusSecondsForBlock(1, 3, 1_500, 600))
        assertEquals(600, focusSecondsForBlock(3, 3, 1_500, 600))
        assertEquals(1_500, focusSecondsForBlock(3, 0, 1_500, 600))
    }

    @Test
    fun `the session's remaining time counts the trimmed last block`() {
        // In block 1 of 2 with 10 min left: 10 + 5 break + a 20-min last block.
        assertEquals(
            35L * 60,
            sessionRemainingSeconds(
                phase = PomodoroPhase.FOCUS, remainingSeconds = 600, completedFocusSessions = 0,
                targetFocusBlocks = 2, focusSeconds = 1_500, shortBreakSeconds = 300,
                longBreakSeconds = 300, longBreakEvery = Int.MAX_VALUE, lastFocusSeconds = 1_200
            )
        )
    }

    @Test
    fun `a recovered break leads into the trimmed last block`() {
        val saved = SavedSession(
            focusSeconds = 1_500, shortBreakSeconds = 300, longBreakSeconds = 300,
            longBreakEvery = Int.MAX_VALUE, targetFocusBlocks = 2, completedFocusSessions = 1,
            phase = PomodoroPhase.SHORT_BREAK, phaseEndsAtMillis = 1_000L, treeWithered = false,
            lastFocusSeconds = 1_200
        )
        assertEquals(5_000L + 1_200_000L, recoverSession(saved, nowMillis = 5_000L).resume!!.phaseEndsAtMillis)
        assertEquals(saved, decodeSession(encodeSession(saved)))
    }

    @Test
    fun `a record from before the trimmed block reads its last block as a full one`() {
        val old = "1500|300|900|4|2|0|FOCUS|10000|0"
        assertEquals(1_500, decodeSession(old)!!.lastFocusSeconds)
    }
}
