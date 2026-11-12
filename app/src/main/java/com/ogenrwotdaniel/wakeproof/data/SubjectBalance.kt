package com.ogenrwotdaniel.wakeproof.data

/** One course's share of the week's focus time beside its share of the credits. */
data class SubjectShare(
    val subject: String,
    val minutes: Int,
    val credits: Int,
    /** 0..1 of the focus time given to courses that carry credits. */
    val timeShare: Double,
    /** 0..1 of all the credits. */
    val creditShare: Double
)

/** A course counts as neglected below this fraction of the time its credits would earn it. */
const val NEGLECT_BELOW = 0.6

/** Under this much credited focus time a week says nothing about balance. */
const val BALANCE_MIN_MINUTES = 60

/**
 * Pure: every course with credits, its time beside its weight (2026-11-12, the
 * user's request: hours per course against each course's credit weight).
 * Courses without credits are left out of both shares, so "General" or
 * untagged work does not make every course look neglected.
 */
fun subjectBalance(minutesBySubject: Map<String, Int>, credits: Map<String, Int>): List<SubjectShare> {
    val weighted = credits.filterValues { it > 0 }
    val totalCredits = weighted.values.sum()
    if (totalCredits == 0) return emptyList()
    val totalMinutes = weighted.keys.sumOf { minutesBySubject[it] ?: 0 }
    return weighted.map { (subject, credit) ->
        val minutes = minutesBySubject[subject] ?: 0
        SubjectShare(
            subject = subject,
            minutes = minutes,
            credits = credit,
            timeShare = if (totalMinutes == 0) 0.0 else minutes.toDouble() / totalMinutes,
            creditShare = credit.toDouble() / totalCredits
        )
    }.sortedByDescending { it.creditShare - it.timeShare }
}

/**
 * Pure: the course furthest behind its weight, when one is under
 * [NEGLECT_BELOW] of it. Null with fewer than two courses or too little time
 * to judge.
 */
fun neglectedSubject(shares: List<SubjectShare>): SubjectShare? {
    if (shares.size < 2 || shares.sumOf { it.minutes } < BALANCE_MIN_MINUTES) return null
    return shares.filter { it.timeShare < it.creditShare * NEGLECT_BELOW }
        .maxByOrNull { it.creditShare - it.timeShare }
}

/** Most credits a course can be given. */
const val MAX_SUBJECT_CREDITS = 12

/** Pure: credits as stored, one "credits:name" per line. */
fun encodeSubjectCredits(credits: Map<String, Int>): String =
    credits.filterValues { it > 0 }.entries.joinToString("\n") { "${it.value}:${it.key}" }

/** Pure: the reverse of [encodeSubjectCredits]; lines that do not parse are dropped. */
fun decodeSubjectCredits(stored: String?): Map<String, Int> =
    stored.orEmpty().lineSequence().mapNotNull { line ->
        val credits = line.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
        val name = line.substringAfter(':', "")
        if (name.isBlank() || credits <= 0) null else name to credits.coerceAtMost(MAX_SUBJECT_CREDITS)
    }.toMap()
