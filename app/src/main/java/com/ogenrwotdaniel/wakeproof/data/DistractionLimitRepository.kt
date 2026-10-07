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
 * A loosening waiting out the cooling-off. [limit] null means the cap is being
 * removed altogether.
 */
data class PendingLimitChange(val limit: AppLimit?, val effectiveAtMillis: Long)

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

    /** Package → a loosening queued behind [COOLING_OFF_MILLIS]. */
    val pendingChanges: Flow<Map<String, PendingLimitChange>> = dataStore.data.map { prefs ->
        parsePending(prefs[PENDING] ?: emptySet())
    }

    /**
     * Asks for a cap change. [minutes] null or ≤ 0 means remove the cap.
     *
     * Anything that makes the cap STRICTER (a new cap, fewer minutes, Nudge →
     * Block) applies at once. Anything that LOOSENS it (removing it, more
     * minutes, Block → Nudge) is queued and only lands [COOLING_OFF_MILLIS]
     * later. The limit exists to overrule the person at the moment they want
     * the app; if that same person can undo it in two taps at that same moment,
     * it overrules nothing. A day is long enough for the urge to pass and short
     * enough that a cap set wrongly is not a life sentence.
     *
     * Returns the queued change, or null when it applied immediately.
     */
    suspend fun requestLimit(
        packageName: String,
        minutes: Int?,
        mode: LimitMode = LimitMode.NUDGE,
        nowMillis: Long = System.currentTimeMillis()
    ): PendingLimitChange? {
        val proposed = minutes?.takeIf { it > 0 }?.let { AppLimit(it, mode) }
        var queued: PendingLimitChange? = null
        dataStore.edit { prefs ->
            val current = parseLimits(prefs[LIMITS] ?: emptySet())[packageName]
            val pending = parsePending(prefs[PENDING] ?: emptySet())
            val otherPending = pending - packageName
            if (isLoosening(current, proposed)) {
                // Asking again for the same loosening keeps its clock; asking for
                // a different one restarts it, or a small queued change could be
                // swapped for "remove" on the eve of landing.
                val existing = pending[packageName]
                val change = if (existing != null && existing.limit == proposed) existing
                else PendingLimitChange(proposed, nowMillis + COOLING_OFF_MILLIS)
                prefs[PENDING] = encodePending(otherPending + (packageName to change))
                queued = change
            } else {
                prefs[LIMITS] = withLimit(prefs[LIMITS] ?: emptySet(), packageName, proposed)
                prefs[PENDING] = encodePending(otherPending)
            }
        }
        return queued
    }

    /** Drops a queued loosening; the current cap simply stays. */
    suspend fun cancelPending(packageName: String) {
        dataStore.edit { prefs ->
            prefs[PENDING] = encodePending(parsePending(prefs[PENDING] ?: emptySet()) - packageName)
        }
    }

    /**
     * Lands every queued change whose cooling-off is over. Called from the
     * 15-minute worker and whenever the Usage screen opens, so a change lands
     * up to a quarter of an hour late — late in the strict direction, which is
     * the safe one. Returns true when anything changed.
     */
    suspend fun promoteDuePending(nowMillis: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val pending = parsePending(prefs[PENDING] ?: emptySet())
            val due = pending.filterValues { it.effectiveAtMillis <= nowMillis }
            if (due.isEmpty()) return@edit
            var limits = prefs[LIMITS] ?: emptySet()
            due.forEach { (pkg, change) -> limits = withLimit(limits, pkg, change.limit) }
            prefs[LIMITS] = limits
            prefs[PENDING] = encodePending(pending - due.keys)
            changed = true
        }
        return changed
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
        private val PENDING = stringSetPreferencesKey("pending")

        /** How long a loosening waits before it lands. */
        const val COOLING_OFF_MILLIS = 24L * 60 * 60 * 1000

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

        /** "pkg|minutes|MODE|effectiveAtMillis"; minutes 0 = removal. */
        fun encodePending(pending: Map<String, PendingLimitChange>): Set<String> =
            pending.map { (pkg, change) ->
                val limit = change.limit
                "$pkg|${limit?.minutes ?: 0}|${(limit?.mode ?: LimitMode.NUDGE).name}|" +
                    change.effectiveAtMillis
            }.toSet()

        /** Unreadable entries are dropped: a corrupt queue leaves the CURRENT, stricter cap. */
        fun parsePending(entries: Set<String>): Map<String, PendingLimitChange> =
            entries.mapNotNull { entry ->
                val parts = entry.split('|')
                if (parts.size != 4) return@mapNotNull null
                val pkg = parts[0].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val minutes = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                val mode = LimitMode.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
                val at = parts[3].toLongOrNull() ?: return@mapNotNull null
                pkg to PendingLimitChange(if (minutes == 0) null else AppLimit(minutes, mode), at)
            }.toMap()

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
