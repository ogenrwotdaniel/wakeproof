package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access to [CompletionLog]. */
@Dao
interface CompletionLogDao {
    @Insert
    suspend fun insert(log: CompletionLog)

    @Query("SELECT * FROM completion_logs WHERE epochDay >= :fromEpochDay ORDER BY epochDay")
    fun observeSince(fromEpochDay: Long): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_logs WHERE epochDay = :epochDay AND kind = :kind")
    fun observeForDay(epochDay: Long, kind: GoalKind): Flow<List<CompletionLog>>

    @Query("SELECT * FROM completion_logs ORDER BY epochDay")
    suspend fun getAll(): List<CompletionLog>
}
