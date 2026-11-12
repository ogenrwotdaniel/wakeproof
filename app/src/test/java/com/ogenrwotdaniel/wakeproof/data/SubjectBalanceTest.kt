package com.ogenrwotdaniel.wakeproof.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectBalanceTest {

    private val credits = mapOf("Calculus" to 3, "Physics" to 3, "Writing" to 2)

    @Test
    fun `each credited course gets its share of time and of credits`() {
        val shares = subjectBalance(mapOf("Calculus" to 300, "Physics" to 60, "Writing" to 40, "General" to 500), credits)
        val physics = shares.first { it.subject == "Physics" }
        // General carries no credits and is in neither share.
        assertEquals(0.15, physics.timeShare, 0.001)
        assertEquals(0.375, physics.creditShare, 0.001)
        assertEquals(3, shares.size)
    }

    @Test
    fun `the course furthest behind its weight is the neglected one`() {
        val shares = subjectBalance(mapOf("Calculus" to 300, "Physics" to 60, "Writing" to 40), credits)
        assertEquals("Physics", neglectedSubject(shares)?.subject)
    }

    @Test
    fun `a week in proportion neglects nothing`() {
        val shares = subjectBalance(mapOf("Calculus" to 180, "Physics" to 170, "Writing" to 110), credits)
        assertNull(neglectedSubject(shares))
    }

    @Test
    fun `too little time, one course or no credits says nothing`() {
        assertNull(neglectedSubject(subjectBalance(mapOf("Calculus" to 30), credits)))
        assertNull(neglectedSubject(subjectBalance(mapOf("Calculus" to 300), mapOf("Calculus" to 3))))
        assertTrue(subjectBalance(mapOf("Calculus" to 300), emptyMap()).isEmpty())
    }

    @Test
    fun `a course with credits and no time at all is neglected`() {
        val shares = subjectBalance(mapOf("Calculus" to 200), mapOf("Calculus" to 3, "Physics" to 3))
        assertEquals("Physics", neglectedSubject(shares)?.subject)
    }

    @Test
    fun `credits survive being stored, names with colons included`() {
        val stored = encodeSubjectCredits(mapOf("Calculus" to 3, "CS: Algorithms" to 4, "Dropped" to 0))
        assertEquals(mapOf("Calculus" to 3, "CS: Algorithms" to 4), decodeSubjectCredits(stored))
        assertTrue(decodeSubjectCredits(null).isEmpty())
        assertTrue(decodeSubjectCredits("rubbish\nx:Name\n:3").isEmpty())
        assertEquals(mapOf("Big" to MAX_SUBJECT_CREDITS), decodeSubjectCredits("99:Big"))
    }
}
