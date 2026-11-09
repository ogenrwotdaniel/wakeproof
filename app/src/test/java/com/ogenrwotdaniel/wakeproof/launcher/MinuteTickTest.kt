package com.ogenrwotdaniel.wakeproof.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MinuteTickTest {
    @Test
    fun `the home-screen tickers wake just past the next minute`() {
        assertEquals(60_050L, millisToNextMinute(second = 0, nano = 0))
        assertEquals(1_050L, millisToNextMinute(second = 59, nano = 0))
        // A wake a few ms early (59.998 s) still lands past the boundary, not before it.
        assertEquals(52L, millisToNextMinute(second = 59, nano = 998_000_000))
        assertEquals(30_550L, millisToNextMinute(second = 29, nano = 500_000_000))
    }
}
