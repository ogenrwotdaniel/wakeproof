package com.ogenrwotdaniel.wakeproof.distraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ResumedActivities.newestClass], which the uninstall lock's wall reads. */
class NewestClassTest {

    private val settings = "com.android.settings"
    private val adminOff = "com.samsung.android.settings.applications.specialaccess.SecDeviceAdminAdd"
    private val wifi = "com.android.settings.wifi.WifiSettings"

    @Test
    fun `a screen whose PAUSED was lost stops being the newest once another opens`() {
        val state = ResumedActivities()
        state.onResumed(settings, adminOff, at = 1_000L)
        assertEquals(adminOff, state.newestClass(settings))
        // No PAUSED for adminOff: it stays in the set but is no longer the newest.
        state.onResumed(settings, wifi, at = 2_000L)
        assertEquals(wifi, state.newestClass(settings))
    }

    @Test
    fun `leaving the newest falls back to what is still resumed, and nothing to null`() {
        val state = ResumedActivities()
        state.onResumed(settings, wifi, at = 1_000L)
        state.onResumed(settings, adminOff, at = 2_000L)
        state.onLeft(settings, adminOff)
        assertEquals(wifi, state.newestClass(settings))
        state.onLeft(settings, wifi)
        assertNull(state.newestClass(settings))
    }
}
