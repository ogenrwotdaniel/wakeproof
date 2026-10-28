package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wallRulesDataStore: DataStore<Preferences> by preferencesDataStore("wall_rules")

/**
 * The tightening rules added on 2026-10-28, each one number so a single
 * looseness test covers them all. Booleans are stored as [RULE_ON]/[RULE_OFF].
 *
 * Every rule is on by default — the user asked for all of them — and, like an
 * app limit, a rule can be tightened at once but only loosened a week later
 * ([WallRulesRepository.request]). Settings that can be switched off in the
 * moment overrule nothing.
 */
enum class WallRule(val key: String, val default: Int) {
    /** Browsers wait like social apps; instagram.com otherwise skips every wall. */
    GATE_BROWSERS("gate_browsers", RULE_ON),

    /** Each visit through the wait today makes the next wait longer. */
    ESCALATING_WAIT("escalating_wait", RULE_ON),

    /** Minutes per day across every waiting app together; 0 = no budget. */
    DAILY_BUDGET_MINUTES("daily_budget_minutes", 25),

    /** Waiting apps stay shut until today's first focus block is done. */
    EARN_THE_FEED("earn_the_feed", RULE_ON),

    /**
     * Minute of the day the bedtime wall starts (12:00–23:45); [RULE_OFF_MINUTES]
     * = no bedtime wall. It lasts until a morning alarm is verified.
     */
    BEDTIME_START_MINUTES("bedtime_start_minutes", 17 * 60),

    /** Do Not Disturb for the whole of a focus session. */
    SESSION_DND("session_dnd", RULE_ON),

    /**
     * Minute of the day the waiting apps' open window starts; [RULE_OFF_MINUTES]
     * = no window, open all day (2026-10-29, user's choice: 14:00, covering
     * social, video, messaging AND browsers). Outside the window they are shut;
     * inside it every other rule still applies.
     */
    OPEN_WINDOW_START_MINUTES("open_window_start_minutes", 14 * 60),

    /** Minute of the day the open window closes (exclusive). */
    OPEN_WINDOW_END_MINUTES("open_window_end_minutes", 17 * 60);

    /**
     * How loose [value] is for this rule; higher = lets the phone be used
     * more. Off is the loosest value every rule can take.
     */
    fun looseness(value: Int): Int = when (this) {
        DAILY_BUDGET_MINUTES -> if (value <= 0) Int.MAX_VALUE else value
        // A later start leaves more of the evening open.
        BEDTIME_START_MINUTES -> if (value < 0) Int.MAX_VALUE else value
        // The window opening earlier, or closing later, leaves more of the day open.
        OPEN_WINDOW_START_MINUTES -> if (value < 0) Int.MAX_VALUE else MINUTES_PER_DAY - value
        OPEN_WINDOW_END_MINUTES -> value
        else -> if (value == RULE_OFF) 1 else 0
    }

    companion object {
        fun fromKey(key: String): WallRule? = entries.firstOrNull { it.key == key }
    }
}

const val RULE_ON = 1
const val RULE_OFF = 0
const val RULE_OFF_MINUTES = -1
const val MINUTES_PER_DAY = 24 * 60

/** A loosening waiting out the week. */
data class PendingRule(val value: Int, val effectiveAtMillis: Long)

/** The rules in force, read as what they mean. */
data class WallRules(val values: Map<WallRule, Int> = emptyMap()) {
    fun value(rule: WallRule): Int = values[rule] ?: rule.default
    val gateBrowsers: Boolean get() = value(WallRule.GATE_BROWSERS) != RULE_OFF
    val escalatingWait: Boolean get() = value(WallRule.ESCALATING_WAIT) != RULE_OFF
    val dailyBudgetMinutes: Int get() = value(WallRule.DAILY_BUDGET_MINUTES).coerceAtLeast(0)
    val earnTheFeed: Boolean get() = value(WallRule.EARN_THE_FEED) != RULE_OFF
    val bedtimeStartMinutes: Int get() = value(WallRule.BEDTIME_START_MINUTES)
    val sessionDnd: Boolean get() = value(WallRule.SESSION_DND) != RULE_OFF
    val openWindowStartMinutes: Int get() = value(WallRule.OPEN_WINDOW_START_MINUTES)
    val openWindowEndMinutes: Int get() = value(WallRule.OPEN_WINDOW_END_MINUTES)
}

/**
 * Stores [WallRules] and the loosenings queued behind them. Same contract as
 * [DistractionLimitRepository.requestLimit], and the same week — one wait for
 * every way of loosening the phone, so none is the easy one.
 */
