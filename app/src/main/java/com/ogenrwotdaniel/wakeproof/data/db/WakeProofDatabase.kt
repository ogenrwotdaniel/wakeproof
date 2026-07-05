package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** App database. Version bumps require a migration once the app ships. */
@Database(entities = [Alarm::class, CompletionLog::class], version = 2, exportSchema = false)
abstract class WakeProofDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun completionLogDao(): CompletionLogDao
}
