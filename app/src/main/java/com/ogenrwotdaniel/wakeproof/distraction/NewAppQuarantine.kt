package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context

/**
 * A NEWLY INSTALLED app is held suspended for its first [DAYS] days
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
 * uninstall followed by a reinstall is, deliberately.
 */
object NewAppQuarantine {
    const val DAYS = 7
    const val MILLIS = DAYS * 24 * 60 * 60 * 1000L

    private const val PREFS = "new_app_quarantine"
    private const val KEY_SEEDED = "seeded"
    private const val KEY_FIRST_SEEN = "first_seen"

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun firstSeen(context: Context): Map<String, Long> =
        decodeFirstSeen(prefs(context).getStringSet(KEY_FIRST_SEEN, emptySet()).orEmpty())

    /**
     * Records [installed] apps not seen before: as known on the very first
     * call, as new (stamped [now]) afterwards. True when anything new appeared.
     */
    fun observe(context: Context, installed: Set<String>, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)
        val known = firstSeen(context)
        val fresh = installed - known.keys
        if (seeded && fresh.isEmpty()) return false
        val stamp = if (seeded) now else 0L
        prefs.edit()
            .putStringSet(KEY_FIRST_SEEN, encodeFirstSeen(known + fresh.associateWith { stamp }))
            .putBoolean(KEY_SEEDED, true)
            .commit()
        return seeded
    }

    /** Apps still inside their first week. */
    fun quarantined(context: Context, now: Long = System.currentTimeMillis()): Set<String> =
        quarantinedPackages(firstSeen(context), now)
}

/**
 * Pure: the apps first seen less than [NewAppQuarantine.MILLIS] before [now].
 * Stamp 0 = there before WakeProof looked. A stamp in the FUTURE (a clock
 * wound back) still counts as quarantined, so winding the clock cannot
 * shorten the week.
 */
fun quarantinedPackages(firstSeen: Map<String, Long>, now: Long): Set<String> =
    firstSeen.filterValues { it > 0L && (it > now || now - it < NewAppQuarantine.MILLIS) }.keys

internal fun encodeFirstSeen(map: Map<String, Long>): Set<String> =
    map.map { (pkg, at) -> "$pkg|$at" }.toSet()

/** Unreadable entries are dropped, i.e. the app counts as new on its next sighting. */
internal fun decodeFirstSeen(entries: Set<String>): Map<String, Long> =
    entries.mapNotNull { entry ->
        val pkg = entry.substringBefore('|', "")
        val at = entry.substringAfter('|', "").toLongOrNull()
        if (pkg.isEmpty() || at == null) null else pkg to at
    }.toMap()
