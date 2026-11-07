package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * How far the wall clock may sit from network time. Generous on purpose: a false
 * alarm shuts every ordinary app, and five minutes moves no wall that matters.
 */
const val CLOCK_TOLERANCE_MILLIS = 5 * 60_000L

/** Both the time and the zone come from the network; either moves the local clock. */
fun clockAutomatic(context: Context): Boolean = runCatching {
    // Android's own default is on; an unset value must not read as a hand-set
    // clock, which shuts every ordinary app.
    Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1 &&
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1
}.getOrDefault(true)

/** The last network time carried forward on the monotonic clock; null when none was ever heard. */
private fun networkNowMillis(): Long? =
    runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull()

/** Pure: an unknown network time is not a disagreement. */
fun clockDisagrees(wallMillis: Long, networkMillis: Long?): Boolean =
    networkMillis != null && kotlin.math.abs(wallMillis - networkMillis) > CLOCK_TOLERANCE_MILLIS

/**
 * Pure. The automatic flag alone proves nothing: in airplane mode the clock can
 * be set by hand and the flag turned back on, and nothing corrects it.
 */
fun clockUntrusted(automatic: Boolean, wallMillis: Long, networkMillis: Long?): Boolean =
    !automatic || clockDisagrees(wallMillis, networkMillis)

/** Pure: stricter than trust, because no network time has been heard to compare against. */
fun clockProven(automatic: Boolean, wallMillis: Long, networkMillis: Long?): Boolean =
    networkMillis != null && !clockUntrusted(automatic, wallMillis, networkMillis)

/**
 * Whether to stop believing the local clock, which is what shuts every ordinary
 * app. An offline phone that has heard no network time is believed: an offline
 * reboot must not lock the phone.
 */
fun clockUntrusted(context: Context): Boolean =
    clockUntrusted(clockAutomatic(context), System.currentTimeMillis(), networkNowMillis())

/**
 * Whether the local clock may be counted on for a week's wait. Loosenings are
 * refused and held back without this: stamping one from a clock set back a week
 * would let it land at once.
 */
fun clockProven(context: Context): Boolean =
    clockProven(clockAutomatic(context), System.currentTimeMillis(), networkNowMillis())

/** What asking for a change to a limit or a wall rule came to. */
enum class ChangeRequest {
    APPLIED,

    /** Loosening: waits out the week. */
    QUEUED,

    /** Loosening asked for on a clock [clockProven] does not vouch for. */
    REFUSED_CLOCK
}

/** Shown when a loosening or leaving sync is turned down for [ChangeRequest.REFUSED_CLOCK]. */
const val CLOCK_REFUSAL_MESSAGE =
    "Turn on automatic date, time and time zone, and connect to the internet, first."
