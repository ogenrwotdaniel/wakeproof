package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One spaced-repetition card. Scheduling state ([easiness], [repetitions],
 * [intervalDays], [dueEpochDay]) follows the SM-2 algorithm; see Sm2Scheduler.
 */
@Entity(tableName = "flashcards")
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val front: String,
    val back: String,
    val easiness: Double = 2.5,
    val repetitions: Int = 0,
    val intervalDays: Int = 0,
    val dueEpochDay: Long,
    val createdEpochDay: Long
)
