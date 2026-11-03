package com.ogenrwotdaniel.wakeproof.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseStateTest {
    private val day = DeviceLock.RELEASE_DELAY_MILLIS

    @Test
    fun `nothing asked, nothing pending`() {
        assertEquals(ReleaseState.NOT_REQUESTED, releaseState(null, 5L))
    }

    @Test
    fun `the release waits the full ninety days`() {
        assertEquals(90L * 24 * 60 * 60 * 1000, DeviceLock.RELEASE_DELAY_MILLIS)
        assertEquals(ReleaseState.WAITING, releaseState(1_000L, 1_000L + day - 1))
        assertEquals(ReleaseState.READY, releaseState(1_000L, 1_000L + day))
    }

    @Test
    fun `winding the clock back never shortens the wait`() {
        assertEquals(ReleaseState.WAITING, releaseState(10 * day, 2 * day))
    }
}
