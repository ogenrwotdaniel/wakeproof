package com.ogenrwotdaniel.wakeproof.verify.math

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathChallengeTest {

    @Test
    fun `every answer is positive and fits the keypad`() {
        val random = Random(7)
        repeat(10_000) {
            val problem = MathChallenge.next(random)
            assertTrue(problem.prompt, problem.answer > 0)
            assertTrue(problem.prompt, problem.answer.toString().length <= MathChallenge.MAX_ANSWER_DIGITS)
        }
    }

    @Test
    fun `the prompt evaluates to the stated answer`() {
        val random = Random(11)
        repeat(2_000) {
            val problem = MathChallenge.next(random)
            assertEquals(problem.prompt, problem.answer, evaluate(problem.prompt))
        }
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

    /** Left-to-right with × before +/−, which is all the generator emits. */
    private fun evaluate(prompt: String): Int {
        val tokens = prompt.split(" ")
        val terms = mutableListOf(tokens[0].toInt())
        val ops = mutableListOf<String>()
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val value = tokens[i + 1].toInt()
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
