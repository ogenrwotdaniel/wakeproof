package com.ogenrwotdaniel.wakeproof.di

import android.content.Context
import androidx.room.Room
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLogDao
import com.ogenrwotdaniel.wakeproof.data.db.ExamDao
import com.ogenrwotdaniel.wakeproof.data.db.FlashcardDao
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThoughtDao
import com.ogenrwotdaniel.wakeproof.data.db.WakeProofDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App-wide singletons: database and DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WakeProofDatabase =
        Room.databaseBuilder(context, WakeProofDatabase::class.java, "wakeproof.db")
            .addMigrations(WakeProofDatabase.MIGRATION_2_3, WakeProofDatabase.MIGRATION_3_4)
            // Pre-release safety net for version jumps with no migration path.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideAlarmDao(database: WakeProofDatabase): AlarmDao = database.alarmDao()

    @Provides
    fun provideCompletionLogDao(database: WakeProofDatabase): CompletionLogDao =
        database.completionLogDao()

    @Provides
    fun provideFlashcardDao(database: WakeProofDatabase): FlashcardDao = database.flashcardDao()

    @Provides
    fun provideExamDao(database: WakeProofDatabase): ExamDao = database.examDao()

    @Provides
    fun provideParkedThoughtDao(database: WakeProofDatabase): ParkedThoughtDao =
        database.parkedThoughtDao()
}
