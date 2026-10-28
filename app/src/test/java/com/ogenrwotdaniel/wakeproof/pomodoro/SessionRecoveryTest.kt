package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {

    private val saved = SavedSession(
        focusSeconds = 1_500, shortBreakSeconds = 300, longBreakSeconds = 900,
        longBreakEvery = 3, targetFocusBlocks = 6, completedFocusSessions = 2,
        phase = PomodoroPhase.FOCUS, phaseEndsAtMillis = 10_000_000L, treeWithered = false
    )

    @Test
    fun `a focus block still running resumes, minus its tree`() {
        val out = recoverSession(saved, nowMillis = 9_000_000L)
        assertEquals(saved.copy(treeWithered = true), out.resume)
        assertFalse(out.witheredBlock)
    }

    @Test
    fun `a break still running resumes untouched`() {
        val onBreak = saved.copy(phase = PomodoroPhase.SHORT_BREAK)
        assertEquals(onBreak, recoverSession(onBreak, nowMillis = 9_000_000L).resume)
    }

    @Test
    fun `an overdue focus block is lost and the next break starts now`() {
        val now = 20_000_000L
        val out = recoverSession(saved, now)
        assertTrue(out.witheredBlock)
        // Third block done: long break after every 3rd.
        assertEquals(PomodoroPhase.LONG_BREAK, out.resume!!.phase)
        assertEquals(3, out.resume!!.completedFocusSessions)
        assertEquals(now + 900_000L, out.resume!!.phaseEndsAtMillis)
    }

    @Test
    fun `an overdue break is followed by a full focus block from now`() {
        val now = 20_000_000L
        val out = recoverSession(saved.copy(phase = PomodoroPhase.SHORT_BREAK, treeWithered = true), now)
        assertFalse(out.witheredBlock)
        assertEquals(PomodoroPhase.FOCUS, out.resume!!.phase)
        assertEquals(now + 1_500_000L, out.resume!!.phaseEndsAtMillis)
        assertFalse(out.resume!!.treeWithered)
    }

    @Test
    fun `an overdue last block ends the session`() {
        val out = recoverSession(saved.copy(completedFocusSessions = 5), nowMillis = 20_000_000L)
        assertNull(out.resume)
        assertTrue(out.witheredBlock)
    }

    @Test
    fun `remaining session time counts every phase still to come`() {
        // 3 h recipe at the start of block 1: 6 × 25 + 4 × 5 + 1 × 15 = 185 min.
        assertEquals(
            185L * 60,
            sessionRemainingSeconds(
                phase = PomodoroPhase.FOCUS, remainingSeconds = 1_500,
                completedFocusSessions = 0, targetFocusBlocks = 6,
                focusSeconds = 1_500, shortBreakSeconds = 300, longBreakSeconds = 900,
                longBreakEvery = 3
            )
        )
        // In the break after block 5: 60 s left, then block 6 and nothing after.
        assertEquals(
            60L + 1_500,
            sessionRemainingSeconds(
                phase = PomodoroPhase.SHORT_BREAK, remainingSeconds = 60,
                completedFocusSessions = 5, targetFocusBlocks = 6,
                focusSeconds = 1_500, shortBreakSeconds = 300, longBreakSeconds = 900,
                longBreakEvery = 3
            )
        )
    }

    @Test
    fun `a saved session survives a round trip, a corrupt one does not`() {
        assertEquals(saved, decodeSession(encodeSession(saved)))
        assertNull(decodeSession("1|2|3"))
        assertNull(decodeSession(encodeSession(saved).replace("FOCUS", "NAP")))
    }
}
