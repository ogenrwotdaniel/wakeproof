package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
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
    fun `the cover is up from the wall's first moment, and gone once the app has left`() {
        assertTrue(wanted(age = 0L))
        assertTrue(wanted(age = 200L))
        // Before the wall is in front, too, but only in the first moments and never over an alarm.
        assertTrue(popupCoverWanted(200L, ownWallInFront = false, walledStillResumed = true, inGrace = false))
        assertFalse(popupCoverWanted(COVER_SETTLE_MILLIS, ownWallInFront = false, walledStillResumed = true, inGrace = false))
        assertFalse(
            popupCoverWanted(200L, ownWallInFront = false, walledStillResumed = true, inGrace = false, alarmOnScreen = true)
        )
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
    fun `everything beside the front app is known, and a phantom can be cleared`() {
        val state = ResumedActivities()
        state.onResumed("popup.app", "A", 1L)
        state.onResumed("front.app", "B", 2L)
        assertTrue(state.isResumed("front.app", "B"))
        assertFalse(state.isResumed("front.app", "A"))
        assertEquals(setOf("popup.app", "front.app"), state.packages())
        state.clear()
        assertTrue(state.packages().isEmpty())
        assertEquals(null, state.foreground())
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

    private fun mayLand(front: String, alarm: Boolean = false) = wallMayLandOver(
        front = front,
        walled = "walled",
        ownPackage = "own",
        alarmOnScreen = alarm,
        essentials = setOf("own", "dialer", "home"),
        homes = setOf("home")
    )

    @Test
    fun `a wall lands over ordinary apps and the home screen`() {
        assertTrue(mayLand("ordinary"))
        assertTrue(mayLand("home"))
    }

    @Test
    fun `a wall never lands on a call, or on itself`() {
        assertFalse(mayLand("dialer"))
        assertFalse(mayLand("walled"))
    }

    @Test
    fun `a ring holds the walls while its screen comes up`() {
        assertTrue(alarmHoldsWalls(ringing = true, screenInFront = false, ringAgeMillis = 0L))
        assertTrue(alarmHoldsWalls(true, false, ALARM_SCREEN_SETTLE_MILLIS - 1))
    }

    @Test
    fun `leaving a ringing alarm's screen is still walled`() {
        assertFalse(alarmHoldsWalls(true, false, ALARM_SCREEN_SETTLE_MILLIS))
    }

    @Test
    fun `the alarm's screen holds them with or without the sound`() {
        assertTrue(alarmHoldsWalls(true, true, ALARM_SCREEN_SETTLE_MILLIS))
        assertTrue(alarmHoldsWalls(false, true, ALARM_SCREEN_SETTLE_MILLIS))
    }

    @Test
    fun `a repeat over WakeProof's screens waits longer each time, up to a limit`() {
        val gaps = (0..8).map(::besideRepeatGap)
        assertEquals(BESIDE_CONFIRM_MILLIS, gaps.first())
        assertEquals(gaps.sorted(), gaps)
        assertEquals(COVER_MAX_MILLIS, gaps.last())
    }

    @Test
    fun `a new resume of an app already resumed shows in its last resume`() {
        val state = ResumedActivities()
        state.onResumed("popup.app", "A", 1L)
        state.onResumed("popup.app", "A", 9L)
        assertEquals(9L, state.lastResumedAt("popup.app"))
        assertEquals(1L, state.resumedSince("popup.app"))
    }

    @Test
    fun `over WakeProof anything but the alarm`() {
        assertTrue(mayLand("own"))
        assertFalse(mayLand("own", alarm = true))
    }

    @Test
    fun `a cap beside the front app is not read again before it could be spent`() {
        assertEquals(20 * 60_000L, nextBesideCapReadIn(usedMillis = 10 * 60_000L, limitMillis = 30 * 60_000L))
    }

    @Test
    fun `a cap nearly spent is still read at most twice a minute`() {
        assertEquals(BESIDE_CONFIRM_MILLIS, nextBesideCapReadIn(usedMillis = 29 * 60_000L + 59_000L, limitMillis = 30 * 60_000L))
        assertEquals(BESIDE_CONFIRM_MILLIS, nextBesideCapReadIn(usedMillis = 40 * 60_000L, limitMillis = 30 * 60_000L))
    }

    @Test
    fun `only a slow verdict looks at the front app again`() {
        assertFalse(frontMayHaveChanged(startedAt = 1_000L, endedAt = 1_000L + SLOW_VERDICT_MILLIS - 1))
        assertTrue(frontMayHaveChanged(startedAt = 1_000L, endedAt = 1_000L + SLOW_VERDICT_MILLIS))
    }

    @Test
    fun `the app just walled is left to the cover while its wall is in front`() {
        assertTrue(leftToCover("popup", coverFor = "popup", coverAgeMillis = 0L, ownFront = true))
        assertTrue(leftToCover("popup", "popup", COVER_MAX_MILLIS, ownFront = true))
    }

    @Test
    fun `a pop-up left beside another app is walled again at once`() {
        assertFalse(leftToCover("popup", coverFor = "popup", coverAgeMillis = 0L, ownFront = false))
    }

    @Test
    fun `the cover lets go at its limit, and never holds another app`() {
        assertFalse(leftToCover("popup", "popup", COVER_MAX_MILLIS + 1, ownFront = true))
        assertFalse(leftToCover("other", "popup", 0L, ownFront = true))
        assertFalse(leftToCover("popup", null, 0L, ownFront = true))
    }

    @org.junit.Test
    fun `let me close it is offered once per app in ten minutes`() {
        org.junit.Assert.assertTrue(coverGraceOffered(lastUsedAt = null, now = 5_000L))
        org.junit.Assert.assertFalse(coverGraceOffered(lastUsedAt = 5_000L, now = 5_000L + COVER_GRACE_MILLIS))
        org.junit.Assert.assertFalse(coverGraceOffered(lastUsedAt = 5_000L, now = 5_000L + COVER_GRACE_EVERY_MILLIS - 1))
        org.junit.Assert.assertTrue(coverGraceOffered(lastUsedAt = 5_000L, now = 5_000L + COVER_GRACE_EVERY_MILLIS))
    }
}
