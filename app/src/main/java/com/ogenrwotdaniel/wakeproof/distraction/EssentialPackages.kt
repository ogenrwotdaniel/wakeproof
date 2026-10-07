package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/**
 * Apps nothing in WakeProof may ever stand in front of: WakeProof itself, the
 * system UI, Settings, the default dialer and the home screen.
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
