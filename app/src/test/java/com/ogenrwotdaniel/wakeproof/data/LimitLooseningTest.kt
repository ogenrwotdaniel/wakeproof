package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository.Companion.isLoosening
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as loosening a cap, which [DistractionLimitRepository.requestLimit] refuses. */
class LimitLooseningTest {

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
}
