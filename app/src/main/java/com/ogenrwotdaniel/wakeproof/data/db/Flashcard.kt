package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One spaced-repetition card. Scheduling state ([easiness], [repetitions],
 * [intervalDays], [dueEpochDay]) follows the SM-2 algorithm; see Sm2Scheduler.
 */
@Entity(
    tableName = "flashcards",
    indices = [Index(value = ["syncId"], unique = true)]
)
data class Flashcard(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val subject: String,
    val front: String,
    val back: String,
    val easiness: Double = 2.5,
    val repetitions: Int = 0,
    val intervalDays: Int = 0,
    val dueEpochDay: Long,
    val createdEpochDay: Long,
    @ColumnInfo(defaultValue = "''")
    override val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    override val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    override val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    override val pending: Boolean = true
) : SyncableEntity
