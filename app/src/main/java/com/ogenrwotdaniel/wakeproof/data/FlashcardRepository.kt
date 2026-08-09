package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import com.ogenrwotdaniel.wakeproof.data.db.FlashcardDao
import com.ogenrwotdaniel.wakeproof.data.db.SubjectCardCount
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Flashcard CRUD plus SM-2 review transitions. */
@Singleton
class FlashcardRepository @Inject constructor(private val dao: FlashcardDao) {

    fun observeSubjectCounts(): Flow<List<SubjectCardCount>> =
        dao.observeSubjectCounts(LocalDate.now().toEpochDay())

    suspend fun dueCards(subject: String): List<Flashcard> =
        dao.dueCards(LocalDate.now().toEpochDay(), subject)

    /** Grades a card and persists its new SM-2 schedule. */
    suspend fun review(card: Flashcard, grade: ReviewGrade) {
        val reviewed = Sm2Scheduler.review(card, grade, LocalDate.now().toEpochDay())
        // Stamped for upload here so a review done on the tablet does not come due
        // again on the phone.
        dao.update(reviewed.copy(updatedAt = System.currentTimeMillis(), pending = true))
    }

    /** New cards are due immediately so they enter today's review queue. */
    suspend fun addCards(subject: String, cards: List<Pair<String, String>>) {
        val today = LocalDate.now().toEpochDay()
        dao.insertAll(
            cards.filter { it.first.isNotBlank() && it.second.isNotBlank() }.map { (front, back) ->
                Flashcard(
                    subject = subject.trim(),
                    front = front.trim(),
                    back = back.trim(),
                    dueEpochDay = today,
                    createdEpochDay = today
                )
            }
        )
    }

    suspend fun delete(card: Flashcard) = dao.softDelete(card.id)

    suspend fun deleteSubject(subject: String) = dao.softDeleteSubject(subject)
}
