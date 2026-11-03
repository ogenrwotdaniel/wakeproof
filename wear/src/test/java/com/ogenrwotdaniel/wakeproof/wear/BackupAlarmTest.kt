package com.ogenrwotdaniel.wakeproof.wear

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAlarmTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, s, 0, seoul)
    private val slots = listOf(3 * 60, 5 * 60 + 30)

    @Test
    fun `the next check is the soonest locked time`() {
        assertEquals(at(5, 3, 0).toInstant().toEpochMilli(), nextSlotAt(slots, at(4, 22, 0)))
        assertEquals(at(5, 5, 30).toInstant().toEpochMilli(), nextSlotAt(slots, at(5, 4, 0)))
        assertEquals(at(6, 3, 0).toInstant().toEpochMilli(), nextSlotAt(slots, at(5, 6, 0)))
    }

    @Test
    fun `a slot just passed is still checked, one whose grace is over is not`() {
        assertEquals(at(5, 3, 0).toInstant().toEpochMilli(), nextSlotAt(slots, at(5, 3, 1)))
        assertEquals(at(5, 5, 30).toInstant().toEpochMilli(), nextSlotAt(slots, at(5, 3, 2, 1)))
    }

    @Test
    fun `no locked times means nothing to arm`() {
        assertNull(nextSlotAt(emptyList(), at(4, 22, 0)))
    }

    @Test
    fun `the watch rings only when the phone did not`() {
        val slot = at(5, 3, 0).toInstant().toEpochMilli()
        assertTrue(phoneMissedSlot(slot, lastPhoneRingAt = 0L))
        assertTrue(phoneMissedSlot(slot, lastPhoneRingAt = slot - 24 * 3_600_000L))
        assertFalse(phoneMissedSlot(slot, lastPhoneRingAt = slot + 5_000L))
        assertFalse(phoneMissedSlot(slot, lastPhoneRingAt = slot - 30_000L))
        assertFalse(phoneMissedSlot(0L, 0L))
    }
}
