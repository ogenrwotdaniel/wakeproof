package com.ogenrwotdaniel.wakeproof.di

import android.content.Context
import androidx.room.Room
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
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
        Room.databaseBuilder(context, WakeProofDatabase::class.java, "wakeproof.db").build()

    @Provides
    fun provideAlarmDao(database: WakeProofDatabase): AlarmDao = database.alarmDao()
}
