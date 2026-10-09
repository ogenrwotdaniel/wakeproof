package com.ogenrwotdaniel.wakeproof.verify.math

import kotlin.random.Random

/** One arithmetic problem: what is shown and the only answer that dismisses it. */
data class MathProblem(val prompt: String, val answer: Int)

/**
 * Generates wake-up arithmetic: hard enough that it cannot be done half asleep,
 * easy enough that nobody needs a pen.
 *
 * The camera missions exclude a lot of people at 06:00 — no room to do
 * pushups, a partner asleep in the dark, a sprained wrist — and an alarm app
 * that only works for some mornings gets uninstalled. Arithmetic works in bed
 * with the lights off, and still demands an awake brain: a two-digit
 * multiplication cannot be answered on autopilot the way a swipe can.
 *
 * Pure and seedable so it is testable without a device. Answers are always
 * positive integers below 10 000, so the keypad never needs a minus sign or a
 * decimal point.
 */
object MathChallenge {

    /** How many problems in a row must be right before the alarm stops. */
    const val DEFAULT_PROBLEMS = 3
    const val MIN_PROBLEMS = 1
    const val MAX_PROBLEMS = 10

    /** Longest answer the generator produces, which caps keypad input. */
    const val MAX_ANSWER_DIGITS = 4

    fun next(random: Random): MathProblem = when (random.nextInt(4)) {
        // 47 × 8 — the classic: a carry the sleeping brain will fumble.
        0 -> {
            val a = random.nextInt(12, 99)
            val b = random.nextInt(3, 10)
            MathProblem("$a × $b", a * b)
        }
        // 386 + 457 — two carries.
        1 -> {
            val a = random.nextInt(120, 899)
            val b = random.nextInt(120, 899)
            MathProblem("$a + $b", a + b)
        }
        // 912 − 378 — larger first, so the answer stays positive.
        2 -> {
            val a = random.nextInt(400, 999)
            val b = random.nextInt(101, a - 50)
            MathProblem("$a − $b", a - b)
        }
        // 13 × 6 + 27 — two steps, which is what defeats guessing.
        else -> {
            val a = random.nextInt(11, 30)
            val b = random.nextInt(3, 10)
            val c = random.nextInt(11, 99)
            MathProblem("$a × $b + $c", a * b + c)
        }
    }
}

/**
 * Where a math mission stands. A wrong answer resets [solved] to zero and draws
 * a fresh problem — the run has to be unbroken, and retrying the same problem
 * would let someone brute-force it one digit at a time.
 */
data class MathProgress(
    val problem: MathProblem,
    val solved: Int,
    val required: Int,
    val mistakes: Int
) {
    val done: Boolean get() = solved >= required
}

/** Applies [answer] to [progress], drawing the next problem from [random]. Pure. */
fun submitAnswer(progress: MathProgress, answer: Int?, random: Random): MathProgress {
    if (progress.done) return progress
    return if (answer == progress.problem.answer) {
        progress.copy(problem = MathChallenge.next(random), solved = progress.solved + 1)
    } else {
        progress.copy(
            problem = MathChallenge.next(random),
            solved = 0,
            mistakes = progress.mistakes + 1
        )
    }
}
