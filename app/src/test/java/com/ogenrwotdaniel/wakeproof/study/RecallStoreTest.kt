package com.ogenrwotdaniel.wakeproof.study

import com.ogenrwotdaniel.wakeproof.plan.DayReview
import com.ogenrwotdaniel.wakeproof.plan.DayReviews
import com.ogenrwotdaniel.wakeproof.plan.PlanDone
import com.ogenrwotdaniel.wakeproof.plan.cleanTopThree
import com.ogenrwotdaniel.wakeproof.plan.topThreeFor
import com.ogenrwotdaniel.wakeproof.plan.withRestoredReviews
import com.ogenrwotdaniel.wakeproof.plan.withReview
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
    fun `tomorrow's top three is trimmed, without blanks, at most three`() {
        assertEquals(listOf("Lab report", "Ch. 4"), cleanTopThree(listOf(" Lab report ", "", "Ch. 4")))
        assertEquals(3, cleanTopThree(listOf("a", "b", "c", "d")).size)
    }

    @Test
    fun `a day's review replaces an earlier one, and today's top three is yesterday evening's`() {
        var all = withReview(DayReviews(), DayReview(100, PlanDone.SOME, listOf("old")))
        all = withReview(all, DayReview(99, PlanDone.ALL, listOf("x")))
        all = withReview(all, DayReview(100, PlanDone.MOST, listOf("Lab report", "Ch. 4")))
        assertEquals(listOf(100L, 99L), all.reviews.map { it.epochDay })
        assertEquals(PlanDone.MOST, all.reviews.first().done)
        assertEquals(listOf("Lab report", "Ch. 4"), topThreeFor(all, day = 101))
        assertTrue(topThreeFor(all, day = 103).isEmpty())
    }

    @Test
    fun `a restored backup wins the days and notes it holds and leaves the rest`() {
        val here = DayReviews(
            listOf(DayReview(11, PlanDone.SOME, listOf("kept")), DayReview(10, PlanDone.NONE, listOf("old")))
        )
        val merged = withRestoredReviews(
            here, listOf(DayReview(10, PlanDone.ALL, listOf("from backup")), DayReview(9, PlanDone.MOST))
        )
        assertEquals(listOf(11L, 10L, 9L), merged.reviews.map { it.epochDay })
        assertEquals(listOf("from backup"), merged.reviews.first { it.epochDay == 10L }.topThree)
        assertEquals(listOf("kept"), merged.reviews.first { it.epochDay == 11L }.topThree)

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
