package com.ogenrwotdaniel.wakeproof.data.db

import kotlinx.serialization.Serializable

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * What the user must prove to dismiss an alarm.
 *
 * Stored by NAME (Room's default enum mapping, and the backup JSON), so a new
 * value is safe to append — but a build that predates it cannot read a row
 * carrying it, so every synced device must be updated together.
 */
@Serializable
enum class AlarmType { PUSHUP, WATER, CUSTOM, MATH }

/**
 * A daily repeating alarm. [lastCompletedEpochDay] records the most recent day
 * (as [java.time.LocalDate.toEpochDay]) whose verification was completed, so
 * screens can tell whether today's goal is already done.
 */
@Entity(
    tableName = "alarms",
    indices = [Index(value = ["syncId"], unique = true)]
)
@Serializable
data class Alarm(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val type: AlarmType,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    /**
     * Which weekdays this alarm repeats on, as an [com.ogenrwotdaniel.wakeproof.alarm.AlarmDays]
     * bit mask. Defaults to every day, which is what alarms did before v5.
     */
    @ColumnInfo(defaultValue = "127")
    val daysMask: Int = 127,
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
