package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.CompletionLog
import com.ogenrwotdaniel.wakeproof.data.db.CompletionStatus
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusTree

/** One day of the forest: a tree per focus block, in the order they grew. */
data class ForestDay(
    val epochDay: Long,
    val trees: List<String>,
    /** Minutes that counted — withered blocks excluded. */
    val minutes: Int,
    val withered: Int
)

/**
 * The focus log drawn as a forest, newest day first, [days] days ending at
 * [today]. Every FOCUS log is a tree: VERIFIED ones grown to a size set by the
 * block length, SKIPPED ones (the tree withered) as [FocusTree.WITHERED].
 *
 * There is no separate table for trees: the forest is a picture of the log that
 * already exists, so it syncs, backs up and restores with everything else and
 * cannot disagree with the focus minutes on Home.
 */
fun forestDays(logs: List<CompletionLog>, today: Long, days: Int): List<ForestDay> {
    val byDay = logs.filter { it.kind == GoalKind.FOCUS && !it.deleted }.groupBy { it.epochDay }
    return (0 until days).map { offset ->
        val day = today - offset
        val blocks = byDay[day].orEmpty().sortedBy { it.id }
        val withered = blocks.count { it.status == CompletionStatus.SKIPPED }
        ForestDay(
            epochDay = day,
            trees = blocks.map { FocusTree.grown(it.amount, it.status == CompletionStatus.SKIPPED) },
            minutes = blocks.filter { it.status == CompletionStatus.VERIFIED }.sumOf { it.amount },
            withered = withered
        )
    }
}
