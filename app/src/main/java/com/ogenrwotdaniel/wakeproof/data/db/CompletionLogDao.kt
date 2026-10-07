package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Focus minutes per subject over a date range. */
data class SubjectMinutes(val subject: String?, val minutes: Int)

/** Room access to [CompletionLog]. */
@Dao
interface CompletionLogDao : SyncDao<CompletionLog> {
    /**
     * Every row, tombstones included.
     *
     * Filtering `deleted = 0` here would be a quiet data-loss bug rather
     * than a tidy-up: a restored device that has forgotten its tombstones
     * cannot tell "deleted" from "never seen", so the next sync would
     * resurrect every item the user had removed.
     */
    @Query("SELECT * FROM completion_logs")
    suspend fun backupAll(): List<CompletionLog>

    /** Restores rows verbatim, ids included, so references between tables survive. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(rows: List<CompletionLog>)

    @Query(
        "SELECT subject, SUM(amount) AS minutes FROM completion_logs " +
            "WHERE kind = 'FOCUS' AND status = 'VERIFIED' AND epochDay >= :fromEpochDay AND deleted = 0 " +
            "GROUP BY subject ORDER BY minutes DESC"
    )
    fun observeFocusBySubject(fromEpochDay: Long): Flow<List<SubjectMinutes>>

    @Insert
    suspend fun insert(log: CompletionLog)

    @Query(
        "SELECT * FROM completion_logs WHERE epochDay >= :fromEpochDay AND deleted = 0 " +
            "ORDER BY epochDay"
    )
    fun observeSince(fromEpochDay: Long): Flow<List<CompletionLog>>

    @Query(
        "SELECT * FROM completion_logs WHERE epochDay = :epochDay AND kind = :kind " +
            "AND deleted = 0"
    )
    fun observeForDay(epochDay: Long, kind: GoalKind): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_logs WHERE deleted = 0 ORDER BY epochDay")
    suspend fun getAll(): List<CompletionLog>

    @Query("SELECT * FROM completion_logs WHERE pending = 1 ORDER BY updatedAt LIMIT :limit")
    override suspend fun pendingUploads(limit: Int): List<CompletionLog>

    @Query("SELECT * FROM completion_logs WHERE syncId = :syncId")
    override suspend fun findBySyncId(syncId: String): CompletionLog?

    @Query(
        "UPDATE completion_logs SET pending = 0 WHERE syncId = :syncId AND updatedAt = :updatedAt"
    )
    override suspend fun clearPending(syncId: String, updatedAt: Long)
}
