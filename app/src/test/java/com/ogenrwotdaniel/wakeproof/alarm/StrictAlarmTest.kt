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
        hour: Int = 5,
        minute: Int = 30,
        enabled: Boolean = true,
        deleted: Boolean = false,
        daysMask: Int = 127
    ) = Alarm(
        id = id, type = type, hour = hour, minute = minute, enabled = enabled,
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
        assertEquals(30, fixed.minute)
        assertEquals(StrictAlarm.TYPE, fixed.type)
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
            alarm(1, hour = 3, minute = 0), alarm(2, hour = 3, minute = 0), alarm(3, hour = 5, minute = 30),
            alarm(4, hour = 3, minute = 0, type = AlarmType.MATH), alarm(5, hour = 6),
            alarm(6, hour = 3, minute = 0, deleted = true)
        )
        assertEquals(listOf(2L, 3L), strictAlarmDuplicates(rows, setOf("s1")).map { it.id })
        assertEquals(emptyList<Long>(), strictAlarmDuplicates(rows, emptySet()).map { it.id })
    }

    @Test
    fun `the two morning alarms are 03 00 and 05 30, at 10 pushups`() {
        assertEquals(listOf(StrictAlarm.Slot(3, 0), StrictAlarm.Slot(5, 30)), StrictAlarm.SLOTS)
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
