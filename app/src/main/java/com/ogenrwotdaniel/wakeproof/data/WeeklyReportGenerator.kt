package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.SubjectMinutes
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the weekly write-up is allowed to reason about. */
data class WeekStats(
    /** The last 7 days, oldest first. */
    val days: List<DayStat>,
    /** Focus minutes per subject across the same 7 days. */
    val focusBySubject: List<SubjectMinutes>,
    /** Screen-on minutes per day; empty when usage access hasn't been granted. */
    val screenByDay: List<DayUsage> = emptyList(),
    /** Today's heaviest apps, longest first; empty without usage access. */
    val topApps: List<AppUsage> = emptyList(),
    val dailyGoalMinutes: Int = 120,
    /** The wait and the waiting apps, day by day; empty when not measured. */
    val wall: List<WallDay> = emptyList()
)

/** One day in front of the wall: waits sat out vs walked away, and time spent past them. */
data class WallDay(
    val epochDay: Long,
    val opened: Int = 0,
    val walkedAway: Int = 0,
    val waitingAppMinutes: Long = 0
)

/**
 * Writes the weekly report on-device from the numbers already in Room.
 *
 * This deliberately replaced a language-model call. The report is three short
 * sections about quantities the app measured itself, and a model can only
 * paraphrase those — while being free to round them wrong, invent a trend that
 * isn't in the data, or describe a good week as a bad one. Arithmetic here is
 * exact, instant, needs no key, and can be unit-tested, which is the whole
 * argument: this was never a language problem.
 *
 * Output keeps the three-section shape the screen already renders:
 * `Wins:` / `Watch out:` / `Next week:`, plain text, no markdown.
 */
@Singleton
class WeeklyReportGenerator @Inject constructor() {

    fun report(stats: WeekStats): String = buildString {
        append("Wins:\n")
        append(wins(stats))
        append("\n\nWatch out:\n")
        append(watchOut(stats))
        append("\n\nNext week:\n")
        append(nextWeek(stats))
    }

    private fun wins(stats: WeekStats): String {
        val lines = mutableListOf<String>()
        val totalFocus = stats.days.sumOf { it.focusMinutes }
        val goalDays = stats.days.count { it.focusMinutes >= stats.dailyGoalMinutes }

        if (totalFocus > 0) {
            lines += "${formatDuration(totalFocus)} of focused work over 7 days" +
                if (goalDays > 0) ", hitting your ${stats.dailyGoalMinutes}-minute goal on " +
                    "$goalDays ${dayWord(goalDays)}." else "."
        }

        stats.days.filter { it.focusMinutes > 0 }.maxByOrNull { it.focusMinutes }?.let { best ->
            lines += "Best day was ${dayName(best.epochDay)} at ${formatDuration(best.focusMinutes)}."
        }
        stats.focusBySubject.filter { it.minutes > 0 }.maxByOrNull { it.minutes }?.let { top ->
            lines += "Most time went to ${top.subject ?: "untagged work"} " +
                "(${formatDuration(top.minutes)})."
        }
        val walkedAway = stats.wall.sumOf { it.walkedAway }
        if (walkedAway > 0) {
            lines += "Walked away from the wait $walkedAway ${if (walkedAway == 1) "time" else "times"}" +
                " instead of opening the app."
        }
        val pushupDays = stats.days.count { it.pushupDone }
        if (pushupDays > 0) {
            lines += "Pushups verified on $pushupDays ${dayWord(pushupDays)}."
        }
        // Only claim the week was empty when it actually was — a week of pushups
        // and no focus still has a win in it.
        if (lines.isEmpty()) {
            lines += "Nothing logged this week — the slate is clean, which is its own kind of start."
        }
        return lines.joinToString("\n") { "- $it" }
    }

