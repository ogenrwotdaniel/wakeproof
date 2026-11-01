package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchSessionBeaconTest {

    private val min = 60_000L

    @Test
    fun `no session ends at zero`() {
        assertEquals(0L, WatchSessionBeacon.sessionEndsAt(WatchTimerState(), now = 5L))
    }

    @Test
    fun `first block of a two block session runs to the end of the second block`() {
        val state = WatchTimerState(
            active = true, running = true, phase = WatchPhase.FOCUS,
            phaseEndsAt = 25 * min, completedFocus = 0, targetFocusBlocks = 2
        )
        // 25 focus + 5 break + 25 focus, no trailing break.
        assertEquals(55 * min, WatchSessionBeacon.sessionEndsAt(state, now = 0L))
    }

    @Test
    fun `the three hour recipe takes its long break after the third block`() {
        val state = WatchTimerState(
            active = true, running = true, phase = WatchPhase.FOCUS,
            phaseEndsAt = 25 * min, targetFocusBlocks = 6, longBreakEvery = 3
        )
        assertEquals(185 * min, WatchSessionBeacon.sessionEndsAt(state, now = 0L))
    }

    @Test
    fun `in the last break only the last block is left`() {
        val state = WatchTimerState(
            active = true, running = true, phase = WatchPhase.SHORT_BREAK,
            phaseEndsAt = 5 * min, completedFocus = 1, targetFocusBlocks = 2
        )
        assertEquals(30 * min, WatchSessionBeacon.sessionEndsAt(state, now = 0L))
    }
}
