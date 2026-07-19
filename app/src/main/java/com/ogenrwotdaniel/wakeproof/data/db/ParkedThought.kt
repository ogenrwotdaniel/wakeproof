package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A distracting thought parked during a focus phase ("reply to John") so the
 * user can dump it and refocus, then deal with it on a break.
 */
@Entity(tableName = "parked_thoughts")
data class ParkedThought(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAtMillis: Long,
    val done: Boolean = false
)

/** Room access to [ParkedThought]. */
@Dao
interface ParkedThoughtDao {
    @Insert
    suspend fun insert(thought: ParkedThought)

    @Update
    suspend fun update(thought: ParkedThought)

    @Query("SELECT * FROM parked_thoughts ORDER BY done, createdAtMillis DESC")
    fun observeAll(): Flow<List<ParkedThought>>

    @Query("DELETE FROM parked_thoughts WHERE done = 1")
    suspend fun clearDone()
}
