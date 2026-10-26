package com.ogenrwotdaniel.wakeproof.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusSoundIdTest {

    @Test
    fun `spotify selects no loop`() {
        assertTrue(FocusSound.isSpotify(FocusSound.SPOTIFY_ID))
        assertNull(FocusSound.fromId(FocusSound.SPOTIFY_ID))
    }

    @Test
    fun `the removed own-music id reads as spotify, not as a loop`() {
        // An install that picked "My music" in 1.5 wanted music; falling through
        // to the ambient loop would swap it for rain without saying so.
        assertTrue(FocusSound.isSpotify("library"))
        assertNull(FocusSound.fromId("library"))
    }

    @Test
    fun `bundled loops and unknown ids are not spotify`() {
        FocusSound.entries.forEach {
            assertFalse(FocusSound.isSpotify(it.id))
            assertEquals(it, FocusSound.fromId(it.id))
        }
        assertFalse(FocusSound.isSpotify("nonsense"))
        assertEquals(FocusSound.AMBIENT, FocusSound.fromId("nonsense"))
    }
}
