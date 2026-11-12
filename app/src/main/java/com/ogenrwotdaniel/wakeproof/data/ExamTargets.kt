package com.ogenrwotdaniel.wakeproof.data

/** An exam as the targets see it: a name and its day ([java.time.LocalDate.toEpochDay]). */
data class ExamDay(val name: String, val epochDay: Long)

/** Days before an exam from which the daily targets start to rise. */
const val EXAM_RAMP_DAYS = 14

/** The focus goal never asks for more than this, however near an exam is. */
const val MAX_FOCUS_GOAL_MINUTES = 720

/** Pure: the next exam on or after [day], or null. */
fun nearestExam(exams: List<ExamDay>, day: Long): ExamDay? =
    exams.filter { it.epochDay >= day }.minByOrNull { it.epochDay }

/**
 * Pure: how much the daily targets are raised [daysLeft] days before an exam,
 * in percent (2026-11-12, the user's request: the Exams screen knows the
 * dates, so the goal should rise by itself as one approaches). Nothing beyond
 * [EXAM_RAMP_DAYS]; a quarter more in the second week out, half more in the
 * last week, double in the last three days and on the day.
 */
fun examBoostPercent(daysLeft: Long?): Int = when {
    daysLeft == null || daysLeft < 0 || daysLeft > EXAM_RAMP_DAYS -> 0
    daysLeft >= 8 -> 25
    daysLeft >= 4 -> 50
    else -> 100
}

/**
 * Pure: the focus goal for [day], the user's own [baseMinutes] raised by the
 * nearest exam on or after it. Rounded up to a quarter of an hour. A base of
 * zero stays zero: no goal is not a goal to raise.
 */
fun focusGoalOn(day: Long, baseMinutes: Int, exams: List<ExamDay>): Int {
    if (baseMinutes <= 0) return 0
    val boost = examBoostPercent(nearestExam(exams, day)?.let { it.epochDay - day })
    if (boost == 0) return baseMinutes
    val raised = (baseMinutes * (100 + boost) + 99) / 100
    return ((raised + 14) / 15 * 15).coerceIn(baseMinutes, maxOf(baseMinutes, MAX_FOCUS_GOAL_MINUTES))
}

/** Pure: how many due cards a review alarm asks for on [day]: 10, rising to 20 before an exam. */
fun reviewCardsOn(day: Long, exams: List<ExamDay>, base: Int = 10): Int =
    when (examBoostPercent(nearestExam(exams, day)?.let { it.epochDay - day })) {
        0 -> base
        25 -> base + base / 2
        else -> base * 2
    }

/** One exam's suggested share of a day's focus goal. */
data class ExamShare(val name: String, val daysLeft: Long, val minutes: Int)

/**
 * Pure: [goalMinutes] split between the exams within [EXAM_RAMP_DAYS] of
 * [day], nearer ones getting more (weight 1 / (days left + 1)), in steps of
 * five minutes. Empty when no exam is that near: then the day is the user's.
 */
fun examSplit(day: Long, goalMinutes: Int, exams: List<ExamDay>): List<ExamShare> {
    val near = exams.filter { it.epochDay - day in 0..EXAM_RAMP_DAYS }.sortedBy { it.epochDay }
    if (near.isEmpty() || goalMinutes <= 0) return emptyList()
    val weights = near.map { 1.0 / (it.epochDay - day + 1) }
    val total = weights.sum()
    return near.mapIndexed { index, exam ->
        val minutes = (goalMinutes * weights[index] / total / 5).toInt() * 5
        ExamShare(exam.name, exam.epochDay - day, minutes)
    }
}

/**
 * Pure: whether a flashcard [subject] belongs to an exam called [examName].
 * Exams carry only a name, so this is by text: either contains the other,
 * ignoring case and spaces ("Calculus" and "Calculus II midterm").
 */
fun subjectMatchesExam(subject: String, examName: String): Boolean {
    val a = subject.lowercase().filter { it.isLetterOrDigit() }
    val b = examName.lowercase().filter { it.isLetterOrDigit() }
    return a.length >= 3 && b.length >= 3 && (a in b || b in a)
}

/** What today's focus goal is and why. */
data class FocusGoalToday(
    /** The goal the user set. */
    val baseMinutes: Int = 120,
    /** The goal the day is scored against. */
    val minutes: Int = 120,
    /** The exam raising it, if one is. */
    val exam: ExamDay? = null,
    val daysLeft: Long = 0,
    val split: List<ExamShare> = emptyList()
) {
    val raised: Boolean get() = minutes > baseMinutes
}

/** Pure: [FocusGoalToday] for [day]. */
fun focusGoalToday(day: Long, baseMinutes: Int, exams: List<ExamDay>): FocusGoalToday {
    val goal = focusGoalOn(day, baseMinutes, exams)
    val exam = nearestExam(exams, day)?.takeIf { it.epochDay - day <= EXAM_RAMP_DAYS }
    return FocusGoalToday(
        baseMinutes = baseMinutes,
        minutes = goal,
        exam = exam,
        daysLeft = exam?.let { it.epochDay - day } ?: 0,
        split = examSplit(day, goal, exams)
    )
}
