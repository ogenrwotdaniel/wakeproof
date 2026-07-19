package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Focus minutes per subject over a date range. */
data class SubjectMinutes(val subject: String?, val minutes: Int)

/** Room access to [CompletionLog]. */
@Dao
interface CompletionLogDao {
    @Query(
        "SELECT subject, SUM(amount) AS minutes FROM completion_logs " +
            "WHERE kind = 'FOCUS' AND epochDay >= :fromEpochDay " +
            "GROUP BY subject ORDER BY minutes DESC"
    )
    fun observeFocusBySubject(fromEpochDay: Long): Flow<List<SubjectMinutes>>

    @Insert
    suspend fun insert(log: CompletionLog)

    @Query("SELECT * FROM completion_logs WHERE epochDay >= :fromEpochDay ORDER BY epochDay")
    fun observeSince(fromEpochDay: Long): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_logs WHERE epochDay = :epochDay AND kind = :kind")
    fun observeForDay(epochDay: Long, kind: GoalKind): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_logs ORDER BY epochDay")
    suspend fun getAll(): List<CompletionLog>
}
