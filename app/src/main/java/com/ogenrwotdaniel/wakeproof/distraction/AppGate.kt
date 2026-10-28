package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony

/**
 * Social, video and messaging apps — and browsers, while
 * [WallRule.GATE_BROWSERS] is on — open only after a wait of at least
 * [GATE_WAIT_SECONDS], however they are opened.
 *
 * The wait used to live on the WakeProof home screen only, so it guarded one
 * door out of many: the Edge panel, Recents, a notification or a share sheet
 * all opened the app at once, and the Edge panel in particular let the user
 * peek at a feed during the very pause that was meant to talk them out of it.
 * The wait is now enforced by [AppBlockService], which watches what is on
 * screen no matter how it got there, and the home screen simply opens the same
 * [GateActivity].
 */
object AppGate {

    /**
     * Five minutes: long enough that the urge rarely survives it. The first
     * wait of the day; later ones grow (see [gateWaitSeconds]).
     */
    const val GATE_WAIT_SECONDS = 5 * 60

    /**
     * How long a fresh pass may go unused while the app is still launching. Past
     * it, a pass that never reached the screen (the launch failed, or the user
     * went elsewhere first) is dropped.
     */
    const val PASS_LAUNCH_MILLIS = 10_000L

    /**
     * WhatsApp Business: the user's main line of communication, so it never
     * waits (2026-10-28) even though it would otherwise match as messaging or
     * by a social category. Still behind the focus wall during a session unless
     * it is put on the focus list. Plain WhatsApp still waits.
     */
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /** Never wait, whatever their category says. See [WHATSAPP_BUSINESS]. */
    val EXEMPT_PACKAGES = setOf(WHATSAPP_BUSINESS)

    /**
     * Screens that sit over an app for a moment without the user having left
     * it: the shade, permission prompts, the share sheet, the system file and
     * photo pickers an "attach" button opens. Seeing one of these in front does
     * not end a pass. Anything else does — see [GatePasses.track].
     */
    val PASS_TRANSIENT_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.google.android.photopicker"
    )

    /**
     * Named outright because an app's declared category is optional and many
     * leave it unset. Messaging apps are on it by the user's choice: a reply
     * that can wait five minutes is how a feed gets opened "just to answer".
     * SMS apps are NOT — see [isGated].
     */
    val KNOWN_GATED_PACKAGES = setOf(
        // Social
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana",
        "com.facebook.lite", "com.twitter.android", "com.snapchat.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.reddit.frontpage",
        "com.pinterest", "com.linkedin.android", "com.tumblr", "com.bereal.ft",
        // Video
        "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
        // Messaging
        "com.whatsapp", "com.facebook.orca", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.discord", "com.kakao.talk",
        "jp.naver.line.android", "com.tencent.mm", "com.viber.voip"
    )

    /**
     * Browsers, named outright for the same reason as [KNOWN_GATED_PACKAGES];
     * [browserPackages] adds whatever else on the phone opens a plain web link.
     */
    val KNOWN_BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser",
        "org.mozilla.firefox", "com.brave.browser", "com.opera.browser",
        "com.opera.mini.native", "com.microsoft.emmx", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "com.naver.whale",
        "com.UCMobile.intl"
    )

    /**
     * Every app that opens an ordinary web page. An app that only handles its
     * own site's links (YouTube for youtube.com) does not answer for
     * example.com, so this finds browsers rather than every app with a link.
     */
    fun browserPackages(context: Context): Set<String> = KNOWN_BROWSERS + runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }
    }.getOrDefault(emptyList())

    /**
     * Pure rule, so it can be tested without a PackageManager.
     *
     * The default SMS app never waits, even if it declares itself social. It
     * carries one-time sign-in codes and bank confirmations that expire in
     * minutes, and nobody scrolls an SMS inbox for fun. (It waited at first,
     * by the user's choice, and was exempted on review, 2026-10-27.) It is
     * still behind the focus wall during a session; the code itself shows in
     * the notification.
     *
     * Browsers wait when [gateBrowsers] (2026-10-28, user's choice: exactly like
     * social apps). Otherwise instagram.com and youtube.com in a browser skipped
     * every wall the apps themselves are behind.
     */
    fun isGated(
        packageName: String,
        category: Int,
        defaultSmsPackage: String?,
        isBrowser: Boolean = false,
        gateBrowsers: Boolean = false
    ): Boolean =
        packageName != defaultSmsPackage && packageName !in EXEMPT_PACKAGES && (
            packageName in KNOWN_GATED_PACKAGES ||
                category == ApplicationInfo.CATEGORY_SOCIAL ||
                category == ApplicationInfo.CATEGORY_VIDEO ||
                (gateBrowsers && isBrowser)
            )

    /** Whether [packageName] waits, never true for an essential app. */
    fun isGated(
        context: Context,
        packageName: String,
        essentials: Set<String>,
        browsers: Set<String>,
        gateBrowsers: Boolean
    ): Boolean {
        if (packageName in essentials) return false
        val category = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).category
        }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return isGated(packageName, category, sms, packageName in browsers, gateBrowsers)
    }
}

