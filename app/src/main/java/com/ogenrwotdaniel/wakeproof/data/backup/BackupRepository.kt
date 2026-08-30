package com.ogenrwotdaniel.wakeproof.data.backup

import androidx.room.withTransaction
import com.ogenrwotdaniel.wakeproof.alarm.AlarmInitializer
import com.ogenrwotdaniel.wakeproof.data.db.WakeProofDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads and writes whole-app backups. See [BackupFile] for why the format is
 * JSON of the entities rather than a copy of the database.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val database: WakeProofDatabase,
    private val alarmInitializer: AlarmInitializer
) {
    private val json = Json {
        // A backup written by a newer build must not be rejected outright by an
        // older one: dropping a field it does not understand loses that field,
        // whereas refusing the file loses everything.
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** Serialises every table, tombstones included. */
    suspend fun export(nowMillis: Long = System.currentTimeMillis()): String =
        withContext(Dispatchers.IO) {
            val backup = BackupFile(
                createdAtMillis = nowMillis,
                alarms = database.alarmDao().backupAll(),
                completionLogs = database.completionLogDao().backupAll(),
                flashcards = database.flashcardDao().backupAll(),
                exams = database.examDao().backupAll(),
                parkedThoughts = database.parkedThoughtDao().backupAll()
            )
            json.encodeToString(BackupFile.serializer(), backup)
        }

    /**
     * Restores a backup over the current database.
     *
     * ### Merge, not wipe-and-replace
     *
     * Rows are inserted with `REPLACE` on the primary key, so a row present in
     * both the backup and the device takes the backup's version and anything the
     * device has that the backup does not is left alone. Clearing the tables
     * first was the alternative and is worse: restoring last week's file onto a
     * working phone would silently delete this week.
     *
     * ### The schedule has to be rebuilt afterwards
     *
     * Room is not the schedule — AlarmManager is. Restored alarm rows list but
     * do not ring until something arms them, which is the same trap the cloud
     * pull hits and solves the same way. [AlarmInitializer.initialize] also
     * cancels intents for alarms the restore did not bring back, so a restore
     * cannot leave a ghost behind.
     *
     * The whole write runs in one transaction: a restore interrupted half way
     * through would otherwise leave completion logs pointing at alarms that
     * were never inserted.
     */
    suspend fun import(document: String): BackupFile = withContext(Dispatchers.IO) {
        val backup = json.decodeFromString(BackupFile.serializer(), document)
        database.withTransaction {
            database.alarmDao().restoreAll(backup.alarms)
            database.completionLogDao().restoreAll(backup.completionLogs)
            database.flashcardDao().restoreAll(backup.flashcards)
            database.examDao().restoreAll(backup.exams)
            database.parkedThoughtDao().restoreAll(backup.parkedThoughts)
        }
        alarmInitializer.initialize()
        backup
    }
}
