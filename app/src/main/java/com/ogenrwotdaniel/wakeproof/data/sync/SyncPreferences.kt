package com.ogenrwotdaniel.wakeproof.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.Timestamp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore("sync")

/**
 * Where sync keeps its own bookkeeping — deliberately in a separate DataStore from
 * user settings, so "sign out and wipe the sync state" cannot take preferences with it.
 */
@Singleton
class SyncPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.syncDataStore

    /** User's opt-in. Sync does nothing at all until this is on *and* an account is linked. */
    val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }

    /** Wall-clock millis of the last pass that completed without error; 0 = never. */
    val lastSyncedAt: Flow<Long> = dataStore.data.map { it[LAST_SYNCED_AT] ?: 0L }

    /** Human-readable reason the last pass failed, or blank when the last pass was clean. */
    val lastError: Flow<String> = dataStore.data.map { it[LAST_ERROR].orEmpty() }

    suspend fun setEnabled(value: Boolean) = dataStore.edit { it[ENABLED] = value }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun recordSuccess(atMillis: Long) = dataStore.edit {
        it[LAST_SYNCED_AT] = atMillis
        it.remove(LAST_ERROR)
    }

    suspend fun recordError(message: String) = dataStore.edit { it[LAST_ERROR] = message }

    /**
     * How far this device has read a collection, as a Firestore server timestamp.
     *
     * Seconds and nanos are kept exactly rather than collapsed into millis: the
     * cursor is used as a strict `>` bound, so rounding it down would re-download
     * documents forever and rounding it up would skip whatever shared that
     * millisecond. Null means "never pulled" — the first pass takes the whole
     * collection.
     */
    suspend fun cursor(collection: String): Timestamp? {
        val raw = dataStore.data.first()[cursorKey(collection)] ?: return null
        val (seconds, nanos) = raw.split(':').let { it.getOrNull(0) to it.getOrNull(1) }
        val s = seconds?.toLongOrNull() ?: return null
        val n = nanos?.toIntOrNull() ?: return null
        return Timestamp(s, n)
    }

    suspend fun setCursor(collection: String, value: Timestamp) = dataStore.edit {
        it[cursorKey(collection)] = "${value.seconds}:${value.nanoseconds}"
    }

    /**
     * Drops every cursor so the next pass re-reads all collections in full.
     * Used on sign-out and on account switch, where keeping a cursor would mean
     * silently skipping everything the new account already had.
     */
    suspend fun clearCursors() = dataStore.edit { prefs ->
        prefs.asMap().keys
            .filter { it.name.startsWith(CURSOR_PREFIX) }
            .forEach { prefs.remove(it) }
        prefs.remove(LAST_SYNCED_AT)
        prefs.remove(LAST_ERROR)
    }

    /**
     * A random id for this install, minted on first use. Names this device's
     * own documents (e.g. its study-time report) so peers can tell them apart
     * and it can skip its own when reading theirs. A reinstall mints a new one.
     */
    suspend fun installId(): String {
        dataStore.data.first()[INSTALL_ID]?.let { return it }
        var id = ""
        dataStore.edit { prefs ->
            id = prefs[INSTALL_ID] ?: java.util.UUID.randomUUID().toString()
            prefs[INSTALL_ID] = id
        }
        return id
    }

    private fun cursorKey(collection: String) = stringPreferencesKey("$CURSOR_PREFIX$collection")

    private companion object {
        const val CURSOR_PREFIX = "cursor_"
        val ENABLED = booleanPreferencesKey("sync_enabled")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val INSTALL_ID = stringPreferencesKey("install_id")
    }
}
