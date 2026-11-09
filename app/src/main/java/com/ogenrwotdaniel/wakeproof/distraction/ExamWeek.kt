package com.ogenrwotdaniel.wakeproof.distraction

/**
 * Exam week (2026-11-09, user's choice): for the [EXAM_WEEK_DAYS] days up to and
 * including an exam on the Exams screen, gated apps lose their 08:40–08:50
 * Monday–Thursday opening and stay shut all day. It tightens only, needs nothing
 * switched on, and lifts by itself the day after the exam. A week once begun is
 * held ([heldExamsAfter]): deleting or moving the exam does not end it early. Chrome keeps its own
 * hours: it is where the revision material is.
 */
const val EXAM_WEEK_DAYS = 7

/** An exam, by name and epoch day; see [examWeekHeld]. */
data class ExamWeek(val name: String, val epochDay: Long)

/**
 * Pure: whether [today] is in exam week, from [exams] (name to epoch day) and every
 * held exam ([heldExamsAfter]), so deleting an exam or moving its date mid-week
 * does not lift the wall; like every other fixed wall, it tightens only. Null on
 * an ordinary day.
 *
 * Names the exam the wall lasts until: the end of the unbroken run of exam weeks
 * from today, so an exam whose week begins the day after another's exam extends
 * the run and is named, rather than a date the wall would then outlast. A held
 * exam on the same day as a live one takes the live one's name, so a renamed
 * exam is shown by its new name.
 */
fun examWeekHeld(exams: List<Pair<String, Long>>, held: Collection<ExamWeek>, today: Long): ExamWeek? {
    val liveDays = exams.mapTo(mutableSetOf()) { it.second }
    val all = exams + held.filter { it.epochDay !in liveDays }.map { it.name to it.epochDay }
    var end = all.filter { (_, day) -> day - today in 0 until EXAM_WEEK_DAYS }.maxByOrNull { it.second }
        ?: return null
    while (true) {
        // A later exam whose week starts by the day after the current end continues the run.
        val next = all.filter { (_, day) -> day > end.second && day - (end.second + 1) < EXAM_WEEK_DAYS }
            .maxByOrNull { it.second } ?: break
        end = next
    }
    return ExamWeek(end.first, end.second)
}

/**
 * An exam row as [heldExamsAfter] reads it: [id] is its sync id, the same through
 * renames and date changes. Rows from before sync have a blank one; those are told
 * apart by name and day instead, or every such row would count as seen for the others.
 */
data class ExamRow(val id: String, val name: String, val epochDay: Long, val updatedAtMillis: Long = Long.MAX_VALUE) {
    val key: String get() = id.ifBlank { "legacy:$epochDay|$name" }
}

/**
 * What [heldExamsAfter] keeps: the holds, and when each exam now in its week was
 * first seen there (row key to wall-clock millis).
 */
data class ExamHolds(val held: Set<ExamWeek>, val seen: Map<String, Long>)

/** How long an exam must have been seen in its week before it is held: the time to fix a mistyped date. */
const val EXAM_HOLD_GRACE_MILLIS = 10 * 60_000L

/**
 * Pure: the holds to keep after reading [exams] today at [nowMillis]. An exam whose
 * week has begun is held once the same row (by [ExamRow.key], whatever it is called
 * or dated) has been in its week for [EXAM_HOLD_GRACE_MILLIS] ([seen] holds when it
 * was first seen there). Real time, not a count of reads: a service restart runs a
 * read at once and would have cut the grace to seconds. Renaming or re-dating a row
 * keeps its first-seen time, so it cannot be kept unheld; a new exam with a
 * mistyped date, fixed within the grace, is never held, even in a week another
 * exam already walls. A clock set back (now before first seen) holds at once:
 * waiting on it would be a way to keep an exam unheld. The exam row walls in the
 * meantime anyway.
 *
 * Holds are added on any clock: a hold only ever adds walls, and one for a far
 * date (a clock set forward) does nothing until its own week. Holds past their
 * day are dropped only when [clockProven], so a date set forward cannot end a
 * real one early.
 */
fun heldExamsAfter(
    held: Set<ExamWeek>,
    seen: Map<String, Long>,
    exams: List<ExamRow>,
    today: Long,
    clockProven: Boolean,
    nowMillis: Long
): ExamHolds {
    val begun = exams.filter { it.epochDay - today in 0 until EXAM_WEEK_DAYS }
    // The grace is for a date just typed: a row last saved before then (an exam added
    // weeks ago, now entering its week) counts from its save and is held at once, or
    // it could be deleted in the first ten minutes of its week.
    val firstSeen = begun.associate { it.key to minOf(seen[it.key] ?: nowMillis, it.updatedAtMillis) }
    val confirmed = begun.filter { row ->
        val since = firstSeen.getValue(row.key)
        nowMillis - since >= EXAM_HOLD_GRACE_MILLIS || nowMillis < since
    }.map { ExamWeek(it.name, it.epochDay) }
    val (ahead, past) = (held + confirmed).partition { it.epochDay >= today }
    // On a doubted clock past holds stay, since "past" may be a date set forward,
    // but only the latest few, so the stored set stays small on a device that
    // never hears network time. Not by age: a clock set far enough forward would
    // age out a real one.
    val keptPast = if (clockProven) emptyList() else past.sortedByDescending { it.epochDay }.take(MAX_PAST_HOLDS)
    return ExamHolds((ahead + keptPast).toSet(), firstSeen)
}

/**
 * Past holds a doubted clock keeps, the latest first; see [heldExamsAfter]. Large
 * enough that pushing a real hold out would take hundreds of fake exams, each
 * made under the clock wall.
 */
const val MAX_PAST_HOLDS = 200

/** Stored form of a first sighting, `millis@key`; [decodeSeen] reads it back, null if damaged. */
fun encodeSeen(key: String, millis: Long): String = "$millis@$key"

fun decodeSeen(stored: String): Pair<String, Long>? {
    val at = stored.indexOf('@')
    if (at <= 0) return null
    val millis = stored.substring(0, at).toLongOrNull() ?: return null
    return stored.substring(at + 1) to millis
}

/** Stored form of a hold, `day|name`; [decodeHeldExam] reads it back, null if damaged. */
fun encodeHeldExam(exam: ExamWeek): String = "${exam.epochDay}|${exam.name}"

fun decodeHeldExam(stored: String): ExamWeek? {
    val bar = stored.indexOf('|')
    if (bar <= 0) return null
    val day = stored.substring(0, bar).toLongOrNull() ?: return null
    return ExamWeek(stored.substring(bar + 1), day)
}
