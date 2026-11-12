package com.ogenrwotdaniel.wakeproof.verify.review

import com.ogenrwotdaniel.wakeproof.data.ReviewGrade
import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRunTest {

    private fun card(id: Long) = Flashcard(
        id = id, subject = "Calculus", front = "q$id", back = "a$id",
        dueEpochDay = 20_000L, createdEpochDay = 19_990L, syncId = "c$id"
    )

    private fun cards(n: Int) = (1..n).map { card(it.toLong()) }

    @Test
    fun `a run asks for at most the alarm's share of the due cards`() {
        val run = reviewRunOf(cards(25))
        assertEquals(REVIEW_ALARM_CARDS, run.required)
        assertEquals((1L..10L).toList(), run.queue.map { it.id })
        assertFalse(run.finished)
    }

    @Test
    fun `fewer due cards make a shorter run, and none makes a finished one`() {
        assertEquals(3, reviewRunOf(cards(3)).required)
        val empty = reviewRunOf(emptyList())
        assertEquals(0, empty.required)
        // What sends the alarm to the sums instead.
        assertTrue(empty.finished)
    }

    @Test
    fun `each recalled card moves the run on, and the last one ends it`() {
        var run = reviewRunOf(cards(2))
        run = run.graded(ReviewGrade.GOOD, run.current!!)
        assertEquals(1, run.done)
        assertEquals(2L, run.current?.id)
        assertFalse(run.finished)
        run = run.graded(ReviewGrade.HARD, run.current!!)
        assertEquals(2, run.done)
        assertTrue(run.finished)
    }

    @Test
    fun `a card graded Again comes round once more and does not count`() {
        var run = reviewRunOf(cards(2))
        val reset = card(1).copy(repetitions = 0, easiness = 1.7)
        run = run.graded(ReviewGrade.AGAIN, reset)
        assertEquals(0, run.done)
        assertEquals(listOf(2L, 1L), run.queue.map { it.id })
        // The copy that was stored is the one graded next time, not the stale one.
        assertEquals(1.7, run.queue.last().easiness, 0.0)
        run = run.graded(ReviewGrade.EASY, run.current!!)
        assertFalse(run.finished)
        run = run.graded(ReviewGrade.GOOD, run.current!!)
        assertEquals(2, run.done)
        assertTrue(run.finished)
    }

    @Test
    fun `cards of a subject under exam come first, the rest keep their order`() {
        val due = listOf(card(1), card(2).copy(subject = "Physics"), card(3), card(4).copy(subject = "Physics"))
        assertEquals(listOf(2L, 4L, 1L, 3L), examCardsFirst(due, listOf("Physics final")).map { it.id })
        assertEquals(due, examCardsFirst(due, emptyList()))
        // More cards before an exam: the run takes what it is told to.
        assertEquals(3, reviewRunOf(due, max = 3).required)
    }

    @Test
    fun `grading a finished run changes nothing`() {
        val done = reviewRunOf(emptyList())
        assertEquals(done, done.graded(ReviewGrade.GOOD, card(9)))
    }
}
