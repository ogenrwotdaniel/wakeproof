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
        assertEquals(6, fixed.hour)
        assertEquals(5, fixed.minute)
        assertEquals(StrictAlarm.TYPE, fixed.type)
        assertTrue(fixed.enabled)
        assertFalse(fixed.deleted)
        assertEquals(StrictAlarm.DAYS_MASK, fixed.daysMask)
        assertEquals(99L, fixed.updatedAt)
        assertTrue(fixed.pending)
    }

    @Test
    fun `a locked alarm already as it must be is left alone`() {
        assertNull(strictAlarmFixed(alarm(1, hour = 4, minute = 0), StrictAlarm.SLOTS[0], now = 99L))
    }

    @Test
    fun `a locked alarm from the old 03 00 slot moves to 04 00 with its label, a custom label stays`() {
        val moved = strictAlarmFixed(alarm(1, hour = 3, minute = 0, label = "Locked 3:00"), StrictAlarm.SLOTS[0], 99L)!!
        assertEquals(4, moved.hour)
        assertEquals(0, moved.minute)
        assertEquals("Locked 4:00", moved.label)
        val custom = strictAlarmFixed(alarm(2, hour = 5, minute = 30, label = "Up!"), StrictAlarm.SLOTS[1], 99L)!!
        assertEquals("Up!", custom.label)
        assertEquals("Locked 6:05", strictLabel(StrictAlarm.SLOTS[1]))
    }

    @Test
    fun `the earliest live pushup alarm is adopted`() {
        val chosen = strictAlarmCandidate(
            listOf(
                alarm(1, hour = 4, deleted = true),
                alarm(2, type = AlarmType.MATH, hour = 1),
                alarm(3, hour = 6),
                alarm(4, hour = 5, minute = 30)
            )
        )
        assertEquals(4L, chosen?.id)
    }

    @Test
    fun `a slot never adopts another kind of alarm, nor one already locked`() {
        assertNull(strictAlarmCandidate(listOf(alarm(2, type = AlarmType.MATH, hour = 8))))
        assertNull(strictAlarmCandidate(listOf(alarm(1, enabled = false))))
        assertEquals(
            3L,
            strictAlarmCandidate(listOf(alarm(1, hour = 3), alarm(3, hour = 6)), taken = setOf("s1"))?.id
        )
    }

    @Test
    fun `an unlocked pushup alarm on a locked slot is a duplicate, nothing else is`() {
        val rows = listOf(
            alarm(1, hour = 4, minute = 0), alarm(2, hour = 4, minute = 0), alarm(3, hour = 6, minute = 5),
            alarm(4, hour = 4, minute = 0, type = AlarmType.MATH), alarm(5, hour = 6, minute = 0),
            alarm(6, hour = 4, minute = 0, deleted = true),
            // The old locked pair, synced back from before the move, never rings either.
            alarm(7, hour = 3, minute = 0), alarm(8, hour = 5, minute = 30)
        )
        assertEquals(listOf(2L, 3L, 7L, 8L), strictAlarmDuplicates(rows, setOf("s1")).map { it.id })
        assertEquals(emptyList<Long>(), strictAlarmDuplicates(rows, emptySet()).map { it.id })
    }

    @Test
    fun `the two morning alarms are 04 00 and 06 05, at 10 pushups`() {
        assertEquals(listOf(StrictAlarm.Slot(4, 0), StrictAlarm.Slot(6, 5)), StrictAlarm.SLOTS)
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
