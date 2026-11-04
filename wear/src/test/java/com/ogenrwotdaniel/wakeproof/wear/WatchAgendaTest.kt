package com.ogenrwotdaniel.wakeproof.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAgendaTest {

    private val min = 60_000L

    @Test
    fun `a push parses into events with their ticks, earliest first`() {
        val list = WatchAgenda.parse(
            epochDay = 20_396, allowed = true,
            keys = listOf("b:2", "a:1"), titles = listOf("Lab", "Lecture"),
            begins = longArrayOf(600 * min, 540 * min), ends = longArrayOf(690 * min, 600 * min),
            done = listOf("a:1")
        )
        assertTrue(list.present)
        assertEquals(
            listOf(
                WatchAgendaEvent("a:1", "Lecture", 540 * min, 600 * min, done = true),
                WatchAgendaEvent("b:2", "Lab", 600 * min, 690 * min, done = false)
            ),
            list.events
        )
    }

    @Test
    fun `uneven arrays are read to the shortest and blank keys dropped`() {
        val list = WatchAgenda.parse(
            epochDay = 1, allowed = true,
            keys = listOf("a", "", "c"), titles = listOf("A", "B", "C"),
            begins = longArrayOf(1, 2), ends = longArrayOf(10, 20, 30),
            done = null
        )
        assertEquals(listOf("a"), list.events.map { it.key })
    }

    @Test
    fun `missing arrays are an empty list, not a crash`() {
        val list = WatchAgenda.parse(1, false, null, null, null, null, null)
        assertTrue(list.events.isEmpty())
        assertFalse(list.allowed)
    }

    @Test
    fun `a list from another day is not today's`() {
        val list = WatchAgenda.parse(
            epochDay = 10, allowed = true, keys = listOf("a"), titles = listOf("A"),
            begins = longArrayOf(0), ends = longArrayOf(60 * min), done = null
        )
        assertEquals(1, list.forDay(10).size)
        assertTrue(list.forDay(11).isEmpty())
    }

    @Test
    fun `the confirm says the length plainly`() {
        assertEquals("1 h 25 m", WatchAgenda.lengthLabel(85))
        assertEquals("2 h", WatchAgenda.lengthLabel(120))
        assertEquals("45 min", WatchAgenda.lengthLabel(45))
    }

    @Test
    fun `the tick command carries the event key`() {
        assertEquals("agenda_done:123:456", TimerCommands.agendaDone("123:456"))
    }
}
