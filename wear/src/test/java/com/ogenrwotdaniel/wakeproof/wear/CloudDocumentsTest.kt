package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CloudDocumentsTest {

    private val block = FocusBlock(
        id = "b-1",
        startedAt = 1_000L,
        endedAt = 1_500_000L,
        minutes = 25,
        epochDay = 20_380L,
        subject = "Anatomy"
    )

    @Test
    fun `block is written exactly as the phone's sync writes a focus log`() {
        // Field names and values pinned: the phone decodes these in
        // SyncTable("completion_logs"), and an unreadable doc is silently skipped.
        assertEquals(
            mapOf(
                "epochDay" to 20_380L,
                "kind" to "FOCUS",
                "status" to "VERIFIED",
                "amount" to 25,
                "subject" to "Anatomy",
                "syncId" to "b-1",
                "updatedAt" to 1_500_000L,
                "deleted" to false
            ),
            CloudDocuments.block(block)
        )
    }

    @Test
    fun `live timer carries remaining time for a paused phase`() {
        val paused = WatchTimerState(
            active = true,
            running = false,
            phase = WatchPhase.SHORT_BREAK,
            remainingMillis = 90_000L,
            completedFocus = 2,
            targetFocusBlocks = 4
        )
        val doc = CloudDocuments.liveTimer(paused, now = 5_000L)
        assertEquals(true, doc["active"])
        assertEquals(false, doc["running"])
        assertEquals("SHORT_BREAK", doc["phase"])
        assertEquals(90_000L, doc["remainingMillis"])
        assertEquals(2, doc["completedFocus"])
        assertEquals(4, doc["targetFocusBlocks"])
        assertEquals(5_000L, doc["updatedAt"])
    }

    @Test
    fun `live timer key ignores nothing that changes what the tablet shows`() {
        val running = WatchTimerState(active = true, running = true, phaseEndsAt = 10_000L)
        assertEquals(CloudDocuments.liveTimerKey(running), CloudDocuments.liveTimerKey(running.copy()))
        assertNotEquals(CloudDocuments.liveTimerKey(running), CloudDocuments.liveTimerKey(running.copy(running = false)))
        assertNotEquals(CloudDocuments.liveTimerKey(running), CloudDocuments.liveTimerKey(running.copy(phaseEndsAt = 20_000L)))
        assertEquals("idle", CloudDocuments.liveTimerKey(WatchTimerState()))
    }
}
