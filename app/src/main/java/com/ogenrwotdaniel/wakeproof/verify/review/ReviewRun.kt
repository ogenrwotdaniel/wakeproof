package com.ogenrwotdaniel.wakeproof.verify.review

import com.ogenrwotdaniel.wakeproof.data.ReviewGrade
import com.ogenrwotdaniel.wakeproof.data.subjectMatchesExam
import com.ogenrwotdaniel.wakeproof.data.db.Flashcard

/** Cards a review alarm asks for, when that many are due; more before an exam ([com.ogenrwotdaniel.wakeproof.data.reviewCardsOn]). */
const val REVIEW_ALARM_CARDS = 10

/** How long a card's question stands before its answer can be shown. */
const val REVIEW_THINK_MILLIS = 3_000L

/**
 * One review alarm's run of due flashcards: [queue] is what is left, its head
 * the card on screen, and [done] of [required] have been answered.
 */
data class ReviewRun(val queue: List<Flashcard>, val required: Int, val done: Int = 0) {
    val current: Flashcard? get() = queue.firstOrNull()

    /** Nothing left to answer. An empty run (no card due) is finished from the start. */
    val finished: Boolean get() = queue.isEmpty()
}

/**
 * Pure: [due] with the cards of a subject under exam ([examNames], see
 * [subjectMatchesExam]) first; otherwise the order is kept, longest overdue
 * first.
 */
fun examCardsFirst(due: List<Flashcard>, examNames: List<String>): List<Flashcard> =
    if (examNames.isEmpty()) due
    else due.sortedBy { card -> if (examNames.any { subjectMatchesExam(card.subject, it) }) 0 else 1 }

/** Pure: the run a review alarm starts with, at most [max] of the [due] cards. */
fun reviewRunOf(due: List<Flashcard>, max: Int = REVIEW_ALARM_CARDS): ReviewRun {
    val cards = due.take(max.coerceAtLeast(0))
    return ReviewRun(queue = cards, required = cards.size)
}

/**
 * Pure: the run after its head card was graded and stored as [stored].
 *
 * AGAIN does not count: the card goes to the back and comes round once more,
 * so the alarm ends on cards that were recalled, not on ones tapped past.
 */
fun ReviewRun.graded(grade: ReviewGrade, stored: Flashcard): ReviewRun {
    if (queue.isEmpty()) return this
    val rest = queue.drop(1)
    return if (grade == ReviewGrade.AGAIN) copy(queue = rest + stored)
    else copy(queue = rest, done = done + 1)
}
