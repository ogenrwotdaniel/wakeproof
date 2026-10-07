package com.ogenrwotdaniel.wakeproof.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatCompactTest {
    @Test
    fun `compact durations fit above a chart bar`() {
        assertEquals("0m", formatCompactDuration(0))
        assertEquals("45m", formatCompactDuration(45))
        assertEquals("2h", formatCompactDuration(120))
        assertEquals("1h05", formatCompactDuration(65))
        assertEquals("0m", formatCompactDuration(-3))
    }
}
