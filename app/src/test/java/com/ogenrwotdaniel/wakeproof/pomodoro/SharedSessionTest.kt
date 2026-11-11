package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSessionTest {

    private val now = 1_800_000_000_000L
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun session(
        phase: PomodoroPhase = PomodoroPhase.FOCUS,
        endsIn: Long = 10 * 60_000L,
        completed: Int = 1,
        target: Int = 4,
        fromOrigin: Boolean = true,
        origin: String = SharedSession.ORIGIN_WATCH
    ) = SharedSession(
        id = id, origin = origin, phase = phase, phaseEndsAtMillis = now + endsIn,
        completedFocus = completed, targetFocusBlocks = target,
        focusSeconds = 25 * 60, shortBreakSeconds = 5 * 60, longBreakSeconds = 15 * 60,
        longBreakEvery = 4, subject = "Maths", fromOrigin = fromOrigin
    )

    @Test
    fun `a block has one id on every device, the watch's own format`() {
        // The watch's WatchTimer.blockId must give the same string.
        assertEquals("$id-b3", sharedBlockId(id, 3))
    }

    @Test
    fun `only letters, digits and dashes name a session`() {
        assertEquals(id, validSessionId(id))
        assertNull(validSessionId("a|b|c|d|e"))
        assertNull(validSessionId("short"))
        assertNull(validSessionId(null))
    }

    @Test
    fun `a session is joined mid-phase, not in its last seconds or once over`() {
        assertTrue(session().joinableAt(now))
        assertFalse(session(endsIn = JOIN_MARGIN_MILLIS - 1).joinableAt(now))
        assertFalse(session(endsIn = -1_000L).joinableAt(now))
        // Its last block done, or a shape no timer could run.
        assertFalse(session(completed = 4).joinableAt(now))
        assertFalse(session(target = 0).joinableAt(now))
        assertFalse(session().copy(shortBreakSeconds = 0).joinableAt(now))
        assertFalse(session().copy(id = "x").joinableAt(now))
    }

    @Test
    fun `joining takes up the session where it is, with a fresh tree`() {
        val saved = session(phase = PomodoroPhase.SHORT_BREAK, completed = 2).toSaved()
        assertEquals(PomodoroPhase.SHORT_BREAK, saved.phase)
        assertEquals(2, saved.completedFocusSessions)
        assertEquals(now + 10 * 60_000L, saved.phaseEndsAtMillis)
        assertEquals(id, saved.sessionId)
        assertEquals(SharedSession.ORIGIN_WATCH, saved.origin)
        assertFalse(saved.treeWithered)
    }

    @Test
    fun `the whole session ends after every block and break still to come`() {
        // Block 2 of 4 with 10 min left: +5 break, +25, +5, +25.
        assertEquals(now + (10 + 5 + 25 + 5 + 25) * 60_000L, session().endsAtMillis())
        assertEquals(now + 10 * 60_000L, session(completed = 3).endsAtMillis())
    }

    @Test
    fun `a follower moves only to the origin's copy, forwards, and for a real difference`() {
        val local = sessionPosition(PomodoroPhase.SHORT_BREAK, 1)
        val localEnds = now + 4 * 60_000L
        // The watch skipped the break: it is on the next block.
        assertTrue(realignTo(session(), id, local, localEnds, now))
        // The same phase, a second apart: nothing to do.
        val same = session(phase = PomodoroPhase.SHORT_BREAK, endsIn = 4 * 60_000L + 1_000L)
        assertFalse(realignTo(same, id, local, localEnds, now))
        // The same phase, ended late on the watch: follow it.
        assertTrue(realignTo(same.copy(phaseEndsAtMillis = localEnds + 40_000L), id, local, localEnds, now))
        // A stale copy from the block before must not replay its end.
        assertFalse(realignTo(session(completed = 0), id, local, localEnds, now))
        // Another follower's copy, or another session, moves nothing.
        assertFalse(realignTo(session(fromOrigin = false), id, local, localEnds, now))
        assertFalse(realignTo(session(), "another-session-id", local, localEnds, now))
    }

    @Test
    fun `position counts blocks and breaks in order`() {
        assertEquals(0, sessionPosition(PomodoroPhase.FOCUS, 0))
        assertEquals(1, sessionPosition(PomodoroPhase.SHORT_BREAK, 1))
        assertEquals(2, sessionPosition(PomodoroPhase.FOCUS, 1))
        assertEquals(5, sessionPosition(PomodoroPhase.LONG_BREAK, 3))
    }

    @Test
    fun `a session survives an intent extra, a subject with the separator in it too`() {
        val original = session().copy(subject = "A|B")
        assertEquals(original, decodeShared(encodeShared(original)))
        assertEquals(session().copy(subject = null), decodeShared(encodeShared(session().copy(subject = null))))
        assertNull(decodeShared("nonsense"))
        assertNull(decodeShared(null))
    }

    @Test
    fun `document fields read back, and tell the origin's copy from a relayed one`() {
        val phone = session(origin = "install-1")
        // Firestore hands numbers back as Long.
        val fields = sharedFields(phone).mapValues { (_, v) -> if (v is Int) v.toLong() else v }
        assertEquals(phone.copy(fromOrigin = true), sharedFromFields(fields, writer = "install-1"))
        assertEquals(phone.copy(fromOrigin = false), sharedFromFields(fields, writer = "install-2"))
        // A document with no session in it: only an end time.
        assertNull(sharedFromFields(mapOf("endsAt" to 5L, "installId" to "install-1"), writer = "install-1"))
    }

    @Test
    fun `the origin's copy is believed over a relayed one, then the one further on`() {
        val relayed = session(fromOrigin = false, completed = 2)
        val own = session(completed = 1)
        assertEquals(own, pickShared(relayed, own))
        assertEquals(own, pickShared(own, relayed))
        assertEquals(relayed, pickShared(relayed, session(fromOrigin = false, completed = 1)))
        assertEquals(own, pickShared(null, own))
        assertNull(pickShared(null, null))
    }

    @Test
    fun `a saved session keeps its id and origin, and one saved before them still loads`() {
        val saved = session().toSaved()
        assertEquals(saved, decodeSession(encodeSession(saved)))
        val old = decodeSession("1500|300|900|4|4|1|FOCUS|${now}|0|1500")!!
        assertEquals("", old.sessionId)
        assertEquals(SharedSession.ORIGIN_SELF, old.origin)
    }
}

