package com.ogenrwotdaniel.wakeproof.data.db

import kotlinx.serialization.Serializable

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Which daily goal a log entry belongs to. WATER is retired with its alarm
 * (2026-11-12); the name stays so old rows, cloud documents and backups load.
 */
@Serializable
enum class GoalKind { PUSHUP, WATER, FOCUS, MATH, PLAN, REVIEW, SHUTDOWN }

/**
 * How the goal was completed. VERIFIED = camera/API proof, UNVERIFIED =
 * honor-mode fallback (counts, shown with an asterisk), SKIPPED = emergency
 * dismiss (breaks the streak).
 */
@Serializable
enum class CompletionStatus { VERIFIED, UNVERIFIED, SKIPPED }

/**
 * One completed (or skipped) goal instance. [epochDay] is
 * [java.time.LocalDate.toEpochDay]; [amount] is reps for PUSHUP, minutes for
 * FOCUS, problems solved for MATH, photos for PLAN, cards for REVIEW, things written for tomorrow for SHUTDOWN.
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
