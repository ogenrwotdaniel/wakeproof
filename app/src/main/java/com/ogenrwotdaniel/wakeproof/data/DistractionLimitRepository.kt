package com.ogenrwotdaniel.wakeproof.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.ogenrwotdaniel.wakeproof.distraction.withFixedCaps

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
        withFixedCaps(parseLimits(prefs[LIMITS] ?: emptySet()))
    }

    suspend fun currentLimits(): Map<String, AppLimit> = limits.first()

    /** Just the packages walled off once their cap is spent. */
    val blockedPackages: Flow<Set<String>> = limits.map { all ->
        all.filterValues { it.mode == LimitMode.BLOCK }.keys
    }

    /**
     * Asks for a cap change. [minutes] null or ≤ 0 means remove the cap.
     * True when it was applied.
     *
     * Only a STRICTER cap (a new one, fewer minutes, Nudge → Block) is ever
     * applied, at once. Anything that LOOSENS a cap (removing it, more
     * minutes, Block → Nudge) is refused outright (2026-11-08, the user's
     * choice: limits "totally fixed"). It used to wait a week, which was short
     * enough to sit out on a bad one; a cap set too tight takes a new build to
     * undo. Loosenings already queued under that week are never landed.
     */
    suspend fun requestLimit(packageName: String, minutes: Int?, mode: LimitMode = LimitMode.NUDGE): Boolean {
        val proposed = minutes?.takeIf { it > 0 }?.let { AppLimit(it, mode) }
        var applied = false
        dataStore.edit { prefs ->
            // Loosenings queued under the old week are never landed; drop them with the first change.
            DEAD_KEYS.forEach { prefs.remove(it) }
            // Judged against what is enforced: a Chrome cap looser than its fixed hour
            // would be stored, reported as applied, and then ignored.
            val current = withFixedCaps(parseLimits(prefs[LIMITS] ?: emptySet()))[packageName]
            if (isLoosening(current, proposed)) return@edit
            prefs[LIMITS] = withLimit(prefs[LIMITS] ?: emptySet(), packageName, proposed)
            applied = true
        }
        return applied
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

        /** The cooling-off's queue and its older 24/48 h settings, removed 2026-11-08. */
        private val DEAD_KEYS = listOf(
            stringSetPreferencesKey("pending"),
            intPreferencesKey("cooling_off_hours"),
            intPreferencesKey("cooling_off_pending_hours"),
            longPreferencesKey("cooling_off_pending_at"),
            booleanPreferencesKey("cooling_off_moved_onto_week")
        )

        /**
         * True when [proposed] lets the app be used more than [current] does:
         * the cap removed, more minutes, or Block softened to Nudge. A change
         * that loosens on any axis counts as loosening — more minutes is still
         * more minutes even when it arrives with a switch to Block.
         */
        fun isLoosening(current: AppLimit?, proposed: AppLimit?): Boolean = when {
            current == null -> false
            proposed == null -> true
            proposed.minutes > current.minutes -> true
            current.mode == LimitMode.BLOCK && proposed.mode == LimitMode.NUDGE -> true
            else -> false
        }

        private fun withLimit(entries: Set<String>, packageName: String, limit: AppLimit?): Set<String> {
            val without = entries.filterNot { it.substringBefore('|') == packageName }.toSet()
            return if (limit == null) without
            else without + "$packageName|${limit.minutes}|${limit.mode.name}"
        }

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
