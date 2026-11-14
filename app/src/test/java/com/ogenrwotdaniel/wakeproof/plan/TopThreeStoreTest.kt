package com.ogenrwotdaniel.wakeproof.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopThreeStoreTest {

    private fun hm(h: Int, m: Int) = h * 60 + m

    @Test
    fun `the ask is twenty minutes before bed at 19 40`() {
        assertEquals(hm(19, 40), TOP_THREE_MINUTES)
    }

    @Test
    fun `typed lines are trimmed, blanks dropped, three kept`() {
        assertEquals(listOf("Maths set", "Lab report"), cleanTopThree(listOf("  Maths set ", "", "Lab report")))
        assertEquals(3, cleanTopThree(listOf("a", "b", "c", "d")).size)
        assertEquals(MAX_TOP_THREE_ITEM_LENGTH, cleanTopThree(listOf("x".repeat(500))).single().length)
        assertTrue(cleanTopThree(listOf(" ", "")).isEmpty())
    }

    @Test
    fun `an evening's list is shown the next day and replaced by a second save`() {
        var all = withTopThree(TopThrees(), TopThree(100, listOf("first")))
        all = withTopThree(all, TopThree(100, listOf("second")))
        assertEquals(1, all.evenings.size)
        assertEquals(listOf("second"), topThreeFor(all, 101))
        assertEquals(listOf("second"), topThreeWrittenOn(all, 100))
        assertTrue(topThreeFor(all, 100).isEmpty())
        assertTrue(topThreeFor(all, 102).isEmpty())
    }

    @Test
    fun `only the newest evenings are kept`() {
        val all = (1L..40L).fold(TopThrees()) { kept, day -> withTopThree(kept, TopThree(day, listOf("x"))) }
        assertEquals(MAX_TOP_THREE_DAYS, all.evenings.size)
        assertEquals(40L, all.evenings.first().epochDay)
    }

    @Test
    fun `the newest evening is the one written last, whatever its day`() {
        val all = TopThrees(listOf(TopThree(12, listOf("b"), atMillis = 50), TopThree(11, listOf("a"), atMillis = 90)))
        assertEquals(11L, newestTopThree(all)?.epochDay)
        assertEquals(null, newestTopThree(TopThrees()))
    }

    @Test
    fun `the home screen shows today's list and tomorrow's once written`() {
        val all = TopThrees(listOf(TopThree(9, listOf("a")), TopThree(10, listOf("b"))))
        assertEquals(
            listOf("Today's top three" to listOf("a"), "Tomorrow's top three" to listOf("b")),
            topThreeShown(all, 10)
        )
        assertEquals(listOf("Today's top three" to listOf("b")), topThreeShown(all, 11))
        assertTrue(topThreeShown(all, 13).isEmpty())
    }

    @Test
    fun `a list written after midnight still belongs to last evening`() {
        assertEquals(9L, topThreeEveningDay(today = 10, minuteOfDay = hm(0, 15)))
        assertEquals(9L, topThreeEveningDay(today = 10, minuteOfDay = hm(2, 59)))
        assertEquals(10L, topThreeEveningDay(today = 10, minuteOfDay = hm(3, 0)))
        assertEquals(10L, topThreeEveningDay(today = 10, minuteOfDay = hm(19, 40)))
    }

    @Test
    fun `the evening card shows from 19 40 to midnight`() {
        assertFalse(topThreeEvening(hm(19, 39)))
        assertTrue(topThreeEvening(hm(19, 40)))
        assertTrue(topThreeEvening(hm(23, 59)))
        assertFalse(topThreeEvening(hm(0, 0)))
    }
}
