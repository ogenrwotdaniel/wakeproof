package com.ogenrwotdaniel.wakeproof.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.limitsDataStore: DataStore<Preferences> by preferencesDataStore("distraction_limits")

/**
 * Per-app daily screen-time caps ("Instagram ≤ 30 min/day") plus a
 * notified-today marker so each app nudges at most once per day. Entries are
 * flat "pkg|value" strings in DataStore. Constructible without Hilt (plain
 * [Context]) because the periodic worker runs outside the DI graph.
 */
@Singleton
class DistractionLimitRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.limitsDataStore

    /** Package → daily limit in minutes. */
    val limits: Flow<Map<String, Int>> = dataStore.data.map { prefs ->
        (prefs[LIMITS] ?: emptySet()).mapNotNull { entry ->
            val (pkg, minutes) = entry.split('|', limit = 2).takeIf { it.size == 2 }
                ?: return@mapNotNull null
            minutes.toIntOrNull()?.let { pkg to it }
        }.toMap()
    }

    suspend fun currentLimits(): Map<String, Int> = limits.first()

    /** [minutes] null or ≤ 0 clears the limit. */
    suspend fun setLimit(packageName: String, minutes: Int?) {
        dataStore.edit { prefs ->
            val without = (prefs[LIMITS] ?: emptySet())
                .filterNot { it.startsWith("$packageName|") }.toSet()
            prefs[LIMITS] =
                if (minutes == null || minutes <= 0) without
                else without + "$packageName|$minutes"
        }
    }

    /** True once per package per [epochDay]; marks as notified as a side effect. */
    suspend fun tryMarkNotified(packageName: String, epochDay: Long): Boolean {
        var first = false
        dataStore.edit { prefs ->
            val entry = "$packageName|$epochDay"
            val current = prefs[NOTIFIED] ?: emptySet()
            first = entry !in current
            // Keep only today's markers so the set can't grow forever.
            prefs[NOTIFIED] = current.filter { it.endsWith("|$epochDay") }.toSet() + entry
        }
        return first
    }

    private companion object {
        val LIMITS = stringSetPreferencesKey("limits")
        val NOTIFIED = stringSetPreferencesKey("notified")
    }
}
