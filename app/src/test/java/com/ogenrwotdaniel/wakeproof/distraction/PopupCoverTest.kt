package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupCoverTest {

    private fun wanted(
        age: Long? = 5_000L,
        inFront: Boolean = true,
        resumed: Boolean = true,
        grace: Boolean = false
    ) = popupCoverWanted(age, inFront, resumed, grace)

    @Test
    fun `a walled app still running beside the wall is covered`() {
        assertTrue(wanted())
    }

    @Test
    fun `an ordinary full-screen wall never flashes the cover`() {
        assertFalse(wanted(age = 200L))
        assertFalse(wanted(resumed = false))
    }

    @Test
    fun `the cover never outlives its limit, so a lost event cannot brick the phone`() {
        assertTrue(wanted(age = COVER_MAX_MILLIS))
        assertFalse(wanted(age = COVER_MAX_MILLIS + 1))
        assertFalse(wanted(age = null))
    }

    @Test
    fun `the grace and another app in front both lift it`() {
        assertFalse(wanted(grace = true))
        assertFalse(wanted(inFront = false))
    }
}
