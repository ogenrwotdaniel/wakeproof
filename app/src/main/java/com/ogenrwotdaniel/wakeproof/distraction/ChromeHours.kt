package com.ogenrwotdaniel.wakeproof.distraction

import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.AppLimit
import com.ogenrwotdaniel.wakeproof.data.LimitMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Chrome's own hours (2026-11-09, user's choice). Chrome is the one ungated
 * browser, kept for submitting assignments, but an open browser reaches every
 * gated site at once. It is open on weekdays 08:40–16:00, for at most an hour a
 * day, and a deadline on a weekday's calendar keeps it open until that deadline
 * (never into the night's sleep wall). All pure, so it is tested without a phone.
 */

/** Chrome opens at the bedtime wall's fixed end (08:40)… */
const val CHROME_OPEN_START_MINUTES = BEDTIME_END_MINUTES

/**
 * …and shuts at 16:00, Monday to Friday. Fixed: it equals the default bedtime
 * start but does not follow a stricter stored one.
 */
const val CHROME_OPEN_END_MINUTES = 16 * 60

/** Chrome's fixed daily cap, a wall once spent; see [withFixedCaps]. */
const val CHROME_DAILY_CAP_MINUTES = 60

/** How long before Chrome shuts the user is told, so an upload is not cut off. */
const val CHROME_CLOSE_WARNING_MINUTES = 10

/** How much of the hour is left when the user is told. */
const val CHROME_CAP_WARNING_MINUTES = 5

/**
 * Titles that mark a deadline: English and Korean (마감 = deadline, 제출 =
 * submission), since the coursework is at UNIST.
 */
private val DEADLINE_WORDS = Regex("""(?i)\b(due|deadline|submit|submission)\b|마감|제출""")

/** The same words, as the wall and the Usage screen name them. */
const val DEADLINE_WORDS_SHOWN = "due, deadline, submit, 마감 or 제출"

private fun weekday(day: DayOfWeek) = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY

/**
 * An ungated app with fixed hours of its own: open on weekdays from [opensAt]
 * until [closesAt], for at most [capMinutes] a day, and (with [deadlinePass])
 * until today's calendar deadline. One row per app in [APP_HOURS], so the wall,
 * the notification guard, the launcher and the caps all read the same table and
 * a second app is a row, not a new branch in each.
 */
data class AppHours(
    val packageName: String,
    val label: String,
    val opensAt: Int,
    val closesAt: Int,
    val capMinutes: Int,
    val deadlinePass: Boolean,
    /**
     * Packages that post this app's notifications, such as the shells Chrome
     * installs for sites saved as apps. Only for notifications: a package name is
     * no proof, so a shell on screen is judged as itself.
     */
    val shellPrefixes: List<String> = emptyList()
)

val CHROME_HOURS = AppHours(
    packageName = AppGate.CHROME,
    label = "Chrome",
    opensAt = CHROME_OPEN_START_MINUTES,
    closesAt = CHROME_OPEN_END_MINUTES,
    capMinutes = CHROME_DAILY_CAP_MINUTES,
    deadlinePass = true,
    shellPrefixes = listOf(AppGate.WEBAPK_PREFIX)
)

/** Every app with fixed hours. */
val APP_HOURS: List<AppHours> = listOf(CHROME_HOURS)

/** Pure: [packageName]'s row in [APP_HOURS]. */
fun appHoursFor(packageName: String): AppHours? = APP_HOURS.firstOrNull { it.packageName == packageName }

/**
 * Pure: the row whose walls [packageName]'s notifications follow: its own, or
 * the app it is a shell for. Never loosens: a stranger using the prefix only
 * has its notifications removed outside Chrome's hours.
 */
fun notificationHoursFor(packageName: String): AppHours? = appHoursFor(packageName)
    ?: APP_HOURS.firstOrNull { hours -> hours.shellPrefixes.any { packageName.startsWith(it) } }

/**
 * Pure: the minute today's deadline falls at, from today's calendar [events]:
 * the latest timed event whose title reads as a deadline, or the sleep wall's
 * start for an all-day one ("Essay due" with no time is due some time today).
 * Null when there is none.
 */
fun deadlineMinuteToday(events: List<AgendaEvent>, today: LocalDate, zone: ZoneId): Int? =
    events.filter { DEADLINE_WORDS.containsMatchIn(it.title) }.mapNotNull { event ->
        if (event.allDay) {
            SLEEP_START_MINUTES.takeIf { com.ogenrwotdaniel.wakeproof.launcher.eventDay(event, zone) == today }
        } else {
            val at = Instant.ofEpochMilli(event.beginMillis).atZone(zone)
            (at.hour * 60 + at.minute).takeIf { at.toLocalDate() == today }
        }
    }.maxOrNull()


/**
 * Pure: the minute Chrome shuts on [day], or null when it does not open at all.
 * A weekday shuts at 16:00; a [deadline] keeps it open until then, never past
 * the sleep wall's start. Weekdays only: anyone can add an event called "Essay
 * due", and a weekend pass would undo the weekend shut for every gated site.
 * Weekend work goes through the Blackboard screen, which has no hours.
 */
fun chromeCloseMinute(day: DayOfWeek, deadline: Int? = null, hours: AppHours = CHROME_HOURS): Int? {
    if (!weekday(day)) return null
    val pass = deadline?.takeIf { hours.deadlinePass }?.coerceAtMost(SLEEP_START_MINUTES)
        ?.takeIf { it > hours.opensAt }
    return listOfNotNull(hours.closesAt, pass).maxOrNull()
}

/** Pure: the app is shut now; see [chromeCloseMinute]. */
fun chromeHoursShut(day: DayOfWeek, minuteOfDay: Int, deadline: Int? = null, hours: AppHours = CHROME_HOURS): Boolean {
    val close = chromeCloseMinute(day, deadline, hours) ?: return true
    return minuteOfDay !in hours.opensAt until close
}

/** When Chrome next opens at [CHROME_OPEN_START_MINUTES]; see [chromeNextOpening]. */
enum class ChromeOpening { TODAY, TOMORROW, MONDAY }

/**
 * Pure: the next Chrome opening from [day] at [minuteOfDay]. [afterToday] for
 * a spent cap, which never reopens the same day. Days ahead are judged without
 * a deadline, which cannot be known yet, so a weekend deadline opens Chrome
 * sooner than this says, never later.
 */
fun chromeNextOpening(
    day: DayOfWeek,
    minuteOfDay: Int,
    afterToday: Boolean,
    deadline: Int? = null
): ChromeOpening = when {
    !afterToday && chromeCloseMinute(day, deadline) != null &&
        minuteOfDay < CHROME_OPEN_START_MINUTES -> ChromeOpening.TODAY
    weekday(day.plus(1)) -> ChromeOpening.TOMORROW
    else -> ChromeOpening.MONDAY
}

/** What the user is warned of while in Chrome. */
enum class ChromeWarning { CLOSING, HOUR_ALMOST_SPENT }

/**
 * Pure: the warning due now, if any, each at most once a day. A wall landing
 * on Chrome closes the page under it, and a half-finished upload is the worst
 * thing it could cut off, so both edges are announced ahead.
 */
fun chromeWarningDue(
    minuteOfDay: Int,
    closeMinute: Int?,
    usedMinutes: Long,
    capMinutes: Int,
    closingWarned: Boolean,
    hourWarned: Boolean
): ChromeWarning? = when {
    !closingWarned && closeMinute != null &&
        closeMinute - minuteOfDay in 1..CHROME_CLOSE_WARNING_MINUTES -> ChromeWarning.CLOSING
    !hourWarned && capMinutes - usedMinutes in 1..CHROME_CAP_WARNING_MINUTES.toLong() ->
        ChromeWarning.HOUR_ALMOST_SPENT
    else -> null
}

/**
 * Pure: [limits] with the built-in caps laid over them: Chrome is a BLOCK cap
 * of at most [CHROME_DAILY_CAP_MINUTES], tighter if one was stored. Applied
 * where the limits are read, so the wall, the launcher and the Usage screen all
 * see it, and nothing stored can loosen it.
 */
fun withFixedCaps(limits: Map<String, AppLimit>): Map<String, AppLimit> =
    limits + APP_HOURS.associate { hours ->
        val stored = limits[hours.packageName]?.minutes ?: Int.MAX_VALUE
        hours.packageName to AppLimit(minOf(stored, hours.capMinutes), LimitMode.BLOCK)
    }

/** Pure: "today at 08:40", "tomorrow at 08:40" or "Monday at 08:40". */
fun chromeOpensPhrase(opening: ChromeOpening, formatClock: (Int) -> String): String {
    val name = when (opening) {
        ChromeOpening.TODAY -> "today"
        ChromeOpening.TOMORROW -> "tomorrow"
        ChromeOpening.MONDAY -> "Monday"
    }
    return "$name at ${formatClock(CHROME_OPEN_START_MINUTES)}"
}

/**
 * Pure: the home screen's one line on Chrome, so a submission can be planned
 * before it is started: when it opens, how much of the hour is left and when
 * it shuts.
 */
fun chromeStatusLine(
    day: DayOfWeek,
    minuteOfDay: Int,
    deadline: Int?,
    usedMinutes: Long,
    capMinutes: Int,
    formatClock: (Int) -> String
): String {
    val close = chromeCloseMinute(day, deadline)
    if (close == null || minuteOfDay !in CHROME_OPEN_START_MINUTES until close) {
        val afterToday = close != null && minuteOfDay >= close
        return "Chrome opens " +
            chromeOpensPhrase(chromeNextOpening(day, minuteOfDay, afterToday, deadline), formatClock)
    }
    if (usedMinutes >= capMinutes) {
        return "Chrome's hour is spent. It opens " +
            chromeOpensPhrase(chromeNextOpening(day, minuteOfDay, afterToday = true), formatClock)
    }
    val pass = if (deadline != null && close != CHROME_OPEN_END_MINUTES) " (deadline today)" else ""
    return "Chrome: ${capMinutes - usedMinutes} min left, open until ${formatClock(close)}$pass"
}
