package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusSoundIdTest {

    @Test
    fun `bundled loops resolve by id`() {
        FocusSound.entries.forEach {
            assertEquals(it, FocusSound.fromId(it.id))
        }
    }

    @Test
    fun `retired or unknown ids fall back to ambient`() {
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId("spotify"))
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId("library"))
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId("nonsense"))
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId(null))
    }

    @Test
    fun `removed external sound ids are migrated to ambient`() {
        assertEquals(FocusSound.AMBIENT.id, FocusSound.normalizedId("spotify"))
        assertEquals(FocusSound.AMBIENT.id, FocusSound.normalizedId("library"))
    }
}
