package com.ogenrwotdaniel.wakeproof.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** App database. Version bumps require a migration once the app ships. */
@Database(
    entities = [
        Alarm::class,
        CompletionLog::class,
        Flashcard::class,
        Exam::class,
        ParkedThought::class
    ],
    version = 5,
    exportSchema = false
)
abstract class WakeProofDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun completionLogDao(): CompletionLogDao
    abstract fun flashcardDao(): FlashcardDao
    abstract fun examDao(): ExamDao
    abstract fun parkedThoughtDao(): ParkedThoughtDao

    companion object {
        /**
         * v3 (study-tools batch): subject on focus logs + flashcards, exams and
         * parked-thoughts tables. Hand-written (not destructive) so existing
         * streak history survives the update.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE completion_logs ADD COLUMN subject TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `flashcards` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`subject` TEXT NOT NULL, `front` TEXT NOT NULL, " +
                        "`back` TEXT NOT NULL, `easiness` REAL NOT NULL, " +
                        "`repetitions` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, " +
                        "`dueEpochDay` INTEGER NOT NULL, `createdEpochDay` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exams` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `epochDay` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parked_thoughts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                        "`done` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v4 (cloud sync): every table gains the [SyncableEntity] columns.
         *
         * Existing rows are stamped `pending = 1`, so the first sync after signing
         * in uploads the history already on the device rather than starting the
         * cloud copy empty. `updatedAt` is left at 0 for them: last-write-wins then
         * treats a locally-edited row on any *other* device as newer, which is the
         * safe direction — the alternative (stamping "now") would make whichever
         * device happened to update last silently win against real edits.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SYNCED_TABLES.forEach { table ->
                    SyncColumns.addColumnsTo(table).forEach(db::execSQL)
                }
                // Local-only, alarms only. Existing alarms are the user's real ones
                // by definition, so they default to 0 (not seeded) and do sync.
                db.execSQL("ALTER TABLE `alarms` ADD COLUMN `seeded` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 (day-of-week alarms): alarms gain a repeat mask.
         *
         * Defaults to 127 — every day — which is exactly what every existing
         * alarm already did, so the migration changes no behaviour.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `alarms` ADD COLUMN `daysMask` INTEGER NOT NULL DEFAULT 127"
                )
            }
        }

        /** Tables that mirror to Firestore, and the collection names they use. */
        val SYNCED_TABLES = listOf(
            "alarms",
            "completion_logs",
            "flashcards",
            "exams",
            "parked_thoughts"
        )
    }
}
