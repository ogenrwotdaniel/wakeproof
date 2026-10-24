package com.ogenrwotdaniel.wakeproof.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWatchTimerRulesTest {

    private val now = 1_000_000_000L

    private fun timer(
        active: Boolean = true,
        running: Boolean = true,
        phase: String = "FOCUS",
        phaseEndsAt: Long = now + 90_000L,
        remainingMillis: Long = 0L,
        completedFocus: Int = 1,
        target: Int = 0,
        serverUpdatedAt: Long? = now
    ) = LiveWatchTimer(active, running, phase, phaseEndsAt, remainingMillis, completedFocus, target, null, serverUpdatedAt)

    @Test
    fun `reads the keys the watch writes`() {
        // Mirrors the watch's CloudContract.T_* names; a rename on one side only
        // would make every document unreadable here.
        val parsed = LiveWatchTimer.fromFields(
            mapOf(
                "active" to true, "running" to false, "phase" to "LONG_BREAK",
                "phaseEndsAt" to 5L, "remainingMillis" to 60_000L, "completedFocus" to 4L,
                "targetFocusBlocks" to 4L, "subject" to "Maths", "focusMinutes" to 25L
            ),
            serverUpdatedAtMillis = 7L
        )
        assertEquals(LiveWatchTimer(true, false, "LONG_BREAK", 5L, 60_000L, 4, 4, "Maths", 7L), parsed)
    }

    @Test
    fun `an unreadable document is null, not a crash`() {
        assertNull(LiveWatchTimer.fromFields(mapOf("running" to true), null))
    }

    @Test
    fun `idle shows nothing`() {
        assertNull(LiveWatchTimerRules.display(null, now))
        assertNull(LiveWatchTimerRules.display(timer(active = false), now))
    }

    @Test
    fun `running focus counts down rounded up and names the block in progress`() {
        val d = LiveWatchTimerRules.display(timer(phaseEndsAt = now + 90_001L, target = 4), now)!!
        assertEquals(91L, d.remainingSeconds)
        assertEquals("Focus", d.phaseLabel)
        assertEquals("Block 2 of 4", d.blockLabel)
        assertFalse(d.awaitingWatch)
    }

    @Test
    fun `a break names the block just finished`() {
        val d = LiveWatchTimerRules.display(timer(phase = "SHORT_BREAK", completedFocus = 2), now)!!
        assertEquals("Short break", d.phaseLabel)
        assertEquals("Block 2", d.blockLabel)
    }

    @Test
    fun `a phase just over waits for the watch, a long-overdue one disappears`() {
        val justOver = LiveWatchTimerRules.display(timer(phaseEndsAt = now - 30_000L), now)!!
        assertTrue(justOver.awaitingWatch)
        assertEquals(0L, justOver.remainingSeconds)
        assertNull(
            LiveWatchTimerRules.display(
                timer(phaseEndsAt = now - LiveWatchTimerRules.RUNNING_STALE_MILLIS - 1), now
            )
        )
    }

    @Test
    fun `paused shows the frozen remainder until it is abandoned`() {
        val d = LiveWatchTimerRules.display(timer(running = false, remainingMillis = 120_000L), now)!!
        assertFalse(d.running)
        assertEquals(120L, d.remainingSeconds)
        assertNull(
            LiveWatchTimerRules.display(
                timer(running = false, serverUpdatedAt = now - LiveWatchTimerRules.PAUSED_STALE_MILLIS - 1),
                now
            )
        )
    }
}
