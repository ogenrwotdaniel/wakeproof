package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * A distracting thought parked during a focus phase ("reply to John") so the
 * user can dump it and refocus, then deal with it on a break.
 */
@Entity(
    tableName = "parked_thoughts",
    indices = [Index(value = ["syncId"], unique = true)]
)
data class ParkedThought(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val text: String,
    val createdAtMillis: Long,
    val done: Boolean = false,
    @ColumnInfo(defaultValue = "''")
    override val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    override val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    override val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    override val pending: Boolean = true
) : SyncableEntity

/** Room access to [ParkedThought]. */
@Dao
interface ParkedThoughtDao : SyncDao<ParkedThought> {
    @Insert
    suspend fun insert(thought: ParkedThought)

    /**
     * Takes the fields the UI can actually change and stamps the row as needing
     * upload. Passing the whole entity through `@Update` would let a stale copy
     * held by a screen overwrite [updatedAt] with an older value.
     */
    @Query(
        "UPDATE parked_thoughts SET text = :text, done = :done, pending = 1, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun updateContent(
        id: Long,
        text: String,
        done: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM parked_thoughts WHERE deleted = 0 ORDER BY done, createdAtMillis DESC")
    fun observeAll(): Flow<List<ParkedThought>>

    /** Soft delete — see [ExamDao.softDelete]. */
    @Query(
        "UPDATE parked_thoughts SET deleted = 1, pending = 1, updatedAt = :now " +
            "WHERE done = 1 AND deleted = 0"
    )
    suspend fun clearDone(now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM parked_thoughts WHERE pending = 1 ORDER BY updatedAt LIMIT :limit")
    override suspend fun pendingUploads(limit: Int): List<ParkedThought>

    @Query("SELECT * FROM parked_thoughts WHERE syncId = :syncId")
    override suspend fun findBySyncId(syncId: String): ParkedThought?

    @Query(
        "UPDATE parked_thoughts SET pending = 0 WHERE syncId = :syncId AND updatedAt = :updatedAt"
    )
    override suspend fun clearPending(syncId: String, updatedAt: Long)
}