class PeerNewsTest {
    private val now = 1_800_000_000_000L

    private fun session(
        phase: PomodoroPhase = PomodoroPhase.FOCUS,
        completed: Int = 0,
        endsIn: Long = 25 * 60_000L,
        id: String = "0f8fad5b-d9cb"
    ) = SharedSession(
        id = id, origin = SharedSession.ORIGIN_WATCH, phase = phase, phaseEndsAtMillis = now + endsIn,
        completedFocus = completed, targetFocusBlocks = 2, focusSeconds = 25 * 60,
        shortBreakSeconds = 5 * 60, longBreakSeconds = 15 * 60, longBreakEvery = 4
    )

    @Test
    fun `a session heard of in its first minute has started, one caught up with later has not`() {
        assertEquals(PeerNews.STARTED, peerNews(null, session(), now))
        assertNull(peerNews(null, session(endsIn = 10 * 60_000L), now))
        assertNull(peerNews(null, session(phase = PomodoroPhase.SHORT_BREAK, completed = 1, endsIn = 5 * 60_000L), now))
    }

    @Test
    fun `only a copy further on is a phase change`() {
        val focus = session()
        val rest = session(phase = PomodoroPhase.SHORT_BREAK, completed = 1, endsIn = 5 * 60_000L)
        assertEquals(PeerNews.INTO_BREAK, peerNews(focus, rest, now))
        assertEquals(PeerNews.INTO_FOCUS, peerNews(rest, session(completed = 1), now))
        // The second copy of the same phase, a realign, an older copy.
        assertNull(peerNews(rest, rest.copy(phaseEndsAtMillis = now + 1_000L), now))
        assertNull(peerNews(rest, focus, now))
    }

    @Test
    fun `a session gone at its last block's end finished, gone at any other time says nothing`() {
        val last = session(completed = 1, endsIn = 2_000L)
        assertEquals(PeerNews.FINISHED, peerNews(last, null, now))
        assertNull(peerNews(session(completed = 1), null, now))
        assertNull(peerNews(session(endsIn = 2_000L), null, now))
        assertNull(peerNews(null, null, now))
    }
}
