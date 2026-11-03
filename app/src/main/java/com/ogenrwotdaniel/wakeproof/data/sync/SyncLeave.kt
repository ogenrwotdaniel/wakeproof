package com.ogenrwotdaniel.wakeproof.data.sync

import android.content.Context

/**
 * Leaving cloud sync — signing out, or switching sync off — waits a week
 * (2026-11-04, from the bypass audit). A device that leaves sync stops hearing
 * of the user's focus sessions on their other devices, so the tablet could be
 * freed from a session running on the phone with two taps. Refused outright
 * while a session runs anywhere; otherwise the first ask starts the week and
 * an ask after it goes through. Device-protected storage, like the uninstall
 * lock, so a reboot neither loses nor resets the request.
 */
object SyncLeave {
    const val DAYS = 7
    const val MILLIS = DAYS * 24 * 60 * 60 * 1000L

    private const val PREFS = "sync_leave"
    private const val KEY_REQUESTED_AT = "requested_at"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun requestedAt(context: Context): Long? =
        prefs(context).getLong(KEY_REQUESTED_AT, 0L).takeIf { it > 0L }

    /** Starts the week unless it is already running. */
    fun request(context: Context, now: Long = System.currentTimeMillis()) {
        if (requestedAt(context) != null) return
        prefs(context).edit().putLong(KEY_REQUESTED_AT, now).commit()
    }

    /** Used up by a sign-out or sync-off that went through. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_REQUESTED_AT).commit()
    }
}

/**
 * Pure: whether leaving sync may go through at [now]. A request stamped in
 * the future (a clock wound back) is not ready, so the clock cannot shorten
 * the week.
 */
fun syncLeaveReady(requestedAt: Long?, now: Long): Boolean =
    requestedAt != null && requestedAt <= now && now - requestedAt >= SyncLeave.MILLIS
