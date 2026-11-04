package com.ogenrwotdaniel.wakeproof.wear

import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WearAgendaSyncTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 4)
    private val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private fun event(key: String, begin: Long, end: Long, allDay: Boolean = false) =
        AgendaEvent(key, key, begin, end, allDay, "")

    @Test
    fun `the watch gets today's timed events, earliest first`() {
        val events = listOf(
            event("later", dayStart + 900 * min, dayStart + 990 * min),
            event("allday", dayStart, dayStart + 1_440 * min, allDay = true),
            event("tomorrow", dayStart + 1_440 * min + 60 * min, dayStart + 1_440 * min + 120 * min),
            event("overnight", dayStart - 60 * min, dayStart + 30 * min),
            event("ended-yesterday", dayStart - 120 * min, dayStart - 60 * min),
            event("morning", dayStart + 540 * min, dayStart + 600 * min)
        )
        assertEquals(
            listOf("overnight", "morning", "later"),
            WearAgendaSync.watchAgenda(events, today, zone).map { it.key }
        )
    }
}
