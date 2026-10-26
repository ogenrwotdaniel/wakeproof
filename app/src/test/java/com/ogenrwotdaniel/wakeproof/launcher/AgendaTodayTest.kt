package com.ogenrwotdaniel.wakeproof.launcher

import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgendaTodayTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 10, 27)
    private lateinit var saved: TimeZone

    @Before fun setZone() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(seoul))
    }

    @After fun restoreZone() = TimeZone.setDefault(saved)

    private fun allDay(date: LocalDate) = event(date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), true)
    private fun timed(date: LocalDate, hour: Int) =
        event(date.atTime(hour, 0).atZone(seoul).toInstant().toEpochMilli(), false)
    private fun event(begin: Long, allDay: Boolean) =
        AgendaEvent("k$begin", "t", begin, begin + 3_600_000, allDay, "c")

    @Test
    fun `yesterday's all-day event is not today's, even though it overlaps in Seoul`() {
        assertFalse(LauncherViewModel.onAgenda(allDay(today.minusDays(1)), today))
        assertTrue(LauncherViewModel.onAgenda(allDay(today), today))
    }

    @Test
    fun `tomorrow is left off, and a timed event running into today stays`() {
        assertFalse(LauncherViewModel.onAgenda(timed(today.plusDays(1), 9), today))
        assertFalse(LauncherViewModel.onAgenda(allDay(today.plusDays(1)), today))
        assertTrue(LauncherViewModel.onAgenda(timed(today, 14), today))
        assertTrue(LauncherViewModel.onAgenda(timed(today.minusDays(1), 23), today))
    }
}
