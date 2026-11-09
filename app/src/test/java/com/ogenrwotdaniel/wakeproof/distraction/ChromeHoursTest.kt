package com.ogenrwotdaniel.wakeproof.distraction

import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeHoursTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val monday = LocalDate.of(2026, 11, 9)
    private val saturday = LocalDate.of(2026, 11, 14)

    private fun event(title: String, day: LocalDate, hour: Int, minute: Int = 0) = AgendaEvent(
        key = title, title = title,
        beginMillis = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
        endMillis = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
        allDay = false, calendarName = ""
    )

    private fun allDay(title: String, day: LocalDate) = AgendaEvent(
        key = title, title = title,
        beginMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMillis = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allDay = true, calendarName = ""
    )

    @Test
    fun `deadlines are read from titles in English and Korean`() {
        assertEquals(23 * 60 + 59, deadlineMinuteToday(listOf(event("Essay due", monday, 23, 59)), monday, zone))
        assertEquals(18 * 60, deadlineMinuteToday(listOf(event("과제 마감", monday, 18)), monday, zone))
        assertEquals(17 * 60, deadlineMinuteToday(listOf(event("보고서 제출", monday, 17)), monday, zone))
        assertEquals(SLEEP_START_MINUTES, deadlineMinuteToday(listOf(allDay("Lab report deadline", monday)), monday, zone))
        assertNull(deadlineMinuteToday(listOf(event("Lunch with Due", monday, 12).copy(title = "Lunch")), monday, zone))
        assertNull(deadlineMinuteToday(listOf(event("Essay due", monday.plusDays(1), 23)), monday, zone))
        // "Overdue" and "subdue" are not deadlines.
        assertNull(deadlineMinuteToday(listOf(event("overdue books", monday, 12)), monday, zone))
    }

    @Test
    fun `a deadline keeps chrome open until it, never past the sleep wall`() {
        assertEquals(CHROME_OPEN_END_MINUTES, chromeCloseMinute(DayOfWeek.MONDAY))
        assertEquals(19 * 60, chromeCloseMinute(DayOfWeek.MONDAY, 19 * 60))
        assertEquals(SLEEP_START_MINUTES, chromeCloseMinute(DayOfWeek.MONDAY, 23 * 60 + 59))
        // An earlier deadline never shortens the weekday.
        assertEquals(CHROME_OPEN_END_MINUTES, chromeCloseMinute(DayOfWeek.MONDAY, 11 * 60))
        // Weekdays only: a self-made "Essay due" must not open the weekend.
        assertNull(chromeCloseMinute(DayOfWeek.SATURDAY))
        assertNull(chromeCloseMinute(DayOfWeek.SATURDAY, 14 * 60))
        assertTrue(chromeHoursShut(DayOfWeek.SATURDAY, 10 * 60, 14 * 60))
        assertTrue(chromeHoursShut(DayOfWeek.MONDAY, 20 * 60 + 30, 20 * 60))
        assertFalse(chromeHoursShut(DayOfWeek.MONDAY, 19 * 60 + 59, 20 * 60))
    }

    @Test
    fun `chrome warns ten minutes before shutting and five before the hour is spent, once each`() {
        assertEquals(ChromeWarning.CLOSING, chromeWarningDue(15 * 60 + 50, 16 * 60, 10, 60, false, false))
        assertNull(chromeWarningDue(15 * 60 + 49, 16 * 60, 10, 60, false, false))
        assertNull(chromeWarningDue(15 * 60 + 50, 16 * 60, 10, 60, true, false))
        assertEquals(ChromeWarning.HOUR_ALMOST_SPENT, chromeWarningDue(10 * 60, 16 * 60, 55, 60, false, false))
        assertNull(chromeWarningDue(10 * 60, 16 * 60, 54, 60, false, false))
        assertNull(chromeWarningDue(10 * 60, 16 * 60, 55, 60, false, true))
        assertNull(chromeWarningDue(10 * 60, 16 * 60, 60, 60, false, false))
    }

    @Test
    fun `the home screen line says when chrome opens and what is left`() {
        val fmt = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
        assertEquals("Chrome opens today at 08:40", chromeStatusLine(DayOfWeek.MONDAY, 7 * 60, null, 0, 60, fmt))
        assertEquals("Chrome: 25 min left, open until 16:00", chromeStatusLine(DayOfWeek.MONDAY, 10 * 60, null, 35, 60, fmt))
        assertEquals(
            "Chrome: 60 min left, open until 21:00 (deadline today)",
            chromeStatusLine(DayOfWeek.MONDAY, 17 * 60, 23 * 60, 0, 60, fmt)
        )
        assertEquals("Chrome opens Monday at 08:40", chromeStatusLine(DayOfWeek.FRIDAY, 17 * 60, null, 0, 60, fmt))
        assertEquals(
            "Chrome's hour is spent. It opens tomorrow at 08:40",
            chromeStatusLine(DayOfWeek.TUESDAY, 11 * 60, null, 60, 60, fmt)
        )
        assertEquals("Chrome opens Monday at 08:40", chromeStatusLine(DayOfWeek.SATURDAY, 10 * 60, null, 0, 60, fmt))
    }

    @Test
    fun `chrome's own screens and installed sites stay chrome`() {
        assertEquals(
            AppGate.CHROME,
            AppGate.effectivePackage(AppGate.CHROME, "org.chromium.chrome.browser.webapps.WebappActivity")
        )
        // Pages other apps open inside Chrome are Chrome's too (see AGENTS.md: crediting
        // them to the opener was tried and could not be made safe).
        assertEquals(
            AppGate.CHROME,
            AppGate.effectivePackage(AppGate.CHROME, "org.chromium.chrome.browser.customtabs.CustomTabActivity")
        )
    }
}