@Singleton
class WallRulesRepository @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.wallRulesDataStore

    val rules: Flow<WallRules> = dataStore.data.map { prefs ->
        WallRules(WallRule.entries.associateWith { prefs[keyOf(it)] ?: it.default })
    }

    val pending: Flow<Map<WallRule, PendingRule>> = dataStore.data.map { prefs ->
        parsePendingRules(prefs[PENDING] ?: emptySet())
    }

    /**
     * Sets [rule] to [value]: at once when that is no looser, otherwise queued
     * for a week. Asking again for the same loosening keeps its clock. Returns
     * the queued change, or null when it applied.
     */
    suspend fun request(
        rule: WallRule,
        value: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): PendingRule? {
        var queued: PendingRule? = null
        dataStore.edit { prefs ->
            val current = prefs[keyOf(rule)] ?: rule.default
            val pending = parsePendingRules(prefs[PENDING] ?: emptySet())
            if (isLoosening(rule, current, value)) {
                val existing = pending[rule]
                val change = if (existing != null && existing.value == value) existing
                else PendingRule(value, nowMillis + WEEK_MILLIS)
                prefs[PENDING] = encodePendingRules(pending + (rule to change))
                queued = change
            } else {
                prefs[keyOf(rule)] = value
                prefs[PENDING] = encodePendingRules(pending - rule)
            }
        }
        return queued
    }

    /** Drops a queued loosening; the stricter value stays. */
    suspend fun cancelPending(rule: WallRule) {
        dataStore.edit { prefs ->
            prefs[PENDING] = encodePendingRules(parsePendingRules(prefs[PENDING] ?: emptySet()) - rule)
        }
    }

    /** Lands every queued loosening whose week is over. True when anything changed. */
    suspend fun promoteDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val pending = parsePendingRules(prefs[PENDING] ?: emptySet())
            val due = pending.filterValues { it.effectiveAtMillis <= nowMillis }
            if (due.isEmpty()) return@edit
            due.forEach { (rule, change) -> prefs[keyOf(rule)] = change.value }
            prefs[PENDING] = encodePendingRules(pending - due.keys)
            changed = true
        }
        return changed
    }

    suspend fun current(): WallRules = rules.first()

    companion object {
        private val PENDING = stringSetPreferencesKey("pending")
        private fun keyOf(rule: WallRule) = intPreferencesKey(rule.key)

        private const val WEEK_MILLIS =
            DistractionLimitRepository.COOLING_OFF_HOURS * 60L * 60 * 1000

        fun isLoosening(rule: WallRule, current: Int, proposed: Int): Boolean =
            rule.looseness(proposed) > rule.looseness(current)

        /** "key|value|effectiveAtMillis". */
        fun encodePendingRules(pending: Map<WallRule, PendingRule>): Set<String> =
            pending.map { (rule, change) -> "${rule.key}|${change.value}|${change.effectiveAtMillis}" }
                .toSet()

        /** Unreadable entries are dropped, which leaves the current, stricter rule. */
        fun parsePendingRules(entries: Set<String>): Map<WallRule, PendingRule> =
            entries.mapNotNull { entry ->
                val parts = entry.split('|')
                if (parts.size != 3) return@mapNotNull null
                val rule = WallRule.fromKey(parts[0]) ?: return@mapNotNull null
                val value = parts[1].toIntOrNull() ?: return@mapNotNull null
                val at = parts[2].toLongOrNull() ?: return@mapNotNull null
                rule to PendingRule(value, at)
            }.toMap()
    }
}

/** The bedtime wall lifts at this minute of the day at the latest, alarm or not. */
const val BEDTIME_FALLBACK_END_MINUTES = 7 * 60

/**
 * Pure: whether the bedtime wall is up at [minuteOfDay]. It starts at
 * [startMinutes] and runs past midnight until a morning alarm is verified, or
 * until [BEDTIME_FALLBACK_END_MINUTES] on a day no alarm is due. The start is
 * kept to the afternoon or evening by the settings, so "before the start" and
 * "the morning after" never overlap.
 */
fun bedtimeActive(minuteOfDay: Int, startMinutes: Int, alarmVerifiedToday: Boolean): Boolean =
    startMinutes >= 0 && (
        minuteOfDay >= startMinutes ||
            (minuteOfDay < BEDTIME_FALLBACK_END_MINUTES && !alarmVerifiedToday)
        )

/** Longest the escalating wait grows to: half an hour. */
const val MAX_ESCALATED_WAIT_SECONDS = 30 * 60

/**
 * Pure: how long the wait is after [opensToday] visits through it today. Five
 * minutes, then ten, fifteen… capped at [MAX_ESCALATED_WAIT_SECONDS]. The tenth
 * visit used to cost what the first did.
 */
fun gateWaitSeconds(opensToday: Int, escalating: Boolean): Int =
    if (!escalating) AppGate.GATE_WAIT_SECONDS
    else (AppGate.GATE_WAIT_SECONDS * (opensToday.coerceAtLeast(0) + 1))
        .coerceAtMost(MAX_ESCALATED_WAIT_SECONDS)

/**
 * Pure: whether [minuteOfDay] falls outside the waiting apps' open window
 * `[startMinutes, endMinutes)`. A negative start means no window — open all
 * day. A window that ends at or before it starts is open at no time at all:
 * the strict reading of a setting that makes no sense.
 */
fun outsideOpenWindow(minuteOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean =
    startMinutes >= 0 && minuteOfDay !in startMinutes until endMinutes
