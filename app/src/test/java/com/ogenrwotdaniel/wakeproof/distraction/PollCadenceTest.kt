package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollCadenceTest {

    @Test
    fun `inside the last poll interval falls back to the fast rate`() {
        // Under the floor the delay clamps UP, so the wall lands within the
        // same 1.5 s granularity the loop has always had.
        assertEquals(FAST_POLL_MILLIS, pollDelayMillis(500L))
    }

    @Test
    fun `a few seconds left sleeps exactly that long`() {
        // The point of the cadence: wake when the cap is actually due, rather
        // than at a fixed rate that is either too slow or needlessly fast.
        assertEquals(2_000L, pollDelayMillis(2_000L))
    }

    @Test
    fun `far from the cap backs off to the ceiling`() {
        // 40 minutes of allowance left — the wall cannot be needed for 40
        // minutes, so there is nothing to gain from asking sooner.
        assertEquals(MAX_POLL_MILLIS, pollDelayMillis(40 * 60_000L))
    }

    @Test
    fun `the backoff never exceeds the extrapolation window`() {
        // The ceiling is a correctness boundary, not a comfort setting: past it
        // the loop would be extending how long usage is extrapolated for.
        assertTrue(pollDelayMillis(Long.MAX_VALUE) <= MAX_POLL_MILLIS)
    }

    @Test
    fun `an overshoot does not spin the loop`() {
        // remainingMillis is derived from an extrapolation and can arrive
        // negative when the clock ran past the cap between measurements.
        assertEquals(FAST_POLL_MILLIS, pollDelayMillis(0L))
        assertEquals(FAST_POLL_MILLIS, pollDelayMillis(-5_000L))
    }

    @Test
    fun `the middle of the range is honoured exactly`() {
        // Ten seconds left means the next check can be ten seconds away, which
        // is what keeps the wall on time without a fixed rate.
        assertEquals(10_000L, pollDelayMillis(10_000L))
    }
}
