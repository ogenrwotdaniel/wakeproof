package com.ogenrwotdaniel.wakeproof.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day-splitting for the usage screen. The screen used to read whole aggregation
 * buckets, which are not clipped to the queried range, so "today" quietly
 * included yesterday — see [foregroundMillisPerSpan]'s KDoc.
 */
class ForegroundMillisPerSpanTest {

    private val ig = "com.instagram.android"
    private val chrome = "com.android.chrome"

    private fun resumed(pkg: String, at: Long, cls: String = "Main") =
        ForegroundEvent(pkg, cls, resumed = true, timestamp = at)

    private fun paused(pkg: String, at: Long, cls: String = "Main") =
        ForegroundEvent(pkg, cls, resumed = false, timestamp = at)

    private val day = 24 * 60 * 60_000L

    @Test
    fun `usage lands in the day it happened, not the day either side`() {
        val spans = foregroundMillisPerSpan(
            events = listOf(
                resumed(ig, 10_000), paused(ig, 70_000),          // day 0: 60 s
                resumed(ig, day + 10_000), paused(ig, day + 40_000) // day 1: 30 s
            ),
            edges = listOf(0L, day, 2 * day),
            now = 2 * day
        )
        assertEquals(60_000L, spans[0][ig])
        assertEquals(30_000L, spans[1][ig])
    }

    @Test
    fun `a session running across midnight is split between both days`() {
        // Phone still in hand at midnight: open 23:50, closed 00:20.
        val spans = foregroundMillisPerSpan(
            events = listOf(
                resumed(ig, day - 600_000),   // 23:50 on day 0
                paused(ig, day + 1_200_000)   // 00:20 on day 1
            ),
            edges = listOf(0L, day, 2 * day),
            now = 2 * day
        )
        assertEquals("10 minutes before midnight", 600_000L, spans[0][ig])
        assertEquals("20 minutes after midnight", 1_200_000L, spans[1][ig])
    }

    @Test
    fun `folding spans in isolation would lose the post-midnight half — this does not`() {
        // The later span contains no resume event at all, only the pause. Folding
        // it alone yields nothing; the cumulative subtraction recovers it.
        val events = listOf(resumed(chrome, day - 60_000), paused(chrome, day + 60_000))
        val isolated = foldForegroundMillis(events.filter { it.timestamp >= day }, 2 * day)
        assertNull("isolated fold cannot see the session", isolated[chrome])

        val spans = foregroundMillisPerSpan(events, listOf(0L, day, 2 * day), now = 2 * day)
        assertEquals(60_000L, spans[1][chrome])
    }

    @Test
    fun `an open session counts up to now and no further`() {
        val now = day + 120_000
        val spans = foregroundMillisPerSpan(
            events = listOf(resumed(ig, day + 60_000)),
            edges = listOf(0L, day, day + day),
            now = now
        )
        assertEquals(0, spans[0].size)
        assertEquals(60_000L, spans[1][ig])
    }

    @Test
    fun `future edges contribute nothing`() {
        val now = day / 2
        val spans = foregroundMillisPerSpan(
            events = listOf(resumed(ig, 0), paused(ig, 60_000)),
            edges = listOf(0L, day, 2 * day, 3 * day),
            now = now
        )
        assertEquals(60_000L, spans[0][ig])
        assertTrue("nothing after now", spans.drop(1).all { it.isEmpty() })
    }

    @Test
    fun `packages are kept apart`() {
        val spans = foregroundMillisPerSpan(
            events = listOf(
                resumed(ig, 10_000), paused(ig, 40_000),
                resumed(chrome, 50_000), paused(chrome, 110_000)
            ),
            edges = listOf(0L, day),
            now = day
        )
        assertEquals(30_000L, spans[0][ig])
        assertEquals(60_000L, spans[0][chrome])
    }
}