/**
 * Passes earned by sitting out the wait, one per app. Process-wide on purpose:
 * [GateActivity] grants them and [AppBlockService] reads them, and both run in
 * the app's process. Losing them to process death only means waiting again,
 * which is the strict direction.
 *
 * A pass is good for ONE visit (2026-10-28, user's request). Closing the app,
 * switching to another, going home or turning the screen off ends it, and the
 * next visit sits through the whole five minutes again. It used to survive a
 * minute off screen, which made "just check one more thing" free.
 */
object GatePasses {
    private val passes = mutableMapOf<String, GatePass>()

    @Synchronized
    fun grant(packageName: String, now: Long) {
        passes[packageName] = GatePass(grantedAt = now, used = false)
    }

    /**
     * Called every tick with what is in front. Marks a pass used once its app
     * is seen and drops every pass whose visit is over.
     */
    @Synchronized
    fun track(foreground: String, now: Long) {
        val entries = passes.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            val next = passAfter(entry.value, entry.key, foreground, now)
            if (next == null) entries.remove() else entry.setValue(next)
        }
    }

    /** The screen went off or locked: every visit is over. */
    @Synchronized
    fun revokeAll() = passes.clear()

    @Synchronized
    fun isValid(packageName: String): Boolean = packageName in passes

    /**
     * Pure: what is left of [pass] (for [passPackage]) once [foreground] is
     * seen in front. Null means the visit is over.
     *
     * - Its own app in front: the visit is on, and from now on leaving ends it.
     * - A [AppGate.PASS_TRANSIENT_PACKAGES] screen: a moment over the app, not
     *   a departure.
     * - Anything else before the app was ever seen: still launching (WakeProof's
     *   own wait screen is in front then), allowed for [AppGate.PASS_LAUNCH_MILLIS].
     * - Anything else after: the user left.
     */
    fun passAfter(pass: GatePass, passPackage: String, foreground: String, now: Long): GatePass? =
        when {
            foreground == passPackage -> pass.copy(used = true)
            foreground in AppGate.PASS_TRANSIENT_PACKAGES -> pass
            !pass.used && now - pass.grantedAt <= AppGate.PASS_LAUNCH_MILLIS -> pass
            else -> null
        }
}

/** One earned visit to a waiting app; [used] once the app reached the screen. */
data class GatePass(val grantedAt: Long, val used: Boolean)

/** What [AppBlockService] does about the app on screen. */
enum class GateVerdict {
    ALLOW, FOCUS_WALL, CAP_WALL, WINDOW_WALL, BEDTIME_WALL, EARN_WALL, BUDGET_WALL, WAIT
}

/**
 * The single decision, in priority order:
 *
 * 1. Essential apps (phone, settings, home, WakeProof itself) are never touched.
 * 2. During a focus session, anything not on the focus list is walled — the
 *    whole session, breaks included, by the user's choice. A session running
 *    on another of the user's devices counts too.
 * 3. An app whose daily cap is spent is walled until midnight.
 * 4. Everything else here is about the waiting apps only: shut outside the
 *    open window (14:00–17:00 by default), then the bedtime wall, then the feed not yet earned by a focus block, then the
 *    shared daily budget spent, and finally the wait itself. A pass earned at
 *    the wait does not get past any of the walls before it.
 */
fun gateVerdict(
    essential: Boolean,
    focusSessionActive: Boolean,
    onFocusList: Boolean,
    capSpent: Boolean,
    gated: Boolean,
    hasPass: Boolean,
    outsideWindow: Boolean = false,
    bedtime: Boolean = false,
    feedEarned: Boolean = true,
    budgetSpent: Boolean = false
): GateVerdict = when {
    essential -> GateVerdict.ALLOW
    focusSessionActive && !onFocusList -> GateVerdict.FOCUS_WALL
    capSpent -> GateVerdict.CAP_WALL
    !gated -> GateVerdict.ALLOW
    outsideWindow -> GateVerdict.WINDOW_WALL
    bedtime -> GateVerdict.BEDTIME_WALL
    !feedEarned -> GateVerdict.EARN_WALL
    budgetSpent -> GateVerdict.BUDGET_WALL
    !hasPass -> GateVerdict.WAIT
    else -> GateVerdict.ALLOW
}
