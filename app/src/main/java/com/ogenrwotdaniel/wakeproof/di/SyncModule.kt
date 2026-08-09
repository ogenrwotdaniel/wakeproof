package com.ogenrwotdaniel.wakeproof.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.CompletionLogDao
import com.ogenrwotdaniel.wakeproof.data.db.ExamDao
import com.ogenrwotdaniel.wakeproof.data.db.FlashcardDao
import com.ogenrwotdaniel.wakeproof.data.db.ParkedThoughtDao
import com.ogenrwotdaniel.wakeproof.data.db.SyncableEntity
import com.ogenrwotdaniel.wakeproof.data.sync.CloudSyncAvailability
import com.ogenrwotdaniel.wakeproof.data.sync.SyncTable
import com.ogenrwotdaniel.wakeproof.data.sync.syncTables
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Firebase wiring for cloud sync.
 *
 * Both Firebase types are provided as nullable and are null in a build with no
 * `google-services.json`. Touching `FirebaseAuth.getInstance()` in such a build
 * throws, and it would throw from inside the object graph — where a crash is hard
 * to attribute — so the check happens once, here.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(availability: CloudSyncAvailability): FirebaseAuth? =
        if (availability.isAvailable) FirebaseAuth.getInstance() else null

    @Provides
    @Singleton
    fun provideFirestore(availability: CloudSyncAvailability): FirebaseFirestore? {
        if (!availability.isAvailable) return null
        return FirebaseFirestore.getInstance().apply {
            // Room is already the offline copy of this data. A second on-disk cache
            // would double the storage and, worse, let a stale cached read look like
            // a successful download — so Firestore is kept to memory only.
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideSyncTables(
        alarms: AlarmDao,
        logs: CompletionLogDao,
        cards: FlashcardDao,
        exams: ExamDao,
        thoughts: ParkedThoughtDao
    ): List<@JvmSuppressWildcards SyncTable<out SyncableEntity>> =
        syncTables(alarms, logs, cards, exams, thoughts)
}
