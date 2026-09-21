package com.ogenrwotdaniel.wakeproof.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test
    fun `under an hour stays in minutes`() {
        assertEquals("30 min", formatDurationMinutes(30))
        assertEquals("59 min", formatDurationMinutes(59))
    }

    @Test
    fun `whole hours drop the minutes`() {
        assertEquals("1h", formatDurationMinutes(60))
        assertEquals("12h", formatDurationMinutes(720))
    }

    @Test
    fun `mixed durations read as hours and minutes`() {
        assertEquals("2h 30m", formatDurationMinutes(150))
        assertEquals("7h 45m", formatDurationMinutes(465))
    }

    @Test
    fun `zero and negatives do not produce nonsense`() {
        assertEquals("0 min", formatDurationMinutes(0))
        assertEquals("0 min", formatDurationMinutes(-5))
    }
}
