package com.ogenrwotdaniel.wakeproof.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * The one way out short of a reset is a release that waits 24 hours
 * ([RELEASE_DELAY_MILLIS]) after it is asked for — long enough that it is
 * never the answer to an impulse, the same idea as the week a looser limit
 * waits. Asking again restarts nothing; cancelling and asking again does.
 * The request lives in device-protected storage so a reboot neither loses
 * nor resets it.
 */
object DeviceLock {
    private const val TAG = "DeviceLock"
    private const val PREFS = "device_lock"
    private const val KEY_REQUESTED_AT = "release_requested_at"

    const val RELEASE_DELAY_MILLIS = 24 * 60 * 60 * 1000L

    fun admin(context: Context) = ComponentName(context, WakeProofAdmin::class.java)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** Makes sure uninstalling is blocked. Idempotent; called on enable and every app start. */
    fun enforce(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching { dpm(context).setUninstallBlocked(admin(context), context.packageName, true) }
            .onFailure { Log.w(TAG, "could not block uninstall", it) }
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When a release was asked for; null = none pending. */
    fun requestedAt(context: Context): Long? =
        prefs(context).getLong(KEY_REQUESTED_AT, 0L).takeIf { it > 0L }

    /** Starts the 24-hour wait, unless one is already running. */
    fun requestRelease(context: Context, now: Long = System.currentTimeMillis()) {
        if (requestedAt(context) != null) return
        prefs(context).edit().putLong(KEY_REQUESTED_AT, now).commit()
    }

    /**
     * Automatic (network) time is on. The release requires it: otherwise
     * setting the clock a day forward would be a 24-hour wait taking seconds.
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
