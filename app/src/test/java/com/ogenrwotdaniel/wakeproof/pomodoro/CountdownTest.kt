package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownTest {
    @Test fun roundsUpPartialSeconds() {
        assertEquals(25 * 60, remainingSecondsAt(1_500_000, 0))
        assertEquals(1500, remainingSecondsAt(1_500_000, 1))
        assertEquals(1499, remainingSecondsAt(1_500_000, 1_000))
        assertEquals(1, remainingSecondsAt(1_000, 1))
    }

    @Test fun zeroAtAndAfterTheDeadline() {
        assertEquals(0, remainingSecondsAt(1_000, 1_000))
        assertEquals(0, remainingSecondsAt(1_000, 5_000))
    }

    /** The old decrementing ticker lost time on every overshooting delay; this cannot. */
    @Test fun lateTicksDoNotAccumulate() {
        val deadline = 25 * 60_000L
        // 1500 ticks each 2 ms late lands 3 s past where a decrement would think it is.
        assertEquals(0, remainingSecondsAt(deadline, 1500 * 1_002L))
    }
}
