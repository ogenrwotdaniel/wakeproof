package com.ogenrwotdaniel.wakeproof.verify.math

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathChallengeTest {

    @Test
    fun `every answer is positive and fits the keypad in both modes`() {
        val random = Random(7)
        repeat(10_000) {
            val eulerProblem = MathChallenge.next(random, eulerMode = true)
            assertTrue(eulerProblem.prompt, eulerProblem.answer > 0)
            assertTrue(eulerProblem.prompt, eulerProblem.answer.toString().length <= MathChallenge.MAX_ANSWER_DIGITS)

            val standardProblem = MathChallenge.next(random, eulerMode = false)
            assertTrue(standardProblem.prompt, standardProblem.answer > 0)
            assertTrue(standardProblem.prompt, standardProblem.answer.toString().length <= MathChallenge.MAX_ANSWER_DIGITS)
        }
    }

    @Test
    fun `the prompt evaluates to the stated answer across all Euler Dojo families`() {
        val random = Random(11)
        val seenShapes = mutableSetOf<String>()
        repeat(3_000) {
            val problem = MathChallenge.next(random, eulerMode = true)
            assertEquals(problem.prompt, problem.answer, evaluate(problem.prompt))
            when {
                problem.prompt.contains("² +") -> seenShapes += "square"
                problem.prompt.contains("³") -> seenShapes += "cube"
                problem.prompt.startsWith("2^") -> seenShapes += "power2"
                problem.prompt.contains("mod") -> seenShapes += "mod"
                problem.prompt.startsWith("φ(") -> seenShapes += "totient"
                problem.prompt.endsWith(") k") -> seenShapes += "gauss_k"
                problem.prompt.endsWith(") k²") -> seenShapes += "gauss_k2"
            }
        }
        assertEquals(
            setOf("square", "cube", "power2", "mod", "totient", "gauss_k", "gauss_k2"),
            seenShapes
        )
    }

    @Test
    fun `eulerTotient computes canonical values`() {
        assertEquals(1, MathChallenge.eulerTotient(1))
        assertEquals(1, MathChallenge.eulerTotient(2))
        assertEquals(4, MathChallenge.eulerTotient(10))
        assertEquals(12, MathChallenge.eulerTotient(13))
        assertEquals(24, MathChallenge.eulerTotient(72))
        assertEquals(40, MathChallenge.eulerTotient(100))
    }

    @Test
    fun `a run of right answers finishes the mission`() {
        val random = Random(3)
        var progress = MathProgress(MathChallenge.next(random), solved = 0, required = 3, mistakes = 0)
        repeat(3) { progress = submitAnswer(progress, progress.problem.answer, random) }
        assertTrue(progress.done)
        assertEquals(0, progress.mistakes)
    }

    @Test
    fun `a wrong answer resets the run and changes the problem`() {
        val random = Random(5)
        var progress = MathProgress(MathChallenge.next(random), solved = 0, required = 3, mistakes = 0)
        progress = submitAnswer(progress, progress.problem.answer, random)
        progress = submitAnswer(progress, progress.problem.answer, random)
        val before = progress.problem
        progress = submitAnswer(progress, before.answer + 1, random)
        assertEquals(0, progress.solved)
        assertEquals(1, progress.mistakes)
        assertFalse(progress.done)
        // A fresh problem, so the wrong digit cannot simply be corrected and resent.
        assertNotEquals(before, progress.problem)
    }

    @Test
    fun `an empty answer counts as wrong`() {
        val random = Random(9)
        val start = MathProgress(MathChallenge.next(random), solved = 1, required = 3, mistakes = 0)
        val after = submitAnswer(start, null, random)
        assertEquals(0, after.solved)
        assertEquals(1, after.mistakes)
    }

    @Test
    fun `a finished mission ignores further answers`() {
        val random = Random(1)
        val done = MathProgress(MathChallenge.next(random), solved = 3, required = 3, mistakes = 2)
        assertEquals(done, submitAnswer(done, null, random))
    }

    /** Evaluates every prompt shape emitted by [MathChallenge]. */
    private fun evaluate(prompt: String): Int {
        // 2^n
        if (prompt.startsWith("2^")) {
            val exp = prompt.removePrefix("2^").toInt()
            return 1 shl exp
        }
        // φ(n)
        if (prompt.startsWith("φ(")) {
            val n = prompt.removePrefix("φ(").removeSuffix(")").toInt()
            return MathChallenge.eulerTotient(n)
        }
        // (a × b) mod m
        if (prompt.startsWith("(") && prompt.contains(") mod ")) {
            val parts = prompt.removePrefix("(").split(") mod ")
            val m = parts[1].toInt()
            val mul = parts[0].split(" × ")
            return (mul[0].toInt() * mul[1].toInt()) % m
        }
        // ∑(k=1..n) k or ∑(k=1..n) k²
        if (prompt.startsWith("∑(k=1..")) {
            val inside = prompt.removePrefix("∑(k=1..")
            val n = inside.substringBefore(")").toInt()
            return if (inside.endsWith("k²")) {
                n * (n + 1) * (2 * n + 1) / 6
            } else {
                n * (n + 1) / 2
            }
        }
        // Standard arithmetic with optional ² or ³ on operands
        fun parseTerm(token: String): Int = when {
            token.endsWith("²") -> {
                val base = token.removeSuffix("²").toInt()
                base * base
            }
            token.endsWith("³") -> {
                val base = token.removeSuffix("³").toInt()
                base * base * base
            }
            else -> token.toInt()
        }
        val tokens = prompt.split(" ")
        val terms = mutableListOf(parseTerm(tokens[0]))
        val ops = mutableListOf<String>()
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val value = parseTerm(tokens[i + 1])
            if (op == "×") terms[terms.lastIndex] = terms.last() * value
            else { ops += op; terms += value }
            i += 2
        }
        var result = terms[0]
        ops.forEachIndexed { index, op ->
            result = if (op == "+") result + terms[index + 1] else result - terms[index + 1]
        }
        return result
    }
}
