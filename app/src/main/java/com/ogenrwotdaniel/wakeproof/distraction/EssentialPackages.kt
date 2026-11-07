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
    val sms: String?,
    /** How long each has read blank, null when it reads; see [blankRun]. */
    val dialerBlankMillis: Long? = null,
    val smsBlankMillis: Long? = null,
    /** When this was read, on the monotonic clock. */
    val readAt: Long = 0L
) {
    /** The SMS app the walls spare; see [AppGate.sparedSms]. */
    val sparedSms: String? get() = AppGate.sparedSms(sms)
}

/**
 * PackageManager, Telecom and Telephony lookups: call it off the main thread.
 *
 * Null when PackageManager fails, so the caller keeps its last good reading. An
 * empty answer is not a failure: a phone can have no system home to fall back on,
 * and a tablet no dialer or SMS app, and those must still start their walls. A
 * hiccup also answers with a plain nothing, though, so [previous] stands in for a
 * minute ([heldOver]), or the dialer would turn into an ordinary app with a wall in
 * front of it.
 */
fun readDefaultApps(context: Context, previous: DefaultApps? = null): DefaultApps? {
    val readHome = runCatching { readHomes(context) }
        .onFailure { android.util.Log.w("DefaultApps", "home app unreadable", it) }
        .getOrNull()
        ?: return null
    val readDialer = defaultDialer(context)
    val readSms = defaultSms(context)
    val now = android.os.SystemClock.elapsedRealtime()
    // A home app stays until another replaces it: without one the home screen is an
    // ordinary app, and a sleep wall lands on it.
    val homes = readHome.ifEmpty { previous?.homes.orEmpty() }
    val dialerBlank = blankRun(readDialer, previous?.dialerBlankMillis, previous?.readAt, now)
    val smsBlank = blankRun(readSms, previous?.smsBlankMillis, previous?.readAt, now)
    val dialer = heldOver(readDialer, previous?.dialer, dialerBlank ?: 0L)
    val sms = heldOver(readSms, previous?.sms, smsBlank ?: 0L)
    return DefaultApps(essentialPackages(context, homes, dialer), homes, dialer, sms, dialerBlank, smsBlank, now)
}

/**
 * Pure: a first reading worth starting the walls on. A phone has a home, a dialer
 * and an SMS app; a blank at boot is a system not ready yet, not a gap.
 */
fun firstReadingReady(apps: DefaultApps, telephony: Boolean): Boolean =
    apps.homes.isNotEmpty() && (!telephony || (apps.dialer != null && apps.sms != null))

/**
 * Pure: how many first readings the walls wait for. Blanks are likely only just
 * after boot. Later, as after a crash or a grant coming back, one retry covers a
 * hiccup, and a phone with a real gap is not left without walls for long.
 */
fun firstReadAttempts(sinceBootMillis: Long): Int =
    if (bootSettling(sinceBootMillis)) BOOT_FIRST_READS else LATER_FIRST_READS

/** Pure: soon enough after boot for a blank reading to be a system still starting. */
fun bootSettling(sinceBootMillis: Long): Boolean = sinceBootMillis < BOOT_SETTLE_MILLIS

/**
 * Pure: how long a value has read blank, from the previous reading's [run] and
 * [lastReadAt]; null when it reads. Only time seen blank counts: reads stop with
 * the screen off, so a gap past [MAX_READ_GAP_MILLIS] adds nothing and two hiccups
 * an hour apart do not use up the hold in [heldOver]. It carries over the gap,
 * though, so a role really cleared still runs out across short looks at the phone.
 */
fun blankRun(read: Any?, run: Long?, lastReadAt: Long?, now: Long): Long? = when {
    read != null -> null
    run == null || lastReadAt == null -> 0L
    now - lastReadAt > MAX_READ_GAP_MILLIS -> run
    else -> run + (now - lastReadAt)
}

/**
 * Pure: [read], or for a while the [last] value a hiccup may have blanked. By the
 * clock, as refreshes come in bursts around installs, when a hiccup is likeliest.
 * Bounded, because a role really cleared answers the same way, and a retired
 * dialer must not stay essential for as long as the service lives.
 */
fun <T : Any> heldOver(read: T?, last: T?, blankForMillis: Long): T? =
    read ?: last?.takeIf { blankForMillis < MAX_HOLD_MILLIS }

const val MAX_HOLD_MILLIS = 60_000L

/** Wider than the notification guard's 60 s sweep, or its blanks would never add up. */
const val MAX_READ_GAP_MILLIS = 2 * MAX_HOLD_MILLIS

private const val BOOT_SETTLE_MILLIS = 3 * 60_000L
private const val BOOT_FIRST_READS = 5
private const val LATER_FIRST_READS = 2

/**
 * The home app. With two installed and no default chosen Android answers with
 * its chooser, so the phone's own home app stands in, or a wall would land on
 * the home screen.
 */
fun homePackages(context: Context): Set<String> =
    runCatching { readHomes(context) }.getOrDefault(emptySet())

/** [homePackages], letting a failed lookup show as one. */
private fun readHomes(context: Context): Set<String> {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val pm = context.packageManager
    val chosen = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (chosen != null && chosen != "android") return setOf(chosen)
    return pm.queryIntentActivities(home, 0)
        .map { it.activityInfo }
        .filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        .map { it.packageName }
        // Settings hosts a fallback HOME activity (AOSP FallbackHome) for the first
        // boot; it is no home screen, and treating it as one would let walls stand
        // beside it and take away its quarantine exemption.
        .toSet() - "android" - "com.android.settings"
}

/** In-call screens: Samsung, AOSP, and Google's dialer, which hosts its own. */
private val IN_CALL_PACKAGES = setOf(
    "com.samsung.android.incallui",
    "com.android.incallui",
    "com.google.android.dialer",
    "com.android.server.telecom"
)
