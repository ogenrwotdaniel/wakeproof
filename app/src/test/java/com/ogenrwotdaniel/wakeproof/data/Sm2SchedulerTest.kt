package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sm2SchedulerTest {

    private val today = 20_000L

    private fun newCard() = Flashcard(
        subject = "Math", front = "f", back = "b",
        dueEpochDay = today, createdEpochDay = today
    )

    @Test
    fun `first good review schedules one day out`() {
        val reviewed = Sm2Scheduler.review(newCard(), ReviewGrade.GOOD, today)
        assertEquals(1, reviewed.repetitions)
        assertEquals(1, reviewed.intervalDays)
        assertEquals(today + 1, reviewed.dueEpochDay)
    }

    @Test
    fun `second good review schedules six days out`() {
        var card = Sm2Scheduler.review(newCard(), ReviewGrade.GOOD, today)
        card = Sm2Scheduler.review(card, ReviewGrade.GOOD, today + 1)
        assertEquals(6, card.intervalDays)
        assertEquals(today + 1 + 6, card.dueEpochDay)
    }

    @Test
    fun `third review multiplies interval by easiness`() {
        var card = Sm2Scheduler.review(newCard(), ReviewGrade.GOOD, today)
        card = Sm2Scheduler.review(card, ReviewGrade.GOOD, today)
        val before = card
        card = Sm2Scheduler.review(card, ReviewGrade.GOOD, today)
        assertEquals(Math.round(before.intervalDays * before.easiness).toInt(), card.intervalDays)
    }

    @Test
    fun `again resets the streak and keeps the card due today`() {
        var card = Sm2Scheduler.review(newCard(), ReviewGrade.GOOD, today)
        card = Sm2Scheduler.review(card, ReviewGrade.GOOD, today)
        card = Sm2Scheduler.review(card, ReviewGrade.AGAIN, today + 3)
        assertEquals(0, card.repetitions)
        assertEquals(today + 3, card.dueEpochDay)
    }

    @Test
    fun `easy grows easiness and hard shrinks it`() {
        val easy = Sm2Scheduler.review(newCard(), ReviewGrade.EASY, today)
        val hard = Sm2Scheduler.review(newCard(), ReviewGrade.HARD, today)
        assertTrue(easy.easiness > 2.5)
        assertTrue(hard.easiness < 2.5)
    }

    @Test
    fun `easiness never drops below 1_3`() {
        var card = newCard()
        repeat(20) { card = Sm2Scheduler.review(card, ReviewGrade.AGAIN, today) }
        assertTrue(card.easiness >= 1.3)
    }
}
