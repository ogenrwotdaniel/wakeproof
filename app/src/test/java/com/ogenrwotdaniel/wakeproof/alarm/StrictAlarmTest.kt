package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictAlarmTest {

    private fun alarm(
        id: Long,
        type: AlarmType = AlarmType.PUSHUP,
        hour: Int = 6,
        minute: Int = 5,
        enabled: Boolean = true,
        deleted: Boolean = false,
        daysMask: Int = 127,
        label: String = ""
    ) = Alarm(
        id = id, type = type, hour = hour, minute = minute, enabled = enabled, label = label,
        deleted = deleted, daysMask = daysMask, syncId = "s$id", updatedAt = 1L, pending = false
    )

    @Test
    fun `an edited, disabled or deleted locked alarm is written back, restamped`() {
        val slot = StrictAlarm.SLOTS[1]
        val fixed = strictAlarmFixed(
            alarm(1, type = AlarmType.MATH, hour = 7, enabled = false, deleted = true, daysMask = 31),
            slot,
            now = 99L
        )!!
        assertEquals(5, fixed.hour)
        assertEquals(40, fixed.minute)
        assertEquals(AlarmType.PUSHUP, fixed.type)
        assertTrue(fixed.enabled)
        assertFalse(fixed.deleted)
        assertEquals(StrictAlarm.DAYS_MASK, fixed.daysMask)
        assertEquals(99L, fixed.updatedAt)
        assertTrue(fixed.pending)
    }

    @Test
    fun `a locked alarm already as it must be is left alone`() {
        assertNull(strictAlarmFixed(alarm(1, hour = 3, minute = 0), StrictAlarm.SLOTS[0], now = 99L))
    }

    @Test
    fun `a locked alarm from the old 04 00 slot moves to 03 00 with its label, a custom label stays`() {
        val moved = strictAlarmFixed(alarm(1, hour = 4, minute = 0, label = "Locked 4:00"), StrictAlarm.SLOTS[0], 99L)!!
        assertEquals(3, moved.hour)
        assertEquals(0, moved.minute)
        assertEquals("Locked 3:00", moved.label)
        val custom = strictAlarmFixed(alarm(2, hour = 6, minute = 5, label = "Up!"), StrictAlarm.SLOTS[1], 99L)!!
        assertEquals("Up!", custom.label)
        assertEquals("Locked 5:40", strictLabel(StrictAlarm.SLOTS[1]))
    }

    @Test
    fun `the water slot pins a water alarm at 08 20 and adopts only one already there`() {
        val water = StrictAlarm.SLOTS[2]
        assertEquals(StrictAlarm.Slot(8, 20, AlarmType.WATER, hard = false), water)
        val fixed = strictAlarmFixed(alarm(1, type = AlarmType.PUSHUP, hour = 9), water, now = 99L)!!
        assertEquals(AlarmType.WATER, fixed.type)
        assertEquals(8 * 60 + 20, fixed.hour * 60 + fixed.minute)
        val rows = listOf(
            alarm(1, type = AlarmType.WATER, hour = 7), alarm(2, hour = 8, minute = 20),
            alarm(3, type = AlarmType.WATER, hour = 8, minute = 20)
        )
        // The 07:00 water alarm is the user's own and stays where it is.
        assertEquals(3L, strictAlarmCandidate(rows, water)?.id)
        assertNull(strictAlarmCandidate(rows.take(2), water))
    }

    @Test
    fun `only the pushup alarms are hard, and the night runs to the last of them`() {
        assertEquals(listOf(3 * 60, 5 * 60 + 40), StrictAlarm.hardMinutes)
        assertEquals(5 * 60 + 40, StrictAlarm.lastSlotMinute)
        assertEquals(setOf(8 * 60 + 20), StrictAlarm.slotMinutes(AlarmType.WATER))
        assertEquals(emptySet<Int>(), StrictAlarm.silentMinutes(AlarmType.MATH))
    }

    @Test
    fun `the earliest live pushup alarm is adopted`() {
        val chosen = strictAlarmCandidate(
            listOf(
                alarm(1, hour = 4, deleted = true),
                alarm(2, type = AlarmType.MATH, hour = 1),
                alarm(3, hour = 6),
                alarm(4, hour = 5, minute = 30)
            ),
            StrictAlarm.SLOTS[0]
        )
        assertEquals(4L, chosen?.id)
    }

    @Test
    fun `a slot never adopts another kind of alarm, nor one already locked`() {
        assertNull(strictAlarmCandidate(listOf(alarm(2, type = AlarmType.MATH, hour = 8)), StrictAlarm.SLOTS[0]))
        assertNull(strictAlarmCandidate(listOf(alarm(1, enabled = false)), StrictAlarm.SLOTS[0]))
        assertEquals(
            3L,
            strictAlarmCandidate(listOf(alarm(1, hour = 3), alarm(3, hour = 6)), StrictAlarm.SLOTS[0], taken = setOf("s1"))?.id
        )
    }

    @Test
    fun `an unlocked pushup alarm on a locked slot is a duplicate, nothing else is`() {
        val rows = listOf(
            alarm(1, hour = 3, minute = 0), alarm(2, hour = 3, minute = 0), alarm(3, hour = 5, minute = 40),
            alarm(4, hour = 3, minute = 0, type = AlarmType.MATH), alarm(5, hour = 6, minute = 0),
            alarm(6, hour = 3, minute = 0, deleted = true),
            // The old locked pairs, synced back from before a move, never ring either.
            alarm(7, hour = 4, minute = 0), alarm(8, hour = 5, minute = 30), alarm(9, hour = 6, minute = 5),
            // A second water alarm on the fixed minute; a pushup one there is the user's own.
            alarm(10, hour = 8, minute = 20, type = AlarmType.WATER), alarm(11, hour = 8, minute = 20)
        )
        assertEquals(listOf(2L, 3L, 7L, 8L, 9L, 10L), strictAlarmDuplicates(rows, setOf("s1")).map { it.id })
        assertEquals(emptyList<Long>(), strictAlarmDuplicates(rows, emptySet()).map { it.id })
    }

    @Test
    fun `the two morning alarms are 03 00 and 05 40, at 10 pushups`() {
        assertEquals(listOf(StrictAlarm.Slot(3, 0), StrictAlarm.Slot(5, 40)), StrictAlarm.SLOTS.filter { it.hard })
        assertEquals(10, StrictAlarm.REPS)
    }

    @Test
    fun `a cut-off ring resumes within the day, not after it`() {
        val now = 100L * StrictAlarm.RESUME_WITHIN_MILLIS
        assertTrue(shouldResumeRing(5L, now - 60_000L, now))
        assertFalse(shouldResumeRing(5L, now - StrictAlarm.RESUME_WITHIN_MILLIS, now))
        assertFalse(shouldResumeRing(-1L, now, now))
        assertFalse(shouldResumeRing(5L, 0L, now))
    }

    @Test
    fun `a ring stamped in the future still resumes`() {
        assertTrue(shouldResumeRing(5L, 2_000L, 1_000L))
    }
}
