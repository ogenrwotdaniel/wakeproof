package com.ogenrwotdaniel.wakeproof.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.ogenrwotdaniel.wakeproof.distraction.SIDELOADED_BUILD

/**
 * WakeProof as a plain device admin (2026-11-08, the user's choice), so
 * Android refuses to uninstall it until the admin is switched off. Not device
 * owner: that broke most Samsung services and was removed on 2026-11-07. This
 * asks for no policies at all; being an active admin is the whole point.
 *
 * Switching the admin off goes through Settings' `DeviceAdminAdd` screen
 * (from App info's Uninstall, from Device admin apps, or from the package
 * installer's "manage admins"), and the wall service covers that screen
 * while the lock is on ([coversDeactivation]). Still open, on purpose or
 * because nothing else can close them: adb, Safe Mode (WakeProof does not
 * run there), revoking usage access or the overlay permission (the cover
 * goes; the greyed-out Uninstall stays), and a factory reset. Nor beside an
 * essential app such as the dialer in split screen: no wall lands next to a
 * call ([AppBlockService] `mayLandOver`).
 *
 * Sideloaded build only ([AVAILABLE]): the receiver is declared in the debug
 * manifest alone, because Play restricts device-admin use.
 */
object UninstallLock {
    val AVAILABLE = SIDELOADED_BUILD

    /** Settings' activity both for turning an admin on and for turning it off. */
    private const val DEACTIVATE_SCREEN = "DeviceAdminAdd"

    fun component(context: Context): ComponentName = ComponentName(context, WakeProofAdmin::class.java)

    fun isActive(context: Context): Boolean = AVAILABLE && runCatching {
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))
    }.getOrDefault(false)

    /** Releases active-admin status from inside our own UID so `adb shell cmd package uninstall -k` succeeds. */
    fun releaseAdmin(context: Context) {
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)
                ?.removeActiveAdmin(component(context))
        }
    }

    /** The system's own "activate this admin" screen. */
    fun enableIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Euler's Lab asks for no device policies. While it is an active admin, " +
                    "Android will not uninstall it."
            )

    /**
     * Pure: whether the screen in front is where the lock would be switched off:
     * [lockOn], and one of [frontClasses] is Settings' `DeviceAdminAdd`. The same
     * screen also activates other apps' admins; covering it for them too is the
     * price, as Usage Access names the activity but not whose admin it shows.
     * Never while the lock is off, or WakeProof's own activation would be walled.
     */
    fun coversDeactivation(lockOn: Boolean, frontPackage: String, frontClasses: Collection<String>): Boolean =
        lockOn && frontPackage == SETTINGS_PACKAGE &&
            // Ends-with: One UI names it SecDeviceAdminAdd (seen on the Tab S9 FE+, Android 16).
            frontClasses.any { it.substringAfterLast('.').endsWith(DEACTIVATE_SCREEN) }

    const val SETTINGS_PACKAGE = "com.android.settings"

    private const val PREFS = "uninstall_lock"
    // Time since boot plus the boot count, never the wall clock: Settings is not
    // walled, so a clock set back to just after the stamp would reopen the grace
    // and let the off screen through unwalled.
    private const val KEY_ENABLED_ELAPSED = "enabled_elapsed"
    private const val KEY_ENABLED_BOOT = "enabled_boot"
    // Long enough for the activation screen to close, short enough that it is
    // no window for switching the lock straight back off.
    private const val ENABLE_GRACE_MILLIS = 3_000L

    private fun bootCount(context: Context): Int =
        runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)

    /** Called by [WakeProofAdmin.onEnabled]. */
    fun markEnabled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ENABLED_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_ENABLED_BOOT, bootCount(context))
            .apply()
    }

    /**
     * Within [ENABLE_GRACE_MILLIS] of turning the lock on, when the activation
     * screen is still closing and must not be met with "Uninstall lock is on".
     */
    fun justEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return withinEnableGrace(
            enabledElapsed = prefs.getLong(KEY_ENABLED_ELAPSED, -1L),
            enabledBoot = prefs.getInt(KEY_ENABLED_BOOT, -1),
            nowElapsed = SystemClock.elapsedRealtime(),
            nowBoot = bootCount(context)
        )
    }

    /**
     * Pure: the lock went on less than [ENABLE_GRACE_MILLIS] ago in this boot.
     * An unknown boot count never counts, nor does a stamp from another boot.
     */
    fun withinEnableGrace(enabledElapsed: Long, enabledBoot: Int, nowElapsed: Long, nowBoot: Int): Boolean =
        enabledBoot >= 0 && enabledBoot == nowBoot && enabledElapsed >= 0 &&
            enabledElapsed in (nowElapsed - ENABLE_GRACE_MILLIS + 1)..nowElapsed
}

/** The admin component itself; it holds no policies. */
class WakeProofAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = UninstallLock.markEnabled(context)

    /** Shown on Android's own confirm step, should one ever get past the cover. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off lets Euler's Lab be uninstalled, and every wall with it."
}
