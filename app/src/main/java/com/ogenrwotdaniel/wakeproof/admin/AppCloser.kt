package com.ogenrwotdaniel.wakeproof.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Keeps walled apps from being opened at all, by SUSPENDING them as device
 * owner ([DeviceLock]) — 2026-11-04, after the user ran WhatsApp Business in a
 * Samsung pop-up window (long-press in Recents) over its wall.
 *
 * A suspended app is greyed out, dropped from Recents, and refuses to start
 * from anywhere: icon, Recents, pop-up, split screen, link, share sheet. The
 * system also destroys its activities the moment it is suspended, which ends a
 * pop-up or a screen pin on the spot (verified on the S25 Edge).
 *
 * Two kinds of suspension, recorded separately in device-protected storage
 * BEFORE the call, so a process death never loses track of one:
 *  - [hold]/[release]: STANDING, for as long as the app is walled. Decided by
 *    `AppBlockService.syncSuspensions` every tick. Deliberately NOT lifted when
 *    WakeProof restarts — the service re-decides on its first tick — and NOT
 *    when the service stops for a lost grant: revoking usage access must not be
 *    a way to unlock everything.
 *  - [close]: a BRIEF one ([SUSPEND_MILLIS]) for an app WakeProof does not
 *    hold, to tear down a window beside a wall (any app during a focus
 *    session). [releaseStale] lifts leftovers of these on start.
 */
object AppCloser {
    private const val TAG = "AppCloser"
    private const val PREFS = "app_closer"
    private const val KEY_BRIEF = "suspended"
    private const val KEY_HELD = "held"

    /** Long enough for the system to tear the windows down; it takes well under 1 s. */
    const val SUSPEND_MILLIS = 1_500L

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): Set<String> =
        prefs(context).getStringSet(key, emptySet()).orEmpty()

    private fun write(context: Context, key: String, value: Set<String>) {
        prefs(context).edit().putStringSet(key, value).commit()
    }

    /** Whether suspending is possible here at all. */
    fun available(context: Context): Boolean = DeviceLock.isDeviceOwner(context)

    /** Apps held suspended because they are walled. */
    fun held(context: Context): Set<String> = read(context, KEY_HELD)

    /** Suspends [packageName] until [release]. Returns whether it took. */
    fun hold(context: Context, packageName: String): Boolean {
        if (!available(context)) return false
        write(context, KEY_HELD, held(context) + packageName)
        val ok = setSuspended(context, packageName, true)
        if (ok) Log.i(TAG, "holding $packageName") else write(context, KEY_HELD, held(context) - packageName)
        return ok
    }

    /** Lifts a [hold]. Safe to call for an app that is not held. */
    fun release(context: Context, packageName: String) {
        if (packageName !in held(context)) return
        if (setSuspended(context, packageName, false) || !available(context)) {
            write(context, KEY_HELD, held(context) - packageName)
            Log.i(TAG, "released $packageName")
        }
    }

    /**
     * Suspends [packageName] for [SUSPEND_MILLIS], then lifts it. Returns false
     * when it could not be suspended, so the caller falls back to the overlay
     * cover. An app already [hold]-suspended is already closed.
     */
    suspend fun close(context: Context, packageName: String): Boolean {
        if (!available(context)) return false
        if (packageName in held(context)) return true
        write(context, KEY_BRIEF, read(context, KEY_BRIEF) + packageName)
        val ok = setSuspended(context, packageName, true)
        if (ok) {
            Log.i(TAG, "closed $packageName")
            delay(SUSPEND_MILLIS)
        }
        liftBrief(context, packageName)
        return ok
    }

    /** Lifts every BRIEF suspension a previous run left behind (never a hold). */
    fun releaseStale(context: Context) {
        read(context, KEY_BRIEF).forEach { liftBrief(context, it) }
    }

    private fun liftBrief(context: Context, packageName: String) {
        // A hold taken meanwhile wins: the app stays suspended.
        val lifted = packageName in held(context) ||
            setSuspended(context, packageName, false) ||
            // Giving up device owner clears the admin's suspensions with it.
            !available(context)
        if (lifted) write(context, KEY_BRIEF, read(context, KEY_BRIEF) - packageName)
    }

    private fun setSuspended(context: Context, packageName: String, suspended: Boolean): Boolean =
        runCatching {
            dpm(context).setPackagesSuspended(
                DeviceLock.admin(context), arrayOf(packageName), suspended
            ).isEmpty()
        }.onFailure { Log.w(TAG, "could not set $packageName suspended=$suspended", it) }
            .getOrDefault(false)
}
