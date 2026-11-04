package com.ogenrwotdaniel.wakeproof.wear

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaComplicationContentTest {

    private val min = 60_000L
    private val today = 20_000L
    // 14:00 UTC on [today].
    private val now = today * 86_400_000L + 14 * 60 * min
    private val format = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val time: (Long) -> String = { format.format(Date(it)) }

    private fun event(key: String, title: String, beginMin: Int, lenMin: Int = 60, done: Boolean = false) =
        WatchAgendaEvent(key, title, now + beginMin * min, now + (beginMin + lenMin) * min, done)

    private val solving = event("a", "Problem Solving", 210) // 17:30
    private val lecture = event("b", "Lecture", 300) // 19:00
    private val lab = event("c", "Lab", 360) // 20:00

    private fun agenda(vararg events: WatchAgendaEvent, day: Long = today, allowed: Boolean = true) =
        WatchAgendaList(present = true, epochDay = day, allowed = allowed, events = events.toList())

    private fun content(list: WatchAgendaList, session: RunningSession? = null) =
        AgendaComplicationTexts.content(list, today, session, now, time)

    @Test
    fun `several events - next as title, the count of the rest as text`() {
        val c = content(agenda(lab, solving, lecture))
        assertEquals("17:30 Problem Solving", c.longTitle)
        assertEquals("+2 more", c.longText)
        assertEquals("Next", c.shortTitle)
        assertEquals("17:30", c.shortText)
        assertEquals("a", c.tapEventKey)
        assertEquals("WakeProof Today: 17:30 Problem Solving, 19:00 Lecture, 20:00 Lab", c.description)
    }

    @Test
    fun `two events - both named`() {
        val c = content(agenda(solving, lecture))
        assertEquals("17:30 Problem Solving", c.longTitle)
        assertEquals("19:00 Lecture", c.longText)
    }

    @Test
    fun `one event - Today over it`() {
        val c = content(agenda(solving))
        assertEquals("Today", c.longTitle)
        assertEquals("17:30 Problem Solving", c.longText)
        assertEquals("a", c.tapEventKey)
    }

    @Test
    fun `ended and ticked events are left out, an ongoing one says Now`() {
        val ended = event("x", "Breakfast", -120, 30)
        val ticked = event("y", "Gym", 30, done = true)
        val ongoing = event("z", "Seminar", -30, 90) // 13:30-15:00
        val c = content(agenda(ended, ticked, ongoing, solving))
        assertEquals("13:30 Seminar", c.longTitle)
        assertEquals("17:30 Problem Solving", c.longText)
        assertEquals("Now", c.shortTitle)
        assertEquals("13:30", c.shortText)
        assertEquals("z", c.tapEventKey)
    }

    @Test
    fun `none left today`() {
        val c = content(agenda(event("x", "Breakfast", -120, 30)))
        assertEquals("Today", c.longTitle)
        assertEquals(AgendaComplicationTexts.NOTHING_LEFT, c.longText)
        assertEquals("Done", c.shortText)
        assertNull(c.tapEventKey)
    }

    @Test
    fun `no agenda pushed, or a push from another day, asks for the phone`() {
        for (list in listOf(WatchAgendaList(), agenda(solving, day = today - 1))) {
            val c = content(list)
            assertEquals(AgendaComplicationTexts.NO_AGENDA, c.longText)
            assertEquals("—", c.shortText)
            assertNull(c.tapEventKey)
        }
    }

    @Test
    fun `calendar access off on the phone says so`() {
        assertEquals(AgendaComplicationTexts.CALENDAR_OFF, content(agenda(allowed = false)).longText)
    }

    @Test
    fun `watch session takes the headline with its end, next event beneath`() {
        val c = content(agenda(solving, lecture), RunningSession.Watch(now + 127 * min)) // 16:07
        assertEquals("Focus · ends 16:07", c.longTitle)
        assertEquals("17:30 Problem Solving", c.longText)
        assertEquals("Focus", c.shortTitle)
        assertEquals("16:07", c.shortText)
        assertEquals("a", c.tapEventKey)
    }

    @Test
    fun `phone session - block end in the short slot, unknown end says Phone`() {
        val known = content(agenda(), RunningSession.Phone(now + 20 * min))
        assertEquals("Focus on phone", known.longTitle)
        assertEquals(AgendaComplicationTexts.NOTHING_LEFT, known.longText)
        assertEquals("Focus", known.shortTitle)
        assertEquals("14:20", known.shortText)
        assertNull(known.tapEventKey)

        assertEquals("Phone", content(agenda(), RunningSession.Phone(null)).shortText)
    }

    @Test
    fun `session with no agenda still asks for the phone beneath`() {
        val c = content(WatchAgendaList(), RunningSession.Watch(now + 30 * min))
        assertEquals(AgendaComplicationTexts.NO_AGENDA, c.longText)
    }
}
