package com.ogenrwotdaniel.wakeproof.wear

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTimerTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val durations = WatchDurations(focusMinutes = 25, shortBreakMinutes = 5, longBreakMinutes = 15)
    private val t0 = LocalDate.of(2026, 10, 15).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val minute = 60_000L
    private var ids = 0
    private fun nextId() = "block-${++ids}"

    @Test
    fun `start runs a focus block of the configured length`() {
        val s = WatchTimer.start(durations, "Calculus", t0)
        assertTrue(s.active && s.running)
        assertEquals(WatchPhase.FOCUS, s.phase)
        assertEquals(t0 + 25 * minute, s.phaseEndsAt)
        assertEquals(25 * 60L, s.remainingSecondsAt(t0))
    }

    @Test
    fun `countdown rounds up so the last second shows until it is over`() {
        val s = WatchTimer.start(durations, null, t0)
        assertEquals(1L, s.remainingSecondsAt(s.phaseEndsAt - 1))
        assertEquals(0L, s.remainingSecondsAt(s.phaseEndsAt))
    }

    @Test
    fun `advance before the end does nothing`() {
        val s = WatchTimer.start(durations, null, t0)
        val a = WatchTimer.advance(s, t0 + 24 * minute, utc, ::nextId)
        assertSame(s, a.state)
        assertNull(a.completed)
    }

    @Test
    fun `finishing focus credits one block and starts a short break from now`() {
        val s = WatchTimer.start(durations, "Calculus", t0)
        val late = s.phaseEndsAt + 3 * minute // alarm delayed by Doze
        val a = WatchTimer.advance(s, late, utc, ::nextId)
        val block = a.completed!!
        assertEquals(25, block.minutes)
        assertEquals("Calculus", block.subject)
        assertEquals(t0, block.startedAt)
        assertEquals(s.phaseEndsAt, block.endedAt)
        assertEquals(WatchPhase.SHORT_BREAK, a.state.phase)
        assertEquals(late + 5 * minute, a.state.phaseEndsAt)
        assertEquals(1, a.state.completedFocus)
    }

    @Test
    fun `a second advance for the same phase end credits nothing`() {
        val s = WatchTimer.start(durations, null, t0)
        val end = s.phaseEndsAt
        val first = WatchTimer.advance(s, end, utc, ::nextId)
        val second = WatchTimer.advance(first.state, end, utc, ::nextId)
        assertNull(second.completed)
        assertSame(first.state, second.state)
    }

    @Test
    fun `every fourth block is followed by a long break`() {
        var s = WatchTimer.start(durations, null, t0)
        var now = t0
        val breaks = mutableListOf<WatchPhase>()
        repeat(4) {
            now = s.phaseEndsAt
            s = WatchTimer.advance(s, now, utc, ::nextId).state
            breaks += s.phase
            now = s.phaseEndsAt
            s = WatchTimer.advance(s, now, utc, ::nextId).state
            assertEquals(WatchPhase.FOCUS, s.phase)
        }
        assertEquals(
            listOf(WatchPhase.SHORT_BREAK, WatchPhase.SHORT_BREAK, WatchPhase.SHORT_BREAK, WatchPhase.LONG_BREAK),
            breaks
        )
    }

    @Test
    fun `ending a break credits nothing`() {
        val focus = WatchTimer.start(durations, null, t0)
        val brk = WatchTimer.advance(focus, focus.phaseEndsAt, utc, ::nextId).state
        val back = WatchTimer.advance(brk, brk.phaseEndsAt, utc, ::nextId)
        assertNull(back.completed)
        assertEquals(WatchPhase.FOCUS, back.state.phase)
        assertEquals(brk.phaseEndsAt, back.state.focusStartedAt)
    }

    @Test
    fun `pause freezes the remaining time and resume continues from it`() {
        val s = WatchTimer.start(durations, null, t0)
        val paused = WatchTimer.pause(s, t0 + 10 * minute)
        assertFalse(paused.running)
        assertEquals(15 * minute, paused.remainingMillisAt(t0 + 60 * minute))
        assertFalse(paused.isDue(t0 + 60 * minute))

        val resumed = WatchTimer.resume(paused, t0 + 60 * minute)
        assertEquals(t0 + 75 * minute, resumed.phaseEndsAt)
        // A paused block is still a whole block of focus.
        val a = WatchTimer.advance(resumed, resumed.phaseEndsAt, utc, ::nextId)
        assertEquals(25, a.completed!!.minutes)
    }

    @Test
    fun `stop mid focus credits nothing`() {
        assertFalse(WatchTimer.stop().active)
    }

    @Test
    fun `skip ends a break but never a focus block`() {
        val focus = WatchTimer.start(durations, null, t0)
        assertSame(focus, WatchTimer.skipBreak(focus, t0 + minute))

        val brk = WatchTimer.advance(focus, focus.phaseEndsAt, utc, ::nextId).state
        val now = brk.phaseEndsAt - 2 * minute
        val skipped = WatchTimer.skipBreak(brk, now)
        assertEquals(WatchPhase.FOCUS, skipped.phase)
        assertEquals(now + 25 * minute, skipped.phaseEndsAt)
        assertTrue(skipped.running)
    }

    @Test
    fun `a block counts towards the day it ended on`() {
        val lateNight = LocalDate.of(2026, 10, 15).atTime(23, 50).toInstant(ZoneOffset.UTC).toEpochMilli()
        val s = WatchTimer.start(durations, null, lateNight)
        val block = WatchTimer.advance(s, s.phaseEndsAt, utc, ::nextId).completed!!
        assertEquals(LocalDate.of(2026, 10, 16).toEpochDay(), block.epochDay)
    }

    @Test
    fun `a phase is never shorter than a minute even if the phone pushed zero`() {
        assertEquals(minute, WatchDurations(focusMinutes = 0).millisOf(WatchPhase.FOCUS))
    }

    @Test
    fun `today's focus adds unsynced blocks and ignores a stale phone snapshot`() {
        val today = LocalDate.of(2026, 10, 15).toEpochDay()
        val yesterdayPush = t0 - 24 * 60 * minute
        val stale = WearStats(focusMinutes = 120, present = true, updatedAtMillis = yesterdayPush)
        val fresh = stale.copy(updatedAtMillis = t0)
        val pending = listOf(
            FocusBlock("a", 0, 0, 25, today, null),
            FocusBlock("b", 0, 0, 25, today - 1, null)
        )
        assertEquals(WatchFocusToday(25, 2), focusToday(stale, pending, today, utc))
        assertEquals(WatchFocusToday(145, 2), focusToday(fresh, pending, today, utc))
    }

    @Test
    fun `presets map to the same block counts as the phone`() {
        // 25+5: 1 h = 2 blocks (55 min), 1 h 30 = 3 (85), 2 h = 4 (115); free = 0.
        fun blocks(session: WatchSession) =
            WatchTimer.start(durations, null, t0, session).targetFocusBlocks
        assertEquals(0, blocks(WatchSession.FREE))
        assertEquals(2, blocks(WatchSession.ONE_HOUR))
        assertEquals(3, blocks(WatchSession.NINETY))
        assertEquals(4, blocks(WatchSession.TWO_HOURS))
        // 50+10 at 90 min is one long block, not floored to nothing.
        assertEquals(2, WatchTimer.focusBlocksForSession(90, 50, 10))
    }

    @Test
    fun `a timed session stops after its last block, skipping the trailing break`() {
        var s = WatchTimer.start(durations, null, t0, WatchSession.ONE_HOUR)
        val first = WatchTimer.advance(s, s.phaseEndsAt, utc, ::nextId)
        assertFalse(first.sessionFinished)
        s = WatchTimer.advance(first.state, first.state.phaseEndsAt, utc, ::nextId).state

        val last = WatchTimer.advance(s, s.phaseEndsAt, utc, ::nextId)
        assertTrue(last.sessionFinished)
        assertNotNullBlock(last)
        assertFalse(last.state.active)
    }

    @Test
    fun `a free run never finishes on its own`() {
        var s = WatchTimer.start(durations, null, t0)
        repeat(20) {
            val a = WatchTimer.advance(s, s.phaseEndsAt, utc, ::nextId)
            assertFalse(a.sessionFinished)
            s = a.state
        }
        assertTrue(s.active)
    }

    private fun assertNotNullBlock(a: Advance) = assertEquals(25, a.completed?.minutes)
}
