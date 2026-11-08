package com.ogenrwotdaniel.wakeproof.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallLockTest {

    private val settings = UninstallLock.SETTINGS_PACKAGE
    private val deactivate = "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminAdd"

    @Test
    fun `the admin off switch is covered while the lock is on`() {
        assertTrue(UninstallLock.coversDeactivation(true, settings, setOf(deactivate)))
        assertTrue(UninstallLock.coversDeactivation(true, settings, setOf("com.android.settings.DeviceAdminAdd")))
        assertTrue(
            UninstallLock.coversDeactivation(
                true,
                settings,
                setOf("com.samsung.android.settings.applications.specialaccess.SecDeviceAdminAdd")
            )
        )
    }

    @Test
    fun `nothing is covered while the lock is off, so it can be turned on`() {
        assertFalse(UninstallLock.coversDeactivation(false, settings, setOf(deactivate)))
    }

    @Test
    fun `the rest of Settings, and other apps' screens of the same name, are left alone`() {
        assertFalse(UninstallLock.coversDeactivation(true, settings, setOf("com.android.settings.Settings")))
        assertFalse(UninstallLock.coversDeactivation(true, settings, setOf("com.android.settings.DeviceAdminSettings")))
        assertFalse(UninstallLock.coversDeactivation(true, "com.example", setOf("com.example.DeviceAdminAdd")))
        assertFalse(UninstallLock.coversDeactivation(true, settings, emptySet()))
    }

    @Test
    fun `the off switch is walled ahead of everything, essential Settings included`() {
        val verdict = com.ogenrwotdaniel.wakeproof.distraction.gateVerdict(
            essential = true,
            focusSessionActive = false,
            onFocusList = false,
            capSpent = false,
            gated = false,
            adminOffSwitch = true
        )
        org.junit.Assert.assertEquals(com.ogenrwotdaniel.wakeproof.distraction.GateVerdict.ADMIN_LOCK_WALL, verdict)
    }

    @Test
    fun `the activation just made is left alone for a few seconds, in the same boot only`() {
        fun grace(at: Long, now: Long, boot: Int = 7, nowBoot: Int = 7) =
            UninstallLock.withinEnableGrace(at, boot, now, nowBoot)
        assertTrue(grace(at = 1_000_000L, now = 1_002_000L))
        assertFalse(grace(at = 1_000_000L, now = 1_003_000L))
        assertFalse(grace(at = -1L, now = 1_005_000L))
        assertFalse(grace(at = 2_000_000L, now = 1_005_000L))
        // A stamp from an earlier boot, or no boot count to compare, never reopens it.
        assertFalse(grace(at = 1_000L, now = 2_000L, boot = 6))
        assertFalse(grace(at = 1_000L, now = 2_000L, boot = -1, nowBoot = -1))
    }
}
