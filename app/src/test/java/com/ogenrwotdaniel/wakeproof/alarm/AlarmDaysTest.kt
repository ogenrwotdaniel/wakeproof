package com.ogenrwotdaniel.wakeproof.alarm

import java.time.DayOfWeek
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmDaysTest {

    /** Wednesday 2026-09-02, 07:00. */
    private val wednesdayMorning = LocalDateTime.of(2026, 9, 2, 7, 0)

    @Test
    fun `weekday and weekend masks cover the week between them`() {
        assertEquals(AlarmDays.EVERY_DAY, AlarmDays.WEEKDAYS or AlarmDays.WEEKENDS)
        assertEquals(0, AlarmDays.WEEKDAYS and AlarmDays.WEEKENDS)
        assertTrue(AlarmDays.contains(AlarmDays.WEEKDAYS, DayOfWeek.FRIDAY))
        assertFalse(AlarmDays.contains(AlarmDays.WEEKDAYS, DayOfWeek.SATURDAY))
        assertTrue(AlarmDays.contains(AlarmDays.WEEKENDS, DayOfWeek.SUNDAY))
    }

    @Test
    fun `an empty mask reads as every day, never as never`() {
        // The house rule: an alarm clock that is unsure still wakes the user.
        assertEquals(AlarmDays.EVERY_DAY, AlarmDays.normalise(0))
        DayOfWeek.entries.forEach { assertTrue(AlarmDays.contains(0, it)) }
    }

    @Test
    fun `bits outside the week are discarded`() {
        assertEquals(AlarmDays.EVERY_DAY, AlarmDays.normalise(-1))
        assertEquals(1, AlarmDays.normalise(0b1000_0001))
    }

    @Test
    fun `toggling the last remaining day is refused`() {
        val mondayOnly = 1 shl 0
        assertEquals(mondayOnly, AlarmDays.toggle(mondayOnly, DayOfWeek.MONDAY))
    }

    @Test
    fun `toggle adds and removes days`() {
        val weekdaysWithoutFriday = AlarmDays.toggle(AlarmDays.WEEKDAYS, DayOfWeek.FRIDAY)
        assertFalse(AlarmDays.contains(weekdaysWithoutFriday, DayOfWeek.FRIDAY))
        assertTrue(AlarmDays.contains(weekdaysWithoutFriday, DayOfWeek.THURSDAY))
        assertEquals(
            AlarmDays.WEEKDAYS,
            AlarmDays.toggle(weekdaysWithoutFriday, DayOfWeek.FRIDAY)
        )
    }

    @Test
    fun `labels name the common masks and list the rest`() {
        assertEquals("Every day", AlarmDays.label(AlarmDays.EVERY_DAY))
        assertEquals("Weekdays", AlarmDays.label(AlarmDays.WEEKDAYS))
        assertEquals("Weekends", AlarmDays.label(AlarmDays.WEEKENDS))
        val monWedFri = (1 shl 0) or (1 shl 2) or (1 shl 4)
        assertEquals("Mon, Wed, Fri", AlarmDays.label(monWedFri))
    }

    @Test
    fun `a time still to come today fires today`() {
        val next = nextAlarmTrigger(wednesdayMorning, 9, 0, AlarmDays.EVERY_DAY)
        assertEquals(LocalDateTime.of(2026, 9, 2, 9, 0), next)
    }

    @Test
    fun `a time already past today rolls to tomorrow`() {
        val next = nextAlarmTrigger(wednesdayMorning, 5, 0, AlarmDays.EVERY_DAY)
        assertEquals(LocalDateTime.of(2026, 9, 3, 5, 0), next)
    }

    @Test
    fun `the alarm time exactly now counts as past`() {
        // setAlarmClock for "right now" fires immediately, which for a daily
        // alarm means firing twice — once on time and once on the reschedule.
        val next = nextAlarmTrigger(wednesdayMorning, 7, 0, AlarmDays.EVERY_DAY)
        assertEquals(LocalDateTime.of(2026, 9, 3, 7, 0), next)
    }

    @Test
    fun `a weekday alarm on friday evening skips to monday`() {
        val fridayEvening = LocalDateTime.of(2026, 9, 4, 20, 0)
        val next = nextAlarmTrigger(fridayEvening, 5, 0, AlarmDays.WEEKDAYS)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 9, 7, 5, 0), next)
    }

    @Test
    fun `a once-weekly alarm on its own day rolls a full week`() {
        // Wednesday 07:00, asking for Wednesday 05:00 — already gone today.
        val wednesdayOnly = 1 shl 2
        val next = nextAlarmTrigger(wednesdayMorning, 5, 0, wednesdayOnly)
        assertEquals(LocalDateTime.of(2026, 9, 9, 5, 0), next)
    }

    @Test
    fun `a once-weekly alarm later the same day stays today`() {
        val wednesdayOnly = 1 shl 2
        val next = nextAlarmTrigger(wednesdayMorning, 22, 30, wednesdayOnly)
        assertEquals(LocalDateTime.of(2026, 9, 2, 22, 30), next)
    }

    @Test
    fun `every day of the week is reachable`() {
        DayOfWeek.entries.forEach { day ->
            val mask = 1 shl (day.value - 1)
            val next = nextAlarmTrigger(wednesdayMorning, 6, 0, mask)
            assertEquals(day, next.dayOfWeek)
            assertTrue(next.isAfter(wednesdayMorning))
            // Never more than a week out, whichever day is asked for.
            assertTrue(next.isBefore(wednesdayMorning.plusDays(8)))
        }
    }
}
