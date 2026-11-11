package com.ogenrwotdaniel.wakeproof.distraction

/**
 * Exam week (2026-11-09, user's choice): for the [EXAM_WEEK_DAYS] days up to and
 * including an exam on the Exams screen, gated apps lose their 08:20–08:50
 * Monday–Thursday opening and stay shut all day. It tightens only, needs nothing
 * switched on, and lifts by itself the day after the exam. A week once begun is
 * held ([heldExamsAfter]): deleting or moving the exam does not end it early.
 * Revision material is on the Blackboard screen, which no wall but the night's covers.
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
 * What [heldExamsAfter] keeps: the holds, and when each exam row was first seen on
 * this device (row key to wall-clock millis).
 */
data class ExamHolds(val held: Set<ExamWeek>, val seen: Map<String, Long>)

/** How long after an exam row was first seen on this device, or last saved if that was earlier, it can be held: the time to fix a mistyped date. At least this long: holds are judged at each read. */
const val EXAM_HOLD_GRACE_MILLIS = 10 * 60_000L

/**
 * Pure: the holds to keep after reading [exams] (every upcoming row) today at
 * [nowMillis]. Every row's first sighting on this device is kept ([seen], by
 * [ExamRow.key]), whatever its date; renaming or re-dating keeps the key, and so
 * the sighting. An exam within a day of its week (its week, or the day before: a
 * hold does nothing until its week) is held once [EXAM_HOLD_GRACE_MILLIS] have
 * passed since that sighting, or since the row was last saved
 * ([ExamRow.updatedAtMillis]) when that was earlier: the time to fix a mistyped
 * date. A planned exam, seen long before, is so already held the day before its
 * week and cannot be deleted in its first minutes. The save counts because a
 * device that first sees an exam inside its week (a late sync, a reinstall, lost
 * prefs) would otherwise give one saved days ago, and long held elsewhere, ten
 * minutes to be deleted in; the grace ran where it was typed. Only when earlier
 * than the sighting, so saving a row again never starts a new grace, and one typed
 * minutes before midnight keeps the rest of its ten minutes. Real time, not a
 * count of reads, which a service restart would cut short. Held at once on a
 * doubted clock ([clockDoubted]) or one set back before the sighting:
 * waiting on a clock that can be wound would keep an exam unheld.
 * Sightings of rows no longer read (deleted, or moved to a past date) are kept, so
 * moving an exam to yesterday and back does not start its grace again. Not dropped
 * by age: the oldest sightings are the planned exams'. Only when more than
 * [MAX_SIGHTINGS] are stored do the oldest of those unread rows go (the newest
 * are kept, or a store once filled would forget every row moved away after); a row
 * still read never loses its sighting, or it would start a new grace at every read.
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
    nowMillis: Long,
    clockDoubted: Boolean
): ExamHolds {
    val sighted = exams.associate { it.key to (seen[it.key] ?: nowMillis) }
    val confirmed = exams.filter { it.epochDay - today in 0..EXAM_WEEK_DAYS }.filter { row ->
        val sighting = sighted.getValue(row.key)
        // Set back is judged on the sighting alone: a save a few seconds ahead of now is
        // an ordinary clock correction, or another device's clock, not a wound one.
        clockDoubted || nowMillis < sighting ||
            nowMillis - minOf(sighting, row.updatedAtMillis) >= EXAM_HOLD_GRACE_MILLIS
    }.map { ExamWeek(it.name, it.epochDay) }
    val (ahead, past) = (held + confirmed).partition { it.epochDay >= today }
    // On a doubted clock past holds stay, since "past" may be a date set forward,
    // but only the latest few, so the stored set stays small on a device that
    // never hears network time. Not by age: a clock set far enough forward would
    // age out a real one.
    val keptPast = if (clockProven) emptyList() else past.sortedByDescending { it.epochDay }.take(MAX_PAST_HOLDS)
    val lingering = seen.filterKeys { it !in sighted }.entries.sortedByDescending { it.value }
        .take((MAX_SIGHTINGS - sighted.size).coerceAtLeast(0)).associate { it.key to it.value }
    return ExamHolds((ahead + keptPast).toSet(), lingering + sighted)
}

/** Sightings stored before those of rows no longer read are dropped, oldest first; see [heldExamsAfter]. */
const val MAX_SIGHTINGS = 500

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

/**
 * A run of exam reads that found the clock doubted: the boot it belongs to
 * ([boot], the device's boot count), and when it began and was last seen, in
 * time since that boot.
 */
data class ClockDoubt(val boot: Int, val since: Long, val lastSeen: Long)

/** How long the clock must have stayed doubted before an exam is held with no grace: about one exam read. */
const val EXAM_CLOCK_DOUBT_MILLIS = 4 * 60_000L

/** The longest gap between two doubted reads of one run: two exam reads and some slack. */
const val EXAM_CLOCK_DOUBT_GAP_MILLIS = 12 * 60_000L

/**
 * Pure: whether the clock counts as doubted for [heldExamsAfter], and the run to
 * store (null clears it). One doubted read is not enough: a clock a few minutes
 * off after a reboot or a zone change rights itself, and must not hold a date
 * just typed with no grace. The doubt must have lasted [EXAM_CLOCK_DOUBT_MILLIS]
 * over reads no more than [EXAM_CLOCK_DOUBT_GAP_MILLIS] apart in the same boot.
 * A [stored] run from another boot, or one not seen for longer than the gap (the
 * service was down, or the trusted read that would have cleared it never ran),
 * begins again, or one doubted read would count as the second.
 */
fun examClockDoubted(doubtedNow: Boolean, stored: ClockDoubt?, boot: Int, nowElapsed: Long): Pair<Boolean, ClockDoubt?> {
    if (!doubtedNow) return false to null
    val running = stored != null && stored.boot == boot && stored.since <= stored.lastSeen &&
        nowElapsed >= stored.lastSeen && nowElapsed - stored.lastSeen <= EXAM_CLOCK_DOUBT_GAP_MILLIS
    val since = if (running) stored!!.since else nowElapsed
    return (nowElapsed - since >= EXAM_CLOCK_DOUBT_MILLIS) to ClockDoubt(boot, since, nowElapsed)
}

/** [ClockDoubt] as stored (`boot|since|lastSeen`), null when malformed. */
fun decodeClockDoubt(stored: String): ClockDoubt? {
    val parts = stored.split('|')
    if (parts.size != 3) return null
    return ClockDoubt(parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null)
}
