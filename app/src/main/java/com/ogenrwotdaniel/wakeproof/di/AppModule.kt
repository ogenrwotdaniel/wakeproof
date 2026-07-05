package com.ogenrwotdaniel.wakeproof.di

import android.content.Context
import androidx.room.Room
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLogDao
import com.ogenrwotdaniel.wakeproof.data.db.WakeProofDatabase
import com.ogenrwotdaniel.wakeproof.verify.MotivationSource
import com.ogenrwotdaniel.wakeproof.verify.StubMotivationSource
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
            // Pre-release only: schema is still evolving phase by phase.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideAlarmDao(database: WakeProofDatabase): AlarmDao = database.alarmDao()

    @Provides
    fun provideCompletionLogDao(database: WakeProofDatabase): CompletionLogDao =
        database.completionLogDao()

    // Replaced by the Kimi-backed implementation once the API phase lands.
    @Provides
    @Singleton
    fun provideMotivationSource(): MotivationSource = StubMotivationSource()
}
