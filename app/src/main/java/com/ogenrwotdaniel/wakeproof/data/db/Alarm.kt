package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** What the user must prove to dismiss an alarm. */
enum class AlarmType { PUSHUP, WATER, CUSTOM }

/**
 * A daily repeating alarm. [lastCompletedEpochDay] records the most recent day
 * (as [java.time.LocalDate.toEpochDay]) whose verification was completed, so
 * screens can tell whether today's goal is already done.
 */
@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AlarmType,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val label: String = "",
    val lastCompletedEpochDay: Long? = null
)
