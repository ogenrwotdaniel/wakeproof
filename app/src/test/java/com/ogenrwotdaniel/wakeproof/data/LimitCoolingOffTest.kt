package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository.Companion.encodePending
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository.Companion.isLoosening
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository.Companion.movedOntoWeek
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository.Companion.parsePending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitCoolingOffTest {

    private val block30 = AppLimit(30, LimitMode.BLOCK)

    @Test
    fun `a brand new cap is never loosening`() {
        assertFalse(isLoosening(null, AppLimit(30)))
        assertFalse(isLoosening(null, null))
    }

    @Test
    fun `removing, raising or softening a cap is loosening`() {
        assertTrue(isLoosening(block30, null))
        assertTrue(isLoosening(block30, AppLimit(31, LimitMode.BLOCK)))
        assertTrue(isLoosening(block30, AppLimit(30, LimitMode.NUDGE)))
    }

    @Test
    fun `more minutes is loosening even when it arrives with a switch to block`() {
        assertTrue(isLoosening(AppLimit(30, LimitMode.NUDGE), AppLimit(60, LimitMode.BLOCK)))
    }

    @Test
    fun `fewer minutes, the same cap, or nudge to block applies at once`() {
        assertFalse(isLoosening(block30, AppLimit(10, LimitMode.BLOCK)))
        assertFalse(isLoosening(block30, block30))
        assertFalse(isLoosening(AppLimit(30, LimitMode.NUDGE), block30))
    }

    @Test
    fun `pending changes survive an encode and parse round trip, removals included`() {
        val pending = mapOf(
            "com.instagram.android" to PendingLimitChange(AppLimit(60, LimitMode.NUDGE), 1_000L),
            "com.zhiliaoapp.musically" to PendingLimitChange(null, 2_000L)
        )
        assertEquals(pending, parsePending(encodePending(pending)))
    }

    @Test
    fun `corrupt pending entries are dropped so the stricter current cap stands`() {
        val parsed = parsePending(
            setOf("pkg|60|NUDGE", "pkg|x|NUDGE|5", "pkg|60|LOOSE|5", "|60|NUDGE|5", "ok|15|BLOCK|9")
        )
        assertEquals(mapOf("ok" to PendingLimitChange(AppLimit(15, LimitMode.BLOCK), 9L)), parsed)
    }

    private val hour = 60L * 60 * 1000

    @Test
    fun `a loosening queued under the old 24 h wait now lands a week after it was asked for`() {
        val requested = 1_000_000L
        assertEquals(requested + 168 * hour, movedOntoWeek(requested + 24 * hour, 24))
        assertEquals(requested + 168 * hour, movedOntoWeek(requested + 48 * hour, 48))
    }

    @Test
    fun `moving onto the week never brings a change forward`() {
        // A nonsense stored wait longer than a week must not make it land sooner.
        assertEquals(500 * hour, movedOntoWeek(500 * hour, 400))
    }
}
