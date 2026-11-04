package com.ogenrwotdaniel.wakeproof.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.ogenrwotdaniel.wakeproof.launcher.LauncherActivity
import android.util.Log

/**
 * WakeProof as DEVICE OWNER (2026-11-01, the user's request): it cannot be
 * uninstalled, and Settings will not force-stop it or clear its data, short
 * of a factory reset.
 *
 * Set once over adb on a phone with no accounts:
 * `adb shell dpm set-device-owner com.ogenrwotdaniel.wakeproof/.admin.WakeProofAdmin`.
 * The receiver is declared in the DEBUG manifest only: Play restricts apps
 * that use device-admin powers to block their own removal, and the sideloaded
 * build is the one this is for.
 *
 * The one way out short of a reset is a release that waits 90 days
 * ([RELEASE_DELAY_MILLIS]) after it is asked for (24 hours until 2026-11-03,
 * when the user asked for three months: "I want just to recover my time") —
 * long enough that it is never the answer to an impulse. Asking again restarts nothing; cancelling and asking again does.
 * The request lives in device-protected storage so a reboot neither loses
 * nor resets it.
 */
object DeviceLock {
    private const val TAG = "DeviceLock"
    private const val PREFS = "device_lock"
    private const val KEY_REQUESTED_AT = "release_requested_at"

    const val RELEASE_DELAY_DAYS = 90
    const val RELEASE_DELAY_MILLIS = RELEASE_DELAY_DAYS * 24 * 60 * 60 * 1000L

    fun admin(context: Context) = ComponentName(context, WakeProofAdmin::class.java)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /**
     * The phone-wide restrictions, by the user's choice (2026-11-04, after an
     * audit of the routes around the walls; they green-lit everything but
     * blocking USB debugging, which is how updates are installed):
     *  - safe mode, where WakeProof does not run but the browsers do;
     *  - the clock: automatic time and time zone are forced on and the user
     *    cannot change them, or setting it forward a week would land every
     *    queued loosening and shift every time window;
     *  - new users, guest, work and clone profiles, where none of this applies;
     *  - default apps, so the home screen stays WakeProof's and no chat app
     *    becomes the never-walled SMS app;
     *  - factory reset from Settings (recovery mode cannot be blocked).
     * All of it goes with device owner when [release] gives that up.
     */
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_ADD_MANAGED_PROFILE,
        // UserManager.DISALLOW_ADD_CLONE_PROFILE is @hide; Dual Messenger-style clones.
        "no_add_clone_profile",
        UserManager.DISALLOW_CONFIG_DEFAULT_APPS,
        UserManager.DISALLOW_FACTORY_RESET
    )

    /**
     * Makes sure uninstalling is blocked and the [RESTRICTIONS] stand.
     * Idempotent; called on enable and every app start. Each step on its own,
     * so one refused does not leave the rest unapplied.
     */
    fun enforce(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        step("block uninstall") { dpm.setUninstallBlocked(admin, context.packageName, true) }
        RESTRICTIONS.forEach { step(it) { dpm.addUserRestriction(admin, it) } }
        // Revoking the camera was a way to open the emergency dismiss on a
        // pushup alarm; the locked alarm has none, so it is held granted.
        step("camera") {
            dpm.setPermissionGrantState(
                admin, context.packageName, android.Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
        // WakeProof Clip's screenshot observer (READ_MEDIA_IMAGES is declared in
        // the debug manifest only, like the admin receiver itself).
        step("read images") {
            dpm.setPermissionGrantState(
                admin, context.packageName, android.Manifest.permission.READ_MEDIA_IMAGES,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
        step("auto time") { dpm.setAutoTimeEnabled(admin, true) }
        step("auto time zone") { dpm.setAutoTimeZoneEnabled(admin, true) }
        step("home") {
            dpm.addPersistentPreferredActivity(
                admin,
                IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
                ComponentName(context, LauncherActivity::class.java)
            )
        }
    }

    private inline fun step(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "could not enforce $what", it) }
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When a release was asked for; null = none pending. */
    fun requestedAt(context: Context): Long? =
        prefs(context).getLong(KEY_REQUESTED_AT, 0L).takeIf { it > 0L }

    /** Starts the [RELEASE_DELAY_DAYS]-day wait, unless one is already running. */
    fun requestRelease(context: Context, now: Long = System.currentTimeMillis()) {
        if (requestedAt(context) != null) return
        prefs(context).edit().putLong(KEY_REQUESTED_AT, now).commit()
    }

    /**
     * Automatic (network) time is on. The release requires it: otherwise
     * setting the clock forward would turn the wait into seconds.
     */
    fun networkTime(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AUTO_TIME) == 1
    }.getOrDefault(false)

    fun cancelRelease(context: Context) {
        prefs(context).edit().remove(KEY_REQUESTED_AT).commit()
    }

    /**
     * Gives up device owner — only once the wait has run out. Afterwards
     * WakeProof is an ordinary app again and can be uninstalled. Returns
     * whether it happened.
     */
    fun release(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (releaseState(requestedAt(context), now) != ReleaseState.READY) return false
        if (!networkTime(context)) return false
        return runCatching {
            val dpm = dpm(context)
            dpm.setUninstallBlocked(admin(context), context.packageName, false)
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(context.packageName)
            cancelRelease(context)
        }.onFailure { Log.w(TAG, "release failed", it) }.isSuccess
    }
}

enum class ReleaseState { NOT_REQUESTED, WAITING, READY }

/**
 * Pure: where a release stands at [now]. A request stamped in the future (a
 * clock wound back after asking) counts as just made, so winding the clock
 * cannot shorten the wait — only lengthen it.
 */
fun releaseState(requestedAt: Long?, now: Long): ReleaseState = when {
    requestedAt == null -> ReleaseState.NOT_REQUESTED
    requestedAt > now -> ReleaseState.WAITING
    now - requestedAt >= DeviceLock.RELEASE_DELAY_MILLIS -> ReleaseState.READY
    else -> ReleaseState.WAITING
}

/** The device-admin component behind [DeviceLock]. */
class WakeProofAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        DeviceLock.enforce(context)
    }
}
