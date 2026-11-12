package com.ogenrwotdaniel.wakeproof.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamTargetsTest {

    private val day = 20_000L
    private fun exam(inDays: Long, name: String = "Calculus") = ExamDay(name, day + inDays)

    @Test
    fun `the goal is the user's own with no exam within two weeks`() {
        assertEquals(120, focusGoalOn(day, 120, emptyList()))
        assertEquals(120, focusGoalOn(day, 120, listOf(exam(15))))
        // An exam already sat raises nothing.
        assertEquals(120, focusGoalOn(day, 120, listOf(exam(-1))))
    }

    @Test
    fun `the goal rises in three steps as the exam nears`() {
        assertEquals(150, focusGoalOn(day, 120, listOf(exam(14))))
        assertEquals(150, focusGoalOn(day, 120, listOf(exam(8))))
        assertEquals(180, focusGoalOn(day, 120, listOf(exam(7))))
        assertEquals(180, focusGoalOn(day, 120, listOf(exam(4))))
        assertEquals(240, focusGoalOn(day, 120, listOf(exam(3))))
        assertEquals(240, focusGoalOn(day, 120, listOf(exam(0))))
    }

    @Test
    fun `the nearest exam decides, and the result is a quarter-hour step`() {
        assertEquals(240, focusGoalOn(day, 120, listOf(exam(10), exam(2, "Physics"))))
        // 100 min + 25% = 125, rounded up to 135.
        assertEquals(135, focusGoalOn(day, 100, listOf(exam(10))))
    }

    @Test
    fun `no goal stays no goal, and the raise stops at twelve hours`() {
        assertEquals(0, focusGoalOn(day, 0, listOf(exam(1))))
        assertEquals(720, focusGoalOn(day, 600, listOf(exam(1))))
        // A goal already at the ceiling is never lowered by the cap.
        assertEquals(720, focusGoalOn(day, 720, listOf(exam(1))))
    }

    @Test
    fun `a past day is scored against the exam that was ahead of it`() {
        val exams = listOf(ExamDay("Calculus", day))
        assertEquals(240, focusGoalOn(day - 2, 120, exams))
        assertEquals(120, focusGoalOn(day + 1, 120, exams))
    }

    @Test
    fun `review alarms ask for more cards before an exam`() {
        assertEquals(10, reviewCardsOn(day, emptyList()))
        assertEquals(15, reviewCardsOn(day, listOf(exam(12))))
        assertEquals(20, reviewCardsOn(day, listOf(exam(6))))
        assertEquals(20, reviewCardsOn(day, listOf(exam(1))))
    }

    @Test
    fun `the goal is split between near exams, the nearer getting more`() {
        val split = examSplit(day, 240, listOf(exam(1, "Physics"), exam(5, "Calculus"), exam(30, "Later")))
        assertEquals(listOf("Physics", "Calculus"), split.map { it.name })
        assertEquals(180, split[0].minutes)
        assertEquals(60, split[1].minutes)
        assertTrue(examSplit(day, 240, listOf(exam(30))).isEmpty())
    }

    @Test
    fun `today's goal names the exam raising it`() {
        val goal = focusGoalToday(day, 120, listOf(exam(5)))
        assertTrue(goal.raised)
        assertEquals(180, goal.minutes)
        assertEquals("Calculus", goal.exam?.name)
        assertEquals(5L, goal.daysLeft)
        val plain = focusGoalToday(day, 120, listOf(exam(40)))
        assertFalse(plain.raised)
        assertNull(plain.exam)
    }

    @Test
    fun `a subject belongs to an exam when one name holds the other`() {
        assertTrue(subjectMatchesExam("Calculus", "Calculus II midterm"))
        assertTrue(subjectMatchesExam("Linear Algebra", "linear algebra"))
        assertFalse(subjectMatchesExam("Physics", "Calculus final"))
        // Too short to mean anything.
        assertFalse(subjectMatchesExam("AI", "AI final"))
    }
}
