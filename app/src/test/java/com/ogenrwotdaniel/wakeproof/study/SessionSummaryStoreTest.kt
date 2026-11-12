package com.ogenrwotdaniel.wakeproof.study

import com.ogenrwotdaniel.wakeproof.plan.parsePhotoFileName
import com.ogenrwotdaniel.wakeproof.plan.photoFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSummaryStoreTest {

    @Test
    fun `a session is asked once, and the next session asks again`() {
        val asked = withSummaryAsked(SummaryState(), "a", "Physics", now = 1_000L)
        assertEquals(PendingSummary("a", "Physics", 1_000L), asked.pending)
        // Photographed, then the same session is recovered and ends a second time.
        val answered = asked.copy(pending = null)
        assertSame(answered, withSummaryAsked(answered, "a", "Physics", now = 2_000L))
        assertEquals("b", withSummaryAsked(answered, "b", "Maths", now = 3_000L).pending?.sessionKey)
    }

    @Test
    fun `the summary holds for half an hour and no longer`() {
        val state = withSummaryAsked(SummaryState(), "a", "Physics", now = 1_000L)
        assertEquals("a", summaryDue(state, 1_000L + SUMMARY_HOLD_MILLIS)?.sessionKey)
        assertNull(summaryDue(state, 1_001L + SUMMARY_HOLD_MILLIS))
        // A small step back (a network correction) still owes it; a clock set
        // far back does not hold the wall for ever.
        assertEquals("a", summaryDue(state, 999L)?.sessionKey)
        assertNull(summaryDue(state, 999L - SUMMARY_HOLD_MILLIS))
        assertNull(summaryDue(SummaryState(), 1_000L))
    }

    @Test
    fun `the writing time is ten minutes from the session's end`() {
        val pending = PendingSummary("a", "Physics", askedAtMillis = 1_000L)
        assertEquals(SUMMARY_WRITE_MILLIS, summaryWriteLeftMillis(pending, 1_000L))
        assertEquals(1L, summaryWriteLeftMillis(pending, 999L + SUMMARY_WRITE_MILLIS))
        assertEquals(0L, summaryWriteLeftMillis(pending, 1_000L + SUMMARY_WRITE_MILLIS))
        assertEquals(0L, summaryWriteLeftMillis(pending, 1_000L + 3 * SUMMARY_WRITE_MILLIS))
        // A clock set back cannot stretch it past ten minutes.
        assertEquals(SUMMARY_WRITE_MILLIS, summaryWriteLeftMillis(pending, 0L))
    }

    @Test
    fun `a photo kept before the ten minutes are up does not settle the summary`() {
        val state = withSummaryAsked(SummaryState(), "a", "Physics", now = 1_000L)
        assertSame(state, withSummaryKept(state, 1_000L + SUMMARY_WRITE_MILLIS - 1))
        val kept = withSummaryKept(state, 1_000L + SUMMARY_WRITE_MILLIS)
        assertNull(kept.pending)
        // Still remembered, so the session is not asked again.
        assertEquals("a", kept.lastKey)
        assertEquals(SummaryState(), withSummaryKept(SummaryState(), 5L))
    }

    @Test
    fun `an owed summary shuts what a session shuts and nothing more`() {
        fun verdict(listed: Boolean = false, essential: Boolean = false, sleeping: Boolean = false) =
            com.ogenrwotdaniel.wakeproof.distraction.gateVerdict(
                essential = essential, focusSessionActive = false, onFocusList = listed,
                capSpent = false, gated = false, sleeping = sleeping, summaryOwed = true
            )
        val wall = com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.SUMMARY_WALL
        assertEquals(wall, verdict())
        assertEquals(com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.ALLOW, verdict(listed = true))
        // Settings and the dialer stay open: it is not a session.
        assertEquals(com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.ALLOW, verdict(essential = true))
        // And the night wall is not lifted for study apps, as a session would.
        assertEquals(
            com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.SLEEP_WALL,
            verdict(listed = true, sleeping = true)
        )
    }

    @Test
    fun `a summary photo is told from a photo of another shelf`() {
        val name = photoFileName("summary", 20_770L, 5L)
        assertEquals("summary_20770_5.jpg", name)
        assertEquals(20_770L to 5L, parsePhotoFileName("summary", name))
        assertNull(parsePhotoFileName("plan", name))
        assertNull(parsePhotoFileName("summary", "plan_20770_5.jpg"))
        assertNull(parsePhotoFileName("summary", "summary_20770_5.jpg.part"))
    }

    @Test
    fun `another device's summary holds starts here until its end and never past the longest hold`() {
        val now = 1_000_000_000L
        assertTrue(peerSummaryHolds(untilMillis = now + 60_000L, now = now))
        assertTrue(peerSummaryHolds(untilMillis = now + SUMMARY_HOLD_MILLIS, now = now))
        assertFalse(peerSummaryHolds(untilMillis = 0L, now = now))
        assertFalse(peerSummaryHolds(untilMillis = now, now = now))
        // An end no hold could have is a wrong clock on one side.
        assertFalse(peerSummaryHolds(untilMillis = now + SUMMARY_HOLD_MILLIS + 1L, now = now))
    }

    @Test
    fun `the writing time is counted on the running clock, so a clock set ahead opens nothing`() {
        val pending = PendingSummary("a", "Physics", askedAtMillis = 1_000L, askedElapsedMillis = 50_000L, bootCount = 7)
        val jumped = 1_000L + 2 * SUMMARY_WRITE_MILLIS
        // The wall clock says the ten minutes are over; one minute has really passed.
        assertEquals(
            SUMMARY_WRITE_MILLIS - 60_000L,
            summaryWriteLeftMillis(pending, jumped, SteadyClock(elapsedMillis = 110_000L, bootCount = 7))
        )
        val state = SummaryState(pending = pending, lastKey = "a")
        assertSame(state, withSummaryKept(state, jumped, SteadyClock(110_000L, 7)))
        assertNull(withSummaryKept(state, 1_000L, SteadyClock(50_000L + SUMMARY_WRITE_MILLIS, 7)).pending)
        // After a restart the running clock starts again: the wall clock decides.
        assertEquals(0L, summaryWriteLeftMillis(pending, jumped, SteadyClock(5_000L, bootCount = 8)))
        // An ask stored without a running-clock reading is judged on the wall clock too.
        assertEquals(0L, summaryWriteLeftMillis(PendingSummary("a", "Physics", 1_000L), jumped, SteadyClock(110_000L, 7)))
    }

    @Test
    fun `the half hour is counted on the running clock too, so a clock set ahead lifts nothing`() {
        val pending = PendingSummary("a", "Physics", askedAtMillis = 1_000L, askedElapsedMillis = 50_000L, bootCount = 7)
        val state = SummaryState(pending = pending, lastKey = "a")
        val jumped = 1_000L + 2 * SUMMARY_HOLD_MILLIS
        assertEquals(pending, summaryDue(state, jumped, SteadyClock(110_000L, 7)))
        assertNull(summaryDue(state, 1_000L, SteadyClock(50_001L + SUMMARY_HOLD_MILLIS, 7)))
        // After a restart the wall clock decides.
        assertNull(summaryDue(state, jumped, SteadyClock(5_000L, 8)))
    }
}