    private fun watchOut(stats: WeekStats): String {
        val lines = mutableListOf<String>()
        val focusTotal = stats.days.sumOf { it.focusMinutes }
        val missedDays = stats.days.count { !it.skipped && it.focusMinutes < stats.dailyGoalMinutes }
        // With nothing logged at all, "7 days under goal" only restates the gap
        // line below it.
        if (missedDays > 0 && focusTotal > 0) {
            lines += "$missedDays ${dayWord(missedDays)} came in under the " +
                "${stats.dailyGoalMinutes}-minute goal."
        }

        // A run of zero-focus days is the signal worth surfacing: three scattered
        // light days are ordinary, three consecutive ones are a habit breaking.
        val gap = longestZeroRun(stats.days)
        if (gap >= 2) {
            lines += "$gap days in a row with no focus logged — the longest gap this week."
        }

        val screenTotal = stats.screenByDay.sumOf { it.minutes }
        if (screenTotal > 0 && focusTotal > 0 && screenTotal > focusTotal * 2) {
            lines += "Screen time (${formatDuration(screenTotal.toInt())}) ran more than double " +
                "your focus time this week."
        }
        val waiting = stats.wall.sumOf { it.waitingAppMinutes }
        if (waiting > 0 && waiting > focusTotal) {
            lines += "Social, video and messaging apps took ${formatDuration(waiting.toInt())} " +
                "this week — more than your focus time."
        }
        stats.topApps.firstOrNull()?.takeIf { it.minutes >= 60 }?.let { app ->
            lines += "${app.label} alone took ${formatDuration(app.minutes.toInt())} today."
        }
        val untagged = stats.focusBySubject.firstOrNull { it.subject == null }?.minutes ?: 0
        if (untagged > 0 && untagged >= focusTotal / 2 && focusTotal > 0) {
            lines += "Half your focus time is untagged, so the per-subject breakdown can't " +
                "tell you much yet."
        }
        return if (lines.isEmpty()) {
            "- Nothing stands out. The week held together."
        } else {
            // Capped: the checks are ordered most-serious first, and a list of
            // five problems reads as noise rather than as a warning.
            lines.take(3).joinToString("\n") { "- $it" }
        }
    }

    /**
     * Exactly one suggestion, picked by severity rather than listed — a report
     * that ends in five things to fix gets none of them done.
     */
    private fun nextWeek(stats: WeekStats): String {
        val focusTotal = stats.days.sumOf { it.focusMinutes }
        val goalDays = stats.days.count { it.focusMinutes >= stats.dailyGoalMinutes }
        val neglected = stats.focusBySubject.filter { it.subject != null && it.minutes == 0 }

        val suggestion = when {
            focusTotal == 0 ->
                "Put one 25-minute block on the calendar for tomorrow. One. Starting is the " +
                    "only problem worth solving this week."

            longestZeroRun(stats.days) >= 3 ->
                "You went ${longestZeroRun(stats.days)} days without a session. Protect a " +
                    "fixed slot — same time, every day — so the gap can't open again."

            goalDays == 0 && stats.dailyGoalMinutes > 60 ->
                "You didn't reach ${stats.dailyGoalMinutes} minutes on any day. Drop the goal " +
                    "to ${(focusTotal / stats.days.size.coerceAtLeast(1) / 15 + 1) * 15} minutes " +
                    "and actually hit it — a goal you miss daily stops meaning anything."

            neglected.isNotEmpty() ->
                "${neglected.first().subject} got no time at all. Give it one block " +
                    "early in the week, before the busy days eat it."

            stats.topApps.firstOrNull()?.let { it.minutes >= 90 } == true ->
                "Set a daily limit on ${stats.topApps.first().label} in the Usage screen. " +
                    "It's the cheapest hour you'll get back."

            goalDays >= 5 ->
                "Five strong days is a working system. Raise the goal to " +
                    "${stats.dailyGoalMinutes + 30} minutes and see if it still holds."

            else ->
                "Aim to hit the goal on ${(goalDays + 1).coerceAtMost(7)} days, not all 7. " +
                    "One more good day is a real target; a perfect week isn't."
        }
        return "- $suggestion"
    }

    /** Longest run of consecutive days with no focus logged and no deliberate skip. */
    private fun longestZeroRun(days: List<DayStat>): Int {
        var longest = 0
        var current = 0
        for (day in days) {
            if (day.focusMinutes == 0 && !day.skipped) {
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    private fun dayWord(count: Int) = if (count == 1) "day" else "days"

    private fun dayName(epochDay: Long): String =
        DayOfWeek.from(LocalDate.ofEpochDay(epochDay))
            .name.lowercase().replaceFirstChar { it.uppercase() }

    /** "45 min" under an hour, "2 h 05 m" above — reports read badly in raw minutes. */
    private fun formatDuration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} m"
    }
}
