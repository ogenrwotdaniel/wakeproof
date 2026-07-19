package com.ogenrwotdaniel.wakeproof.data

import com.ogenrwotdaniel.wakeproof.data.db.Flashcard
import kotlin.math.max
import kotlin.math.roundToInt

/** Anki-style review grades mapped onto SM-2 quality scores. */
enum class ReviewGrade(val quality: Int, val label: String) {
    AGAIN(0, "Again"),
    HARD(3, "Hard"),
    GOOD(4, "Good"),
    EASY(5, "Easy")
}

/**
 * SuperMemo SM-2 scheduling (the algorithm behind Anki): easiness drifts with
 * answer quality, intervals go 1 day → 6 days → interval × easiness. A failed
 * card ([ReviewGrade.AGAIN]) resets its streak and stays due today so it
 * repeats within the same session. Pure Kotlin — unit-tested without Android.
 */
object Sm2Scheduler {

    fun review(card: Flashcard, grade: ReviewGrade, todayEpochDay: Long): Flashcard {
        val easiness = newEasiness(card.easiness, grade.quality)
        if (grade.quality < 3) {
            return card.copy(
                repetitions = 0,
                intervalDays = 0,
                dueEpochDay = todayEpochDay,
                easiness = easiness
            )
        }
        val repetitions = card.repetitions + 1
        val intervalDays = when (repetitions) {
            1 -> 1
            2 -> 6
            else -> (card.intervalDays * card.easiness).roundToInt().coerceAtLeast(1)
        }
        return card.copy(
            repetitions = repetitions,
            intervalDays = intervalDays,
            dueEpochDay = todayEpochDay + intervalDays,
            easiness = easiness
        )
    }

    private fun newEasiness(easiness: Double, quality: Int): Double =
        max(MIN_EASINESS, easiness + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))

    private const val MIN_EASINESS = 1.3
}
