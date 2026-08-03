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

/** What happens when an app reaches its daily cap. */
enum class LimitMode {
    /** One notification per day, then nothing — the app stays usable. */
    NUDGE,

    /** The app is walled off for the rest of the calendar day. */
    BLOCK
}

/** One app's daily cap and what crossing it does. */
data class AppLimit(val minutes: Int, val mode: LimitMode = LimitMode.NUDGE)

/**
 * Per-app daily screen-time caps ("Instagram ≤ 30 min/day") plus a
 * notified-today marker so each app nudges at most once per day. Entries are
 * flat "pkg|minutes|mode" strings in DataStore. Constructible without Hilt
 * (plain [Context]) because the periodic worker runs outside the DI graph.
 */
@Singleton
class DistractionLimitRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.limitsDataStore

    /** Package → daily cap. */
    val limits: Flow<Map<String, AppLimit>> = dataStore.data.map { prefs ->
        parseLimits(prefs[LIMITS] ?: emptySet())
    }

    suspend fun currentLimits(): Map<String, AppLimit> = limits.first()

    /** Just the packages walled off once their cap is spent. */
    val blockedPackages: Flow<Set<String>> = limits.map { all ->
        all.filterValues { it.mode == LimitMode.BLOCK }.keys
    }

    /** [minutes] null or ≤ 0 clears the limit. */
    suspend fun setLimit(packageName: String, minutes: Int?, mode: LimitMode = LimitMode.NUDGE) {
        dataStore.edit { prefs ->
            val without = (prefs[LIMITS] ?: emptySet())
                .filterNot { it.substringBefore('|') == packageName }.toSet()
            prefs[LIMITS] =
                if (minutes == null || minutes <= 0) without
                else without + "$packageName|$minutes|${mode.name}"
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

    companion object {
        private val LIMITS = stringSetPreferencesKey("limits")
        private val NOTIFIED = stringSetPreferencesKey("notified")

        /**
         * Decodes stored entries. Two-field entries predate per-app modes and are
         * read as [LimitMode.NUDGE], so limits set before the kill switch existed
         * keep behaving exactly as they did rather than silently starting to block.
         */
        fun parseLimits(entries: Set<String>): Map<String, AppLimit> =
            entries.mapNotNull { entry ->
                val parts = entry.split('|')
                val pkg = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val minutes = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                val mode = parts.getOrNull(2)
                    ?.let { name -> LimitMode.entries.firstOrNull { it.name == name } }
                    ?: LimitMode.NUDGE
                pkg to AppLimit(minutes, mode)
            }.toMap()
    }
}
