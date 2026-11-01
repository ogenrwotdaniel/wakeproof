package com.ogenrwotdaniel.wakeproof.agenda

import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventFocusTest {

    private val min = 60_000L

    private fun event(key: String = "e", begin: Long, end: Long, allDay: Boolean = false) =
        AgendaEvent(key, "Lecture", begin, end, allDay, "")

    @Test
    fun `a session for an event not yet started lasts the whole event`() {
        assertEquals(85, eventFocusMinutes(event(begin = 100 * min, end = 185 * min), nowMillis = 90 * min))
    }

    @Test
    fun `tapped mid-event, the session ends with the event`() {
        assertEquals(65, eventFocusMinutes(event(begin = 100 * min, end = 185 * min), nowMillis = 120 * min))
    }

    @Test
    fun `all-day, finished and nearly finished events only tick`() {
        assertNull(eventFocusMinutes(event(begin = 0, end = 1_440 * min, allDay = true), nowMillis = 0))
        assertNull(eventFocusMinutes(event(begin = 0, end = 60 * min), nowMillis = 60 * min))
        assertNull(eventFocusMinutes(event(begin = 0, end = 60 * min), nowMillis = 51 * min))
    }

    @Test
    fun `the next reminder is five minutes before the next unticked timed event`() {
        val events = listOf(
            event("allday", begin = 0, end = 1_440 * min, allDay = true),
            event("done", begin = 200 * min, end = 260 * min),
            event("next", begin = 300 * min, end = 360 * min),
            event("later", begin = 400 * min, end = 460 * min)
        )
        assertEquals(295 * min, nextReminderAt(events, setOf("done"), nowMillis = 100 * min))
        // Inside the lead already: the next one after it.
        assertEquals(395 * min, nextReminderAt(events, setOf("done"), nowMillis = 296 * min))
        assertNull(nextReminderAt(events, emptySet(), nowMillis = 500 * min))
    }

    @Test
    fun `a firing reminds of every unticked event starting within the lead`() {
        val events = listOf(
            event("a", begin = 300 * min, end = 360 * min),
            event("b", begin = 300 * min, end = 330 * min),
            event("c", begin = 330 * min, end = 360 * min),
            event("done", begin = 300 * min, end = 360 * min)
        )
        assertEquals(listOf("a", "b"), eventsToRemind(events, setOf("done"), nowMillis = 295 * min).map { it.key })
    }

    @Test
    fun `lengths read plainly`() {
        assertEquals("45 min", eventFocusLabel(45))
        assertEquals("2 h", eventFocusLabel(120))
        assertEquals("1 h 25 min", eventFocusLabel(85))
    }
}
