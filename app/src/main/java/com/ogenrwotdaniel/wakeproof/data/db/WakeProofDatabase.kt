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
    version = 3,
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
    }
}
