package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.Telephony

/**
 * Waiting apps installed in another profile on this phone — Secure Folder,
 * Dual Messenger, a work profile. Usage events are per profile, so WakeProof
 * cannot see those copies at all: a second Instagram in Secure Folder has no
 * wait, no cap and no focus wall. Nothing here can enforce on them; the Usage
 * screen names them so the user can remove them.
 *
 * Best effort: a profile the system does not expose to this app (Samsung keeps
 * Secure Folder to itself on some builds) is simply not listed.
 */
fun gatedAppsInOtherProfiles(context: Context, gateBrowsers: Boolean): List<String> = runCatching {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val me = Process.myUserHandle()
    val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
    launcherApps.profiles
        .filter { it != me }
        .flatMap { user -> launcherApps.getActivityList(null, user) }
        .filter { activity ->
            val info = activity.applicationInfo
            AppGate.isGated(
                packageName = info.packageName,
                category = info.category,
                defaultSmsPackage = sms,
                isBrowser = info.packageName in AppGate.KNOWN_BROWSERS,
                gateBrowsers = gateBrowsers
            )
        }
        .map { it.label.toString() }
        .distinct()
        .sorted()
}.getOrDefault(emptyList())
