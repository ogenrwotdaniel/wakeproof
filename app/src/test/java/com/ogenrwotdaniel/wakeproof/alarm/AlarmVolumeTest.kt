package com.ogenrwotdaniel.wakeproof.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmVolumeTest {

    @Test
    fun `full until the first rep, then falling with each rep, never silent`() {
        assertEquals(1f, pushupRingVolume(0, 25), 0f)
        assertEquals(PUSHUP_FIRST_REP_VOLUME, pushupRingVolume(1, 25), 1e-6f)
        var last = 1f
        for (rep in 1..25) {
            val v = pushupRingVolume(rep, 25)
            assertTrue("rep $rep", v <= last)
            assertTrue("rep $rep", v >= PUSHUP_MIN_VOLUME)
            last = v
        }
        assertEquals(PUSHUP_MIN_VOLUME, pushupRingVolume(24, 25), 1e-6f)
    }

    @Test
    fun `a counter reset is full volume again`() {
        assertEquals(1f, pushupRingVolume(0, 25), 0f)
    }

    @Test
    fun `a locked alarm holds 70 percent of the stream, never zero`() {
        assertEquals(11, strictStreamIndex(15))
        assertEquals(1, strictStreamIndex(1))
        assertEquals(1, strictStreamIndex(0))
    }
}
