package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightRulesCameraTest {

    private val now = 100 * CAMERA_NAG_EVERY_MILLIS

    @Test
    fun `warns hourly while a locked alarm has no camera`() {
        assertTrue(cameraNagDue(strictHeld = true, cameraGranted = false, lastNagAt = 0L, now = now))
        assertFalse(cameraNagDue(true, false, lastNagAt = now - CAMERA_NAG_EVERY_MILLIS + 1, now = now))
        assertTrue(cameraNagDue(true, false, lastNagAt = now - CAMERA_NAG_EVERY_MILLIS, now = now))
    }

    @Test
    fun `says nothing with the camera, or with no locked alarm here`() {
        assertFalse(cameraNagDue(strictHeld = true, cameraGranted = true, lastNagAt = 0L, now = now))
        assertFalse(cameraNagDue(strictHeld = false, cameraGranted = false, lastNagAt = 0L, now = now))
    }

    @Test
    fun `the lost-grant alert returns on its schedule and a wound-back clock cannot silence it`() {
        val every = wallsDownAlertEvery(sideloaded = true)
        assertTrue(every < wallsDownAlertEvery(sideloaded = false))
        assertFalse(nagDue(lastAt = now - every + 1, now = now, everyMillis = every))
        assertTrue(nagDue(lastAt = now - every, now = now, everyMillis = every))
        assertTrue(nagDue(lastAt = now + 1, now = now, everyMillis = every))
    }

    @Test
    fun `a clock wound back cannot silence it`() {
        assertTrue(cameraNagDue(true, false, lastNagAt = now + 1, now = now))
    }

    @Test
    fun `the camera warning is quiet from the sleep wall until the last locked alarm`() {
        val lastSlot = 5 * 60 + 30
        assertTrue(nightQuiet(minuteOfDay = 20 * 60, lastSlotMinutes = lastSlot))
        assertTrue(nightQuiet(minuteOfDay = 2 * 60, lastSlotMinutes = lastSlot))
        assertTrue(nightQuiet(minuteOfDay = 4 * 60, lastSlotMinutes = lastSlot))
        assertFalse(nightQuiet(minuteOfDay = lastSlot, lastSlotMinutes = lastSlot))
        assertFalse(nightQuiet(minuteOfDay = 12 * 60, lastSlotMinutes = lastSlot))
    }

    @Test
    fun `only a repeat of the walls-down alert waits out the night`() {
        assertTrue(wallsDownRepeatHeld(treeLost = false, lastAlertAt = 5L, quiet = true))
        assertFalse(wallsDownRepeatHeld(treeLost = false, lastAlertAt = 0L, quiet = true))
        assertFalse(wallsDownRepeatHeld(treeLost = true, lastAlertAt = 5L, quiet = true))
        assertFalse(wallsDownRepeatHeld(treeLost = false, lastAlertAt = 5L, quiet = false))
    }
}
