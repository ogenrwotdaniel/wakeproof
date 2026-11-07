package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
fun essentialPackages(
    context: Context,
    homes: Set<String> = homePackages(context),
    dialer: String? = defaultDialer(context)
): Set<String> = buildSet {
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
    dialer?.let(::add)
    addAll(homes)
}

fun defaultDialer(context: Context): String? = runCatching {
    context.getSystemService(TelecomManager::class.java).defaultDialerPackage
}.getOrNull()

fun defaultSms(context: Context): String? =
    runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

/**
 * The default apps the walls depend on, read in one pass, so the block service
 * and the notification guard can never disagree about the same app.
 */
class DefaultApps(
    val essential: Set<String>,
    val homes: Set<String>,
    val dialer: String?,
    /** The default SMS app as read, spared or not; [AppGate.isGated] needs it. */
    val sms: String?
) {
    /** The SMS app the walls spare; see [AppGate.sparedSms]. */
    val sparedSms: String? get() = AppGate.sparedSms(sms)
}

/** PackageManager, Telecom and Telephony lookups: call it off the main thread. */
fun readDefaultApps(context: Context): DefaultApps {
    val homes = homePackages(context)
    val dialer = defaultDialer(context)
    return DefaultApps(essentialPackages(context, homes, dialer), homes, dialer, defaultSms(context))
}

/**
 * The home app. With two installed and no default chosen Android answers with
 * its chooser, so the phone's own home app stands in, or a wall would land on
 * the home screen.
 */
fun homePackages(context: Context): Set<String> = runCatching {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val pm = context.packageManager
    val chosen = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (chosen != null && chosen != "android") return@runCatching setOf(chosen)
    pm.queryIntentActivities(home, 0)
        .map { it.activityInfo }
        .filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        .map { it.packageName }
        // Settings hosts a fallback HOME activity (AOSP FallbackHome) for the first
        // boot; it is no home screen, and treating it as one would let walls stand
        // beside it and take away its quarantine exemption.
        .toSet() - "android" - "com.android.settings"
}.getOrDefault(emptySet())

/** In-call screens: Samsung, AOSP, and Google's dialer, which hosts its own. */
private val IN_CALL_PACKAGES = setOf(
    "com.samsung.android.incallui",
    "com.android.incallui",
    "com.google.android.dialer",
    "com.android.server.telecom"
)
