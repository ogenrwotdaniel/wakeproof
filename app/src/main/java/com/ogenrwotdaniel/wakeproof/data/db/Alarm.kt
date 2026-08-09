package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** What the user must prove to dismiss an alarm. */
enum class AlarmType { PUSHUP, WATER, CUSTOM }

/**
 * A daily repeating alarm. [lastCompletedEpochDay] records the most recent day
 * (as [java.time.LocalDate.toEpochDay]) whose verification was completed, so
 * screens can tell whether today's goal is already done.
 */
@Entity(
    tableName = "alarms",
    indices = [Index(value = ["syncId"], unique = true)]
)
data class Alarm(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val type: AlarmType,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val label: String = "",
    val lastCompletedEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "''")
    override val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    override val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    override val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    override val pending: Boolean = true,
    /**
     * True while this is still one of the untouched starter alarms.
     *
     * Local-only, and the reason a second device does not end up with eight
     * alarms: a fresh install seeds four defaults before the user has signed in
     * anywhere, so they must not be uploaded (they carry no intent) and must give
     * way once the account's real alarms arrive. Editing an alarm clears the flag,
     * which is what turns it into a real, synced alarm.
     */
    @ColumnInfo(defaultValue = "0")
    val seeded: Boolean = false
) : SyncableEntity
