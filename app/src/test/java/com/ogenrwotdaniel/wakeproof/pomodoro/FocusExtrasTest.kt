package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FocusExtrasTest {

    @Test
    fun `consecutive blocks get different quotes and a day never repeats early`() {
        val day = 20_000L
        val session = (0 until FocusQuotes.all.size).map { FocusQuotes.forBlock(day, it) }
        assertEquals(FocusQuotes.all.size, session.toSet().size)
        assertNotEquals(FocusQuotes.forBlock(day, 0), FocusQuotes.forBlock(day + 1, 0))
    }

    @Test
    fun `quotes cope with negative inputs`() {
        FocusQuotes.forBlock(-5, -3)
    }

    @Test
    fun `a tree grows through the block and withers when abandoned`() {
        assertEquals("🌱", FocusTree.growing(0f, withered = false))
        assertEquals("🌳", FocusTree.growing(1f, withered = false))
        assertEquals(FocusTree.WITHERED, FocusTree.growing(0.7f, withered = true))
    }

    @Test
    fun `longer blocks leave bigger trees`() {
        assertEquals("🌿", FocusTree.grown(15, withered = false))
        assertEquals("🌳", FocusTree.grown(25, withered = false))
        assertEquals("🌲", FocusTree.grown(50, withered = false))
        assertEquals(FocusTree.WITHERED, FocusTree.grown(50, withered = true))
    }
}
