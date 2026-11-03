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

    @Test
    fun `a pinned app that would be walled is covered, whatever the wall`() {
        GateVerdict.entries.filter { it != GateVerdict.ALLOW }.forEach {
            assertTrue(it.name, pinCoverWanted(userPinned = true, verdict = it))
        }
    }

    @Test
    fun `the pin cover never covers an allowed app or an unpinned one`() {
        assertFalse(pinCoverWanted(userPinned = true, verdict = GateVerdict.ALLOW))
        GateVerdict.entries.forEach {
            assertFalse(it.name, pinCoverWanted(userPinned = false, verdict = it))
        }
    }

    @Test
    fun `a waiting or walled app is held suspended, an allowed one is not`() {
        GateVerdict.entries.filter { it != GateVerdict.ALLOW }.forEach {
            assertTrue(it.name, holdSuspended(neverSuspend = false, verdict = it))
        }
        assertFalse(holdSuspended(neverSuspend = false, verdict = GateVerdict.ALLOW))
    }

    @Test
    fun `the keyboard, SMS and essentials are never suspended`() {
        GateVerdict.entries.forEach {
            assertFalse(it.name, holdSuspended(neverSuspend = true, verdict = it))
        }
    }
}
