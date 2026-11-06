package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ogenrwotdaniel.wakeproof.data.DistractionLimitRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wallRulesDataStore: DataStore<Preferences> by preferencesDataStore("wall_rules")

/**
 * The tightening rules over gated apps, each stored as one integer so a single
 * looseness test covers them all. Booleans are stored as [RULE_ON]/[RULE_OFF].
 *
 * Every rule is on by default — the user asked for all of them — and, like an
 * app limit, a rule can be tightened at once but only loosened a week later
 * ([WallRulesRepository.request]). Settings that can be switched off in the
 * moment overrule nothing.
 */
enum class WallRule(val key: String, val default: Int) {
    /** Browsers follow the same walls as social apps; instagram.com otherwise skips every wall. */
    GATE_BROWSERS("gate_browsers", RULE_ON),

    /**
     * Gated apps stay completely shut from Friday 00:00 through Sunday 24:00,
     * reopening Monday at the open window (2026-11-06, user's choice).
     */
    WEEKEND_SHUT("weekend_shut", RULE_ON),

    /** Gated apps stay shut until today's first focus block is done (when no window is set). */
    EARN_THE_FEED("earn_the_feed", RULE_ON),

    /**
     * Minute of the day the bedtime wall starts (12:00–23:45); [RULE_OFF_MINUTES]
     * = no bedtime wall. It lasts until a morning alarm is verified.
     */
    BEDTIME_START_MINUTES("bedtime_start_minutes", 17 * 60),

    /** Do Not Disturb for the whole of a focus session. */
    SESSION_DND("session_dnd", RULE_ON),

    /**
     * Minute of the day the gated apps' open window starts; [RULE_OFF_MINUTES]
     * = no window, open all day (except Friday–Sunday while [WEEKEND_SHUT] is on).
     * Covers social, video, messaging, the Google app, Play Store and browsers.
     * 08:10–08:50 since 2026-11-01 ([WallRulesRepository.seedMorningWindow]):
     * 40 minutes Monday to Thursday, with no 5-minute wait or 25-minute budget.
     */
    OPEN_WINDOW_START_MINUTES("open_window_start_minutes", 8 * 60 + 10),

    /** Minute of the day the open window closes (exclusive). */
    OPEN_WINDOW_END_MINUTES("open_window_end_minutes", 8 * 60 + 50);

    /**
     * How loose [value] is for this rule; higher = lets the phone be used
     * more. Off is the loosest value every rule can take.
     */
    fun looseness(value: Int): Int = when (this) {
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

/** Days of the week on which [WallRule.WEEKEND_SHUT] keeps gated apps closed all day. */
val WEEKEND_SHUT_DAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY
)

/** A loosening waiting out the week. */
data class PendingRule(val value: Int, val effectiveAtMillis: Long)

/** The rules in force, read as what they mean. */
data class WallRules(val values: Map<WallRule, Int> = emptyMap()) {
    fun value(rule: WallRule): Int = values[rule] ?: rule.default
    val gateBrowsers: Boolean get() = value(WallRule.GATE_BROWSERS) != RULE_OFF
    val weekendShut: Boolean get() = value(WallRule.WEEKEND_SHUT) != RULE_OFF
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
class WallRulesRepository @Inject constructor(@ApplicationContext private val context: Context) {

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
        // A clock set by hand could be set forward a week (2026-11-04).
        if (!com.ogenrwotdaniel.wakeproof.admin.DeviceLock.networkTime(context)) return false
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

    /**
     * One-off: the open window moves to 08:10–08:50 (2026-11-01, the user's
     * explicit choice). Written straight in rather than through [request] —
     * an earlier start reads as a loosening there and would wait a week —
     * and any queued window change is dropped with it. Once per device under
     * its own flag, so later edits from the Usage screen are never overwritten.
     */
    suspend fun seedMorningWindow() {
        dataStore.edit { prefs ->
            if (prefs[MORNING_WINDOW_SEEDED] == true) return@edit
            val window = listOf(WallRule.OPEN_WINDOW_START_MINUTES, WallRule.OPEN_WINDOW_END_MINUTES)
            window.forEach { prefs[keyOf(it)] = it.default }
            prefs[PENDING] = encodePendingRules(parsePendingRules(prefs[PENDING] ?: emptySet()) - window.toSet())
            prefs[MORNING_WINDOW_SEEDED] = true
        }
    }

