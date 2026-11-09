package com.ogenrwotdaniel.wakeproof.distraction

/**
 * Exam week (2026-11-09, user's choice): for the [EXAM_WEEK_DAYS] days up to and
 * including an exam on the Exams screen, gated apps lose their 08:40–08:50
 * Monday–Thursday opening and stay shut all day. It tightens only, needs nothing
 * switched on, and lifts by itself the day after the exam. Chrome keeps its own
 * hours: it is where the revision material is.
 */
const val EXAM_WEEK_DAYS = 7

/** The exam that makes [today] an exam-week day, the nearest one; see [examWeekFor]. */
data class ExamWeek(val name: String, val epochDay: Long)

/**
 * Pure: the nearest of [exams] (name to epoch day) falling within the next
 * [EXAM_WEEK_DAYS] days counting [today], or null when today is an ordinary day.
 */
fun examWeekFor(exams: List<Pair<String, Long>>, today: Long): ExamWeek? =
    exams.filter { (_, day) -> day - today in 0 until EXAM_WEEK_DAYS }
        .minByOrNull { it.second }
        ?.let { (name, day) -> ExamWeek(name, day) }
