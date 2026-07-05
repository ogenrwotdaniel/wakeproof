package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room access to the [Alarm] table. */
@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAll(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observeById(id: Long): Flow<Alarm?>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE type = :type ORDER BY hour, minute LIMIT 1")
    suspend fun getFirstByType(type: AlarmType): Alarm?

    @Query("SELECT COUNT(*) FROM alarms")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(alarms: List<Alarm>)

    @Update
    suspend fun update(alarm: Alarm)

    @Query("UPDATE alarms SET lastCompletedEpochDay = :epochDay WHERE id = :id")
    suspend fun markCompleted(id: Long, epochDay: Long)
}
