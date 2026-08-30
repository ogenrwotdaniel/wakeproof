package com.ogenrwotdaniel.wakeproof.data.db

import kotlinx.serialization.Serializable

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** An upcoming exam; [epochDay] is [java.time.LocalDate.toEpochDay]. */
@Entity(
    tableName = "exams",
    indices = [Index(value = ["syncId"], unique = true)]
)
@Serializable
data class Exam(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val epochDay: Long,
    @ColumnInfo(defaultValue = "''")
    override val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    override val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    override val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    override val pending: Boolean = true
) : SyncableEntity

/** Room access to [Exam]. */
@Dao
interface ExamDao : SyncDao<Exam> {
    /**
     * Every row, tombstones included.
     *
     * Filtering `deleted = 0` here would be a quiet data-loss bug rather
     * than a tidy-up: a restored device that has forgotten its tombstones
     * cannot tell "deleted" from "never seen", so the next sync would
     * resurrect every item the user had removed.
     */
    @Query("SELECT * FROM exams")
    suspend fun backupAll(): List<Exam>

    /** Restores rows verbatim, ids included, so references between tables survive. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(rows: List<Exam>)

    @Insert
    suspend fun insert(exam: Exam)

    /**
     * Soft delete: the row stays as a tombstone so the other devices learn the
     * exam is gone. A hard delete would simply be re-uploaded by whichever
     * device still had it.
     */
    @Query(
        "UPDATE exams SET deleted = 1, pending = 1, updatedAt = :now WHERE id = :id"
    )
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query(
        "SELECT * FROM exams WHERE epochDay >= :today AND deleted = 0 ORDER BY epochDay"
    )
    fun observeUpcoming(today: Long): Flow<List<Exam>>

    @Query("SELECT * FROM exams WHERE pending = 1 ORDER BY updatedAt LIMIT :limit")
    override suspend fun pendingUploads(limit: Int): List<Exam>

    @Query("SELECT * FROM exams WHERE syncId = :syncId")
    override suspend fun findBySyncId(syncId: String): Exam?

    @Query("UPDATE exams SET pending = 0 WHERE syncId = :syncId AND updatedAt = :updatedAt")
    override suspend fun clearPending(syncId: String, updatedAt: Long)
}
