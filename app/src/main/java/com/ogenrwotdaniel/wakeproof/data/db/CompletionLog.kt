package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which daily goal a log entry belongs to. */
enum class GoalKind { PUSHUP, WATER, FOCUS }

/**
 * How the goal was completed. VERIFIED = camera/API proof, UNVERIFIED =
 * honor-mode fallback (counts, shown with an asterisk), SKIPPED = emergency
 * dismiss (breaks the streak).
 */
enum class CompletionStatus { VERIFIED, UNVERIFIED, SKIPPED }

/**
 * One completed (or skipped) goal instance. [epochDay] is
 * [java.time.LocalDate.toEpochDay]; [amount] is reps for PUSHUP, ml for WATER,
 * minutes for FOCUS.
 */
@Entity(tableName = "completion_logs")
data class CompletionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val kind: GoalKind,
    val status: CompletionStatus,
    val amount: Int = 0,
    val alarmId: Long? = null
)