    companion object {
        private val PENDING = stringSetPreferencesKey("pending")
        private val MORNING_WINDOW_SEEDED = booleanPreferencesKey("open_window_morning")
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

/** Pure: whether gated apps are shut all day on [dayOfWeek] under [WallRule.WEEKEND_SHUT]. */
fun weekendShutActive(dayOfWeek: DayOfWeek, weekendShut: Boolean): Boolean =
    weekendShut && dayOfWeek in WEEKEND_SHUT_DAYS

/**
 * Pure: whether [minuteOfDay] falls outside the gated apps' open window
 * `[startMinutes, endMinutes)`. A negative start means no window — open all
 * day. A window that ends at or before it starts is open at no time at all:
 * the strict reading of a setting that makes no sense.
 */
fun outsideOpenWindow(minuteOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean =
    startMinutes >= 0 && minuteOfDay !in startMinutes until endMinutes

/**
 * Pure: whether a gated app is shut by the schedule on [dayOfWeek] at
 * [minuteOfDay] — either all day Friday–Sunday ([weekendShutActive]) or
 * outside `[startMinutes, endMinutes)` ([outsideOpenWindow]).
 */
fun windowShutActive(
    dayOfWeek: DayOfWeek,
    minuteOfDay: Int,
    weekendShut: Boolean,
    startMinutes: Int,
    endMinutes: Int
): Boolean =
    weekendShutActive(dayOfWeek, weekendShut) ||
        outsideOpenWindow(minuteOfDay, startMinutes, endMinutes)

/**
 * Pure: when an app whose daily cap is spent on [today] can actually be used
 * again after today. Null means midnight tonight (ordinary countdown); otherwise
 * a concrete phrase such as `"tomorrow at 08:10"`, `"Monday at 08:10"`, or
 * `"Monday"`. An empty window (`endMinutes <= startMinutes` with a non-negative
 * start) returns null rather than promising an opening that will not happen.
 */
fun nextOpenAfterToday(
    gated: Boolean,
    today: DayOfWeek,
    weekendShut: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    formatClock: (Int) -> String
): String? {
    if (!gated) return null
    val hasWindow = startMinutes != RULE_OFF_MINUTES
    if (hasWindow && endMinutes <= startMinutes) return null
    val shutTomorrow = weekendShutActive(today.plus(1), weekendShut)
    return when {
        shutTomorrow && hasWindow -> "Monday at ${formatClock(startMinutes)}"
        shutTomorrow -> "Monday"
        hasWindow -> "tomorrow at ${formatClock(startMinutes)}"
        else -> null
    }
}

/**
 * Pure: headline and body for [GateVerdict.WINDOW_WALL] on [today] at [minuteOfDay].
 */
fun windowWallCopy(
    label: String,
    today: DayOfWeek,
    minuteOfDay: Int,
    weekendShut: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    formatClock: (Int) -> String
): Pair<String, String> {
    val validWindow = startMinutes != RULE_OFF_MINUTES && endMinutes > startMinutes
    if (weekendShutActive(today, weekendShut)) {
        val reopens = if (validWindow) {
            "Monday between ${formatClock(startMinutes)} and ${formatClock(endMinutes)}"
        } else {
            "on Monday"
        }
        return "Shut Friday to Sunday" to
            "$label and every other gated app stay shut from Friday through Sunday. " +
            "They open again $reopens. The phone and SMS always work."
    }
    if (!validWindow) {
        return "Not open now" to
            "$label and every other gated app are shut right now. The phone and SMS always work."
    }
    val span = "${formatClock(startMinutes)} and ${formatClock(endMinutes)}"
    val body = if (weekendShut && today == DayOfWeek.THURSDAY && minuteOfDay >= endMinutes) {
        "$label and every other gated app open Monday to Thursday between $span, " +
            "and stay shut Friday through Sunday — next open Monday at ${formatClock(startMinutes)}. " +
            "The phone and SMS always work."
    } else if (weekendShut) {
        "$label and every other gated app open Monday to Thursday between $span " +
            "(shut Friday through Sunday). The phone and SMS always work."
    } else {
        "$label and every other gated app open only between $span. " +
            "The phone and SMS always work."
    }
    return "Not open now" to body
}

/**
 * Pure: whether the feed is earned. The open window counts as earned
 * (2026-11-01, user's choice): at 08:10 there is rarely a focus block done
 * yet, so earn-the-feed would have kept a morning window shut almost every
 * day. With the window off, earn-the-feed works as before.
 */
fun feedEarned(earnTheFeed: Boolean, focusDoneToday: Boolean, windowStartMinutes: Int): Boolean =
    !earnTheFeed || focusDoneToday || windowStartMinutes >= 0
