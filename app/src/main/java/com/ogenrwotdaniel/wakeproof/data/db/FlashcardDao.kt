package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Per-subject card counts for the deck list. */
data class SubjectCardCount(val subject: String, val total: Int, val due: Int)

/** Room access to [Flashcard]. */
@Dao
interface FlashcardDao {
    @Insert
    suspend fun insertAll(cards: List<Flashcard>)

    @Update
    suspend fun update(card: Flashcard)

    @Delete
    suspend fun delete(card: Flashcard)

    @Query(
        "SELECT subject, COUNT(*) AS total, " +
            "SUM(CASE WHEN dueEpochDay <= :today THEN 1 ELSE 0 END) AS due " +
            "FROM flashcards GROUP BY subject ORDER BY subject"
    )
    fun observeSubjectCounts(today: Long): Flow<List<SubjectCardCount>>

    @Query(
        "SELECT * FROM flashcards WHERE dueEpochDay <= :today AND subject = :subject " +
            "ORDER BY dueEpochDay, id LIMIT :limit"
    )
    suspend fun dueCards(today: Long, subject: String, limit: Int = 50): List<Flashcard>

    @Query("DELETE FROM flashcards WHERE subject = :subject")
    suspend fun deleteSubject(subject: String)
}
