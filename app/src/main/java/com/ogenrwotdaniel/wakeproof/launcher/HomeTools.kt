package com.ogenrwotdaniel.wakeproof.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telecom.TelecomManager
import com.ogenrwotdaniel.wakeproof.data.AgendaEvent
import com.ogenrwotdaniel.wakeproof.data.InstalledApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Samsung Notes. */
private const val SAMSUNG_NOTES = "com.samsung.android.app.notes"

/** Claude. */
private const val CLAUDE = "com.anthropic.claude"

/**
 * Samsung's Expert RAW camera. The package name is from memory, not read off
 * the device, so [pinnedApps] also finds it by its label.
 */
private const val EXPERT_RAW = "com.samsung.android.app.galaxyraw"
private const val EXPERT_RAW_LABEL = "Expert RAW"

/**
 * The tools that are always on the home screen and cannot be unpinned, in
 * order: the phone, the cameras, notes, Claude, the clock and the calendar.
 * Chosen by the user as things a phone is FOR rather than things it is used
 * to fill time with. Roles (phone, camera, clock, calendar) are resolved from
 * whatever app holds them, so a non-Samsung default still lands here; apps
 * that are not installed are simply left out by the caller.
 */
fun permanentPackages(context: Context): List<String> {
    val pm = context.packageManager
    fun resolve(intent: Intent): String? = runCatching {
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" } // "android" = the chooser, i.e. no default

    val dialer = runCatching {
        context.getSystemService(TelecomManager::class.java).defaultDialerPackage
    }.getOrNull()
    val camera = resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    val clock = resolve(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    val calendar = resolve(
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
    )
    return listOfNotNull(dialer, camera, EXPERT_RAW, SAMSUNG_NOTES, CLAUDE, clock, calendar)
        .filter { it != context.packageName }
        .distinct()
}

/**
 * [permanentPackages] that are installed, in order. Expert RAW is also matched
 * by label, so a wrong or changed package name still pins it.
 */
fun pinnedApps(context: Context, installed: List<InstalledApp>): List<InstalledApp> {
    val byPackage = installed.associateBy { it.packageName }
    val rawByLabel = installed.firstOrNull { it.label.equals(EXPERT_RAW_LABEL, ignoreCase = true) }
    return permanentPackages(context)
        .mapNotNull { pkg -> byPackage[pkg] ?: rawByLabel.takeIf { pkg == EXPERT_RAW } }
        .distinctBy { it.packageName }
}

/**
 * The calendar day [event] falls on. All-day instances are stored as UTC
 * midnights by the calendar provider, so reading them in the local zone puts a
 * Tuesday holiday on Monday evening for anyone west of Greenwich.
 */
fun eventDay(event: AgendaEvent, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(event.beginMillis)
        .atZone(if (event.allDay) ZoneOffset.UTC else zone)
        .toLocalDate()
