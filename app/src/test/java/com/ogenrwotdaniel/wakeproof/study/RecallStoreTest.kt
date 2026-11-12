package com.ogenrwotdaniel.wakeproof.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallStoreTest {

    private val asked = RecallState(pending = PendingRecall("Physics", 20_000L, askedAtMillis = 1_000L))

    @Test
    fun `a recall is asked for half an hour, then let go`() {
        assertEquals("Physics", recallDue(asked, 1_000L + 60_000L)?.subject)
        assertNull(recallDue(asked, 1_000L + RECALL_ASK_FOR_MILLIS + 1))
        // A clock set back must not keep an old ask alive for ever.
        assertNull(recallDue(asked, 0L))
        assertNull(recallDue(RecallState(), 5_000L))
    }

    @Test
    fun `an answer is kept under the block's subject and day, and the ask is cleared`() {
        val after = withRecall(asked, "  F = ma; mass resists acceleration  ", id = "n1", now = 9_000L)
        assertNull(after.pending)
        assertEquals(
            listOf(RecallNote("n1", "F = ma; mass resists acceleration", "Physics", 20_000L, 9_000L)),
            after.notes
        )
    }

    @Test
    fun `a blank answer saves nothing, and an answer nobody asked for is ignored`() {
        val blank = withRecall(asked, "   ", id = "n1", now = 9_000L)
        assertNull(blank.pending)
        assertTrue(blank.notes.isEmpty())
        assertEquals(RecallState(), withRecall(RecallState(), "text", id = "n1", now = 9_000L))
    }

    @Test
    fun `a restored backup wins the notes it holds and leaves the rest`() {
        fun note(id: String, text: String, at: Long) = RecallNote(id, text, "Physics", 20_000L, at)
        val state = RecallState(pending = asked.pending, notes = listOf(note("a", "here", 5), note("b", "mine", 9)))
        val notes = withRestoredNotes(state, listOf(note("a", "backup", 5), note("c", "older", 1)))
        // Oldest first, as notes are kept; the ask in progress is untouched.
        assertEquals(listOf("older", "backup", "mine"), notes.notes.map { it.text })
        assertEquals(asked.pending, notes.pending)
        // A note discarded here stays discarded when an older backup still holds it.
        val after = withRestoredNotes(withoutNote(notes, "c"), listOf(note("c", "older", 1), note("d", "new", 2)))
        assertEquals(listOf("new", "backup", "mine"), after.notes.map { it.text })
    }
}
