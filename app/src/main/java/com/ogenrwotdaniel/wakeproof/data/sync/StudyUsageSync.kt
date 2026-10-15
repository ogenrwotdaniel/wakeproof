package com.ogenrwotdaniel.wakeproof.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.ogenrwotdaniel.wakeproof.data.DeviceStudyUsage
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.UsageStatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Shares each device's measured study-app time with the account's other
 * devices, so the phone's study card can include an evening in Samsung Notes
 * on the tablet. UsageStats is strictly per-device; nothing else carries it.
 *
 * One document per install at `users/{uid}/studyUsage/{installId}`, holding
 * TODAY only and overwritten on each publish — this is a report, not history,
 * so none of the Room sync machinery (tombstones, cursors, merge) applies.
 * Readers ignore a document whose day is not today.
 *
 * Every call is best-effort and silent: without Firebase, sign-in, sync or a
 * network the card simply shows this device's own numbers.
 */
@Singleton
class StudyUsageSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val availability: CloudSyncAvailability,
    private val firestore: FirebaseFirestore?,
    private val accounts: AccountRepository,
    private val preferences: SyncPreferences,
    private val usageStats: UsageStatsRepository,
    private val settings: SettingsRepository
) {

    /** This device's measured minutes today, per study package (WakeProof included). */
    suspend fun localMinutesToday(): Map<String, Long> {
        val study = settings.settings.first().studyApps + context.packageName
        return usageStats.todayForegroundMillisFor(study).mapValues { (_, millis) -> millis / 60_000 }
    }

    /** Uploads [minutes] as this device's report for today. */
    suspend fun publish(minutes: Map<String, Long>) {
        val (db, uid) = target() ?: return
        if (!usageStats.hasAccess()) return
        runCatching {
            db.collection(USERS).document(uid).collection(COLLECTION)
                .document(preferences.installId())
                .set(
                    mapOf(
                        DEVICE to deviceName(),
                        EPOCH_DAY to LocalDate.now().toEpochDay(),
                        MINUTES to minutes,
                        LABELS to minutes.keys.associateWith(usageStats::appLabel),
                        UPDATED_AT to System.currentTimeMillis(),
                        SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                    )
                ).await()
        }.onFailure { Log.w(TAG, "Study-time publish failed", it) }
    }

    /** Measures and publishes in one go — for the background sync pass. */
    suspend fun publishToday() {
        if (target() == null) return
        publish(localMinutesToday())
    }

    /** The other devices' reports for today; empty on any failure. */
    suspend fun peersToday(): List<DeviceStudyUsage> {
        val (db, uid) = target() ?: return emptyList()
        val today = LocalDate.now().toEpochDay()
        val self = preferences.installId()
        return runCatching {
            db.collection(USERS).document(uid).collection(COLLECTION)
                .whereEqualTo(EPOCH_DAY, today)
                .get().await()
                .documents
                .filter { it.id != self }
                .map { doc ->
                    DeviceStudyUsage(
                        deviceName = doc.getString(DEVICE) ?: "Another device",
                        epochDay = doc.getLong(EPOCH_DAY) ?: -1L,
                        minutes = doc.numberMap(MINUTES),
                        labels = (doc.get(LABELS) as? Map<*, *>).orEmpty()
                            .mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }
                            .toMap(),
                        updatedAtMillis = doc.getLong(UPDATED_AT) ?: 0L
                    )
                }
        }.onFailure { Log.w(TAG, "Study-time read failed", it) }.getOrDefault(emptyList())
    }

    private suspend fun target(): Pair<FirebaseFirestore, String>? {
        val db = firestore ?: return null
        val uid = accounts.currentUid() ?: return null
        if (!availability.isAvailable || !preferences.isEnabled()) return null
        return db to uid
    }

    /** The name the user gave the device ("Galaxy Tab S9 FE+"), else the model. */
    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun com.google.firebase.firestore.DocumentSnapshot.numberMap(field: String): Map<String, Long> =
        (get(field) as? Map<*, *>).orEmpty()
            .mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.let { key to it.toLong() } } }
            .toMap()

    private companion object {
        const val TAG = "StudyUsageSync"
        const val USERS = "users"
        const val COLLECTION = "studyUsage"
        const val DEVICE = "device"
        const val EPOCH_DAY = "epochDay"
        const val MINUTES = "minutes"
        const val LABELS = "labels"
        const val UPDATED_AT = "updatedAt"
        const val SERVER_UPDATED_AT = "serverUpdatedAt"
    }
}
