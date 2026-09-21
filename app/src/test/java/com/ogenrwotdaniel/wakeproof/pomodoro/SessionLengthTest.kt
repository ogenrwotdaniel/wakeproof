package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class SessionLengthTest {

    /** Real length of [blocks] pomodoros: no break is run after the last one. */
    private fun actualMinutes(blocks: Int, focus: Int, breakMinutes: Int) =
        blocks * focus + (blocks - 1) * breakMinutes

    @Test
    fun `free session runs until stopped`() {
        assertEquals(0, focusBlocksForSession(0, 25, 5))
        assertEquals(0, focusBlocksForSession(-1, 25, 5))
    }

    @Test
    fun `default lengths give the obvious answers`() {
        assertEquals(2, focusBlocksForSession(60, 25, 5))
        assertEquals(3, focusBlocksForSession(90, 25, 5))
        assertEquals(4, focusBlocksForSession(120, 25, 5))
    }

    @Test
    fun `a long focus setting does not halve the session`() {
        // The bug the old `sessionMinutes / cycleMinutes` had: 90 / 60 floored
        // to one 50-minute block, 40 minutes short of what was asked for.
        assertEquals(2, focusBlocksForSession(90, 50, 10))
        assertEquals(1, focusBlocksForSession(60, 50, 10))
    }

    @Test
    fun `a session is never empty`() {
        assertEquals(1, focusBlocksForSession(5, 50, 10))
        assertEquals(1, focusBlocksForSession(1, 25, 5))
    }

    @Test
    fun `chosen block count is the closest whole session available`() {
        val focusLengths = listOf(15, 20, 25, 30, 45, 50, 60)
        val breakLengths = listOf(3, 5, 10, 15)
        for (focus in focusLengths) {
            for (rest in breakLengths) {
                for (requested in listOf(60, 90, 120)) {
                    val chosen = focusBlocksForSession(requested, focus, rest)
                    val error = abs(actualMinutes(chosen, focus, rest) - requested)
                    for (other in 1..8) {
                        val otherError = abs(actualMinutes(other, focus, rest) - requested)
                        assert(error <= otherError) {
                            "$requested min at $focus+$rest: chose $chosen (off by $error) " +
                                "when $other was off by $otherError"
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a nonsense cycle does not crash the service`() {
        assertEquals(1, focusBlocksForSession(60, 0, 0))
    }
}
