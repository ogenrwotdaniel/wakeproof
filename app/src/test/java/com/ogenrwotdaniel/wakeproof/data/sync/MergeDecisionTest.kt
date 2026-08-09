package com.ogenrwotdaniel.wakeproof.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** The conflict rule that decides whether a downloaded row overwrites a local one. */
class MergeDecisionTest {

    @Test
    fun `row this device has never seen is inserted`() {
        assertEquals(
            MergeDecision.INSERT,
            mergeDecision(localUpdatedAt = null, remoteUpdatedAt = 100, remoteDeleted = false)
        )
    }

    @Test
    fun `newer remote overwrites local`() {
        assertEquals(
            MergeDecision.UPDATE,
            mergeDecision(localUpdatedAt = 100, remoteUpdatedAt = 200, remoteDeleted = false)
        )
    }

    @Test
    fun `older remote loses to local`() {
        assertEquals(
            MergeDecision.IGNORE,
            mergeDecision(localUpdatedAt = 200, remoteUpdatedAt = 100, remoteDeleted = false)
        )
    }

    /** A device re-reads its own uploads; they must not be rewritten every pass. */
    @Test
    fun `equal timestamps keep the local copy`() {
        assertEquals(
            MergeDecision.IGNORE,
            mergeDecision(localUpdatedAt = 100, remoteUpdatedAt = 100, remoteDeleted = false)
        )
    }

    /** Otherwise every device accumulates tombstones it can never act on. */
    @Test
    fun `tombstone for an unknown row is not materialised`() {
        assertEquals(
            MergeDecision.IGNORE,
            mergeDecision(localUpdatedAt = null, remoteUpdatedAt = 100, remoteDeleted = true)
        )
    }

    /** A delete elsewhere must still reach a row this device does have. */
    @Test
    fun `tombstone newer than a known row deletes it`() {
        assertEquals(
            MergeDecision.UPDATE,
            mergeDecision(localUpdatedAt = 100, remoteUpdatedAt = 200, remoteDeleted = true)
        )
    }

    /** A local edit made after the delete wins, so the row comes back. */
    @Test
    fun `stale tombstone does not undo a newer local edit`() {
        assertEquals(
            MergeDecision.IGNORE,
            mergeDecision(localUpdatedAt = 300, remoteUpdatedAt = 200, remoteDeleted = true)
        )
    }
}
