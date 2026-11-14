package com.ogenrwotdaniel.wakeproof.verify.math

import kotlin.random.Random

/** One arithmetic problem: what is shown and the only answer that dismisses it. */
data class MathProblem(val prompt: String, val answer: Int)

/**
 * Generates wake-up arithmetic and Euler-caliber mental calculation drills:
 * hard enough that it cannot be done half asleep, easy enough that nobody
 * needs a pen.
 *
 * When [eulerMode] is enabled (the default in Euler's Lab), the generator
 * includes the full "Euler Mental-Calculation & Estimation Dojo":
 * - Mental squares: `a² + c` (`11 <= a <= 31`)
 * - 2×2 determinants: `a × b − c × d`
 * - Cubes & powers of 2: `a³ + b`, `a³ − b` (`2 <= a <= 15`), and `2^n` (`7 <= n <= 13`, up to `2^13 = 8192`)
 * - Modular arithmetic & Euler's totient: `(a × b) mod m` and `φ(n)` (`n <= 100`, e.g., `φ(72) = 24`)
 * - Gauss / Euler series sums: `∑_{k=1..n} k` and `∑_{k=1..n} k²` (`n <= 25`)
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

    /** Euler's totient function φ(n): count of integers in 1..n coprime to n. */
    fun eulerTotient(n: Int): Int {
        require(n >= 1) { "n must be >= 1" }
        var result = n
        var remaining = n
        var p = 2
        while (p * p <= remaining) {
            if (remaining % p == 0) {
                while (remaining % p == 0) remaining /= p
                result -= result / p
            }
            p++
        }
        if (remaining > 1) result -= result / remaining
        return result
    }

    fun next(random: Random, eulerMode: Boolean = true): MathProblem {
        val poolSize = if (eulerMode) 11 else 4
        return when (random.nextInt(poolSize)) {
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
            3 -> {
                val a = random.nextInt(11, 30)
                val b = random.nextInt(3, 10)
                val c = random.nextInt(11, 99)
                MathProblem("$a × $b + $c", a * b + c)
            }
            // Euler's mental-square drill: a² + c (11 <= a <= 31).
            4 -> {
                val a = random.nextInt(11, 32)
                val c = random.nextInt(11, 99)
                MathProblem("$a² + $c", a * a + c)
            }
            // 2×2 determinant / Diophantine difference of products: a × b − c × d > 0.
            5 -> {
                val a = random.nextInt(12, 29)
                val b = random.nextInt(6, 15)
                val c = random.nextInt(3, 11)
                val d = random.nextInt(2, 6)
                MathProblem("$a × $b − $c × $d", a * b - c * d)
            }
            // Cubes: a³ ± b (2 <= a <= 15).
            6 -> {
                val a = random.nextInt(2, 16)
                val cube = a * a * a
                val b = random.nextInt(1, minOf(50, cube))
                if (random.nextBoolean()) {
                    MathProblem("$a³ + $b", cube + b)
                } else {
                    MathProblem("$a³ − $b", cube - b)
                }
            }
            // Powers of 2 up to 2^13 = 8192.
            7 -> {
                val exp = random.nextInt(7, 14)
                MathProblem("2^$exp", 1 shl exp)
            }
            // Modular arithmetic: (a × b) mod m (guaranteed > 0 via coprime/non-multiple).
            8 -> {
                val moduli = intArrayOf(7, 11, 13, 17, 19, 23, 29, 31, 37)
                val m = moduli[random.nextInt(moduli.size)]
                val a = random.nextInt(12, 95).let { if (it % m == 0) it + 1 else it }
                val b = random.nextInt(4, 25).let { if (it % m == 0) it + 1 else it }
                val rem = (a * b) % m
                MathProblem("($a × $b) mod $m", rem)
            }
            // Euler's totient φ(n) for n <= 100 (e.g., φ(72) = 24).
            9 -> {
                val n = random.nextInt(10, 101)
                MathProblem("φ($n)", eulerTotient(n))
            }
            // Series / Gauss & Euler sums: ∑_{k=1..n} k or ∑_{k=1..n} k² for n <= 25.
            else -> {
                val n = random.nextInt(8, 26)
                if (random.nextBoolean()) {
                    MathProblem("∑(k=1..$n) k", n * (n + 1) / 2)
                } else {
                    MathProblem("∑(k=1..$n) k²", n * (n + 1) * (2 * n + 1) / 6)
                }
            }
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
fun submitAnswer(
    progress: MathProgress,
    answer: Int?,
    random: Random,
    eulerMode: Boolean = true
): MathProgress {
    if (progress.done) return progress
    return if (answer == progress.problem.answer) {
        progress.copy(problem = MathChallenge.next(random, eulerMode), solved = progress.solved + 1)
    } else {
        progress.copy(
            problem = MathChallenge.next(random, eulerMode),
            solved = 0,
            mistakes = progress.mistakes + 1
        )
    }
}
