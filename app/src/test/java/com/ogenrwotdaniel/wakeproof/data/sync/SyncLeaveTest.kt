package com.ogenrwotdaniel.wakeproof.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLeaveTest {

    private val now = 1_000L * SyncLeave.MILLIS

    @Test
    fun `leaving sync goes through only a full week after asking`() {
        assertFalse(syncLeaveReady(null, now))
        assertFalse(syncLeaveReady(now - SyncLeave.MILLIS + 1, now))
        assertTrue(syncLeaveReady(now - SyncLeave.MILLIS, now))
    }

    @Test
    fun `a request stamped in the future is not ready`() {
        assertFalse(syncLeaveReady(now + 1, now))
    }
}
