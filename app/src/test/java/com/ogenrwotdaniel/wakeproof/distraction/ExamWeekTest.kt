package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExamWeekTest {
    @Test
    fun `exam week covers the seven days up to and including the exam`() {
        val today = 20_000L
        assertEquals(ExamWeek("Calculus", today), examWeekFor(listOf("Calculus" to today), today))
        assertEquals(ExamWeek("Physics", today + 6), examWeekFor(listOf("Physics" to today + 6), today))
        assertNull(examWeekFor(listOf("Physics" to today + 7), today))
        assertNull(examWeekFor(listOf("Past" to today - 1), today))
        assertNull(examWeekFor(emptyList(), today))
    }

    @Test
    fun `the nearest exam is the one named`() {
        val today = 20_000L
        assertEquals(
            "Chemistry",
            examWeekFor(listOf("Physics" to today + 5, "Chemistry" to today + 2), today)?.name
        )
    }

    @Test
    fun `chrome has one row of hours, and its shells only lend it their notifications`() {
        assertEquals(CHROME_HOURS, appHoursFor(AppGate.CHROME))
        // A shell's notifications follow Chrome; the shell itself is judged as itself.
        assertNull(appHoursFor("org.chromium.webapk.a1b2c3"))
        assertEquals(CHROME_HOURS, notificationHoursFor("org.chromium.webapk.a1b2c3"))
        assertNull(appHoursFor("com.chrome.beta"))
        assertEquals(CHROME_DAILY_CAP_MINUTES, withFixedCaps(emptyMap())[AppGate.CHROME]?.minutes)
    }
}
