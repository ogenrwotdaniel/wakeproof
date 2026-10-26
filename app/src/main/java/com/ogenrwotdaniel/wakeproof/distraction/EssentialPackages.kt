package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/**
 * Apps nothing in WakeProof may ever stand in front of: WakeProof itself, the
 * system UI, Settings, the default dialer (with its in-call screen) and the
 * home screen.
 *
 * Shared by two features with the same failure mode. A hard app block with no
 * override would otherwise brick the phone, including the path to turning the
 * feature off; and the focus tree would otherwise wither for answering a phone
 * call or glancing at the home screen.
 */
fun essentialPackages(context: Context): Set<String> = buildSet {
    add(context.packageName)
    add("com.android.systemui")
    add("com.android.settings")
    // The in-call screen is its own package on most phones, separate from the
    // dialer. With nuclear focus walling every app not on the focus list, a
    // missing entry here would put a wall over an incoming call.
    addAll(IN_CALL_PACKAGES)
    // Runtime-permission dialogs, including ones WakeProof itself raises.
    add("com.google.android.permissioncontroller")
    add("com.android.permissioncontroller")
    runCatching {
        context.getSystemService(TelecomManager::class.java).defaultDialerPackage
    }.getOrNull()?.let(::add)
    runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }.getOrNull()?.let(::add)
}

/** In-call screens: Samsung, AOSP, and Google's dialer, which hosts its own. */
private val IN_CALL_PACKAGES = setOf(
    "com.samsung.android.incallui",
    "com.android.incallui",
    "com.google.android.dialer",
    "com.android.server.telecom"
)
