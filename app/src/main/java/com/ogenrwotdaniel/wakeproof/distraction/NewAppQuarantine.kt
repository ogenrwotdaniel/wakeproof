package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context

/**
 * On the sideloaded build ([ENFORCED]), a NEWLY INSTALLED app is walled for its first [DAYS] days
 * (2026-11-04, from the bypass audit). The gated lists name known apps and
 * categories, and an app that leaves its category unset — Bluesky, NewPipe, a
 * Reddit client, a browser not on the list — was open the moment it was
 * installed. A week is the same price every other loosening pays.
 *
 * Blocking installs outright was not an option: the restriction that does it
 * also refuses `adb install`, which is how WakeProof's own updates arrive.
 *
 * The first run records every app already on the phone as known (stamp 0), so
 * nothing the user has today is caught. An update is not an install; an
 * uninstall followed by a reinstall is, deliberately ([forget], and the install
 * time). Stamps are only written and read on a clock that can be vouched for,
 * so the week cannot be moved by hand.
 */
object NewAppQuarantine {
    const val DAYS = 7
    const val MILLIS = DAYS * 24 * 60 * 60 * 1000L

    /**
     * Not on the Play build: a stranger's new bank or authenticator app walled
     * for a week, notifications included, with no way to lift it, is the user's
     * own bargain to make, not one to spring on anyone else.
     */
    val ENFORCED = SIDELOADED_BUILD

    private const val PREFS = "new_app_quarantine"
    private const val KEY_SEEDED = "seeded"
    private const val KEY_FIRST_SEEN = "first_seen"
    private const val KEY_HELD = "held"
    private const val KEY_INSTALL_TIMES = "install_times"

    /** Package → when [forget] ran ([android.os.SystemClock.elapsedRealtime]); see [observe]. */
    private val forgottenAt = mutableMapOf<String, Long>()

    /** A new app's stamp until a clock can vouch for one; read as still held. */
    const val PENDING = Long.MAX_VALUE

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun firstSeen(context: Context): Map<String, Long> =
        decodeFirstSeen(prefs(context).getStringSet(KEY_FIRST_SEEN, emptySet()).orEmpty())

    private fun installTimes(context: Context): Map<String, Long> =
        decodeFirstSeen(prefs(context).getStringSet(KEY_INSTALL_TIMES, emptySet()).orEmpty())

    /**
     * Records [installed] apps not seen before: as known on the very first call,
     * as new afterwards.
     *
     * On a clock that is not [proven] a new app is held as [PENDING] (still in
     * quarantine) and given its real stamp once a clock can vouch for one: a
     * stamp from a clock set back a week would free it at once, and no stamp
     * at all would leave it open offline.
     *
     * With [firstInstalledAt] given, a reinstall is told by its install time
     * changing from the one recorded at first sight. Comparing it with the clock
     * instead would make a hand-set clock either free or re-hold every app. An
     * app's absence from the launcher list proves nothing: a disabled app
     * vanishes from it too, and must not start a new week.
     */
    fun observe(
        context: Context,
        installed: Set<String>,
        proven: Boolean,
        firstInstalledAt: ((String) -> Long?)? = null,
        snapshotAt: Long = Long.MAX_VALUE,
        now: Long = System.currentTimeMillis()
    ) = synchronized(this) {
        // Uninstalled after [installed] was read: recording it now would bring it
        // back with no install time, and a reinstall would skip its week.
        @Suppress("NAME_SHADOWING")
        val installed = installed.filterNot { (forgottenAt[it] ?: Long.MIN_VALUE) >= snapshotAt }.toSet()
        val prefs = prefs(context)
        val known = firstSeen(context)
        val recorded = installTimes(context)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)
        val current = firstInstalledAt?.let { lookup ->
            installed.associateWith(lookup).filterValues { it != null }.mapValues { it.value!! }
        }.orEmpty()
        val back = if (seeded) reinstalledPackages(known.keys intersect installed, recorded, current) else emptySet()
        val settled = if (proven) known.mapValues { (_, at) -> if (at == PENDING) now else at } else known
        val fresh = (installed - settled.keys) + back
        val stamp = if (!seeded) 0L else if (proven) now else PENDING
        val next = settled + fresh.associateWith { stamp }
        // First sightings, and the new install time of a reinstall.
        val times = recorded + current.filterKeys { it !in recorded || it in back }
        if (next == known && times == recorded && seeded) return@synchronized
        prefs.edit()
            .putStringSet(KEY_FIRST_SEEN, encodeFirstSeen(next))
            .putStringSet(KEY_INSTALL_TIMES, encodeFirstSeen(times))
            .putBoolean(KEY_SEEDED, true)
            .commit()
    }

    /** [packageName] was uninstalled: installing it again starts a new week. */
    fun forget(context: Context, packageName: String) = synchronized(this) {
        forgottenAt[packageName] = android.os.SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putStringSet(KEY_FIRST_SEEN, encodeFirstSeen(firstSeen(context) - packageName))
            .putStringSet(KEY_INSTALL_TIMES, encodeFirstSeen(installTimes(context) - packageName))
            .commit()
    }

    /**
     * Apps still inside their first week. On a clock that is not [proven] no week
     * ends: those held at the last proven reading stay held, across a restart too,
     * or a reboot in airplane mode would free them.
     */
    fun quarantined(
        context: Context,
        proven: Boolean,
        now: Long = System.currentTimeMillis()
    ): Set<String> = synchronized(this) {
        val held = quarantinedPackages(firstSeen(context), now)
        if (!proven) return@synchronized held + prefs(context).getStringSet(KEY_HELD, emptySet()).orEmpty()
        if (held != prefs(context).getStringSet(KEY_HELD, emptySet())) {
            prefs(context).edit().putStringSet(KEY_HELD, held).commit()
        }
        held
    }
}

/**
 * Pure: the apps first seen less than [NewAppQuarantine.MILLIS] before [now].
 * Stamp 0 = there before WakeProof looked. A stamp in the FUTURE (a clock
 * wound back) still counts as quarantined, so winding the clock cannot
 * shorten the week.
 */
fun quarantinedPackages(firstSeen: Map<String, Long>, now: Long): Set<String> =
    firstSeen.filterValues { it > 0L && (it > now || now - it < NewAppQuarantine.MILLIS) }.keys

/** Install times within this of a recording are the same install. */
const val REINSTALL_MARGIN_MILLIS = 60_000L

/**
 * Pure: apps whose install time ([current]) has moved from the one [recorded]
 * at first sight, i.e. uninstalled and installed again. An app with no recorded
 * time is never one: it is recorded now instead.
 */
fun reinstalledPackages(
    packages: Set<String>,
    recorded: Map<String, Long>,
    current: Map<String, Long>
): Set<String> = packages.filter { pkg ->
    val was = recorded[pkg] ?: return@filter false
    val now = current[pkg] ?: return@filter false
    kotlin.math.abs(now - was) > REINSTALL_MARGIN_MILLIS
}.toSet()

internal fun encodeFirstSeen(map: Map<String, Long>): Set<String> =
    map.map { (pkg, at) -> "$pkg|$at" }.toSet()

/** Unreadable entries are dropped, i.e. the app counts as new on its next sighting. */
internal fun decodeFirstSeen(entries: Set<String>): Map<String, Long> =
    entries.mapNotNull { entry ->
        val pkg = entry.substringBefore('|', "")
        val at = entry.substringAfter('|', "").toLongOrNull()
        if (pkg.isEmpty() || at == null) null else pkg to at
    }.toMap()
