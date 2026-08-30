package com.ogenrwotdaniel.wakeproof.data.db

import kotlinx.serialization.Serializable

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Which daily goal a log entry belongs to. */
@Serializable
enum class GoalKind { PUSHUP, WATER, FOCUS }

/**
 * How the goal was completed. VERIFIED = camera/API proof, UNVERIFIED =
 * honor-mode fallback (counts, shown with an asterisk), SKIPPED = emergency
 * dismiss (breaks the streak).
 */
@Serializable
enum class CompletionStatus { VERIFIED, UNVERIFIED, SKIPPED }

/**
 * One completed (or skipped) goal instance. [epochDay] is
 * [java.time.LocalDate.toEpochDay]; [amount] is reps for PUSHUP, ml for WATER,
 * minutes for FOCUS.
 */
@Entity(
    tableName = "completion_logs",
    indices = [Index(value = ["syncId"], unique = true)]
)
@Serializable
data class CompletionLog(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val epochDay: Long,
    val kind: GoalKind,
    val status: CompletionStatus,
    val amount: Int = 0,
    val alarmId: Long? = null,
    /** Study subject for FOCUS entries ("Calculus"); null for other kinds. */
    val subject: String? = null,
    @ColumnInfo(defaultValue = "''")
    override val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    override val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    override val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    override val pending: Boolean = true
) : SyncableEntity
