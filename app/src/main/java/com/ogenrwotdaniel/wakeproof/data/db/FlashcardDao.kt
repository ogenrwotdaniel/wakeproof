package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Per-subject card counts for the deck list. */
data class SubjectCardCount(val subject: String, val total: Int, val due: Int)

/** Room access to [Flashcard]. */
@Dao
interface FlashcardDao : SyncDao<Flashcard> {
    /**
     * Every row, tombstones included.
     *
     * Filtering `deleted = 0` here would be a quiet data-loss bug rather
     * than a tidy-up: a restored device that has forgotten its tombstones
     * cannot tell "deleted" from "never seen", so the next sync would
     * resurrect every item the user had removed.
     */
    @Query("SELECT * FROM flashcards")
    suspend fun backupAll(): List<Flashcard>

    /** Restores rows verbatim, ids included, so references between tables survive. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(rows: List<Flashcard>)

    @Insert
    suspend fun insertAll(cards: List<Flashcard>)

    @Update
    suspend fun update(card: Flashcard)

    /** Soft delete, so the other devices learn the card is gone. */
    @Query("UPDATE flashcards SET deleted = 1, pending = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE flashcards SET deleted = 1, pending = 1, updatedAt = :now " +
            "WHERE subject = :subject AND deleted = 0"
    )
    suspend fun softDeleteSubject(subject: String, now: Long = System.currentTimeMillis())

    @Query(
        "SELECT subject, COUNT(*) AS total, " +
            "SUM(CASE WHEN dueEpochDay <= :today THEN 1 ELSE 0 END) AS due " +
            "FROM flashcards WHERE deleted = 0 GROUP BY subject ORDER BY subject"
    )
    fun observeSubjectCounts(today: Long): Flow<List<SubjectCardCount>>

    @Query(
        "SELECT * FROM flashcards WHERE dueEpochDay <= :today AND subject = :subject " +
            "AND deleted = 0 ORDER BY dueEpochDay, id LIMIT :limit"
    )
    suspend fun dueCards(today: Long, subject: String, limit: Int = 50): List<Flashcard>

    @Query("SELECT * FROM flashcards WHERE pending = 1 ORDER BY updatedAt LIMIT :limit")
    override suspend fun pendingUploads(limit: Int): List<Flashcard>

    @Query("SELECT * FROM flashcards WHERE syncId = :syncId")
    override suspend fun findBySyncId(syncId: String): Flashcard?

    @Query("UPDATE flashcards SET pending = 0 WHERE syncId = :syncId AND updatedAt = :updatedAt")
    override suspend fun clearPending(syncId: String, updatedAt: Long)
}
