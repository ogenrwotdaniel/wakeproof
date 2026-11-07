package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.ogenrwotdaniel.wakeproof.BuildConfig

/**
 * Social, video, messaging and feed apps — and browsers, while
 * [WallRule.GATE_BROWSERS] is on — are walled by [AppBlockService] outside the
 * Monday–Thursday 08:10–08:50 open window and all day Friday through Sunday.
 *
 * Inside that 40-minute window on Monday–Thursday they open directly with no
 * countdown wait and no separate 25-minute budget (2026-11-06, user's choice:
 * only 40 minutes exist in the window anyway). Outside it they are
 * covered by [BlockedActivity].
 */
object AppGate {

    /**
     * WhatsApp Business. Off the home screen's permanent pins since 2026-11-03
     * and gated under the same schedule as every other messenger.
     */
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /**
     * The Google app: carries the Discover feed and an in-app search browser
     * (`SilkPaneActivity`) that opened YouTube, Reddit and X links without
     * triggering [browserPackages]. Closed on 2026-11-06; standalone Google
     * Gemini ([GEMINI_APP]) is pinned on the home screen instead (`HomeTools.kt`).
     */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /**
     * Standalone Google Gemini (`BardShell`). On Android 16 its launcher entry
     * trampolines into `.voice.robin.*` activities hosted inside [GOOGLE_APP];
     * [effectivePackage] attributes those Gemini activities back to [GEMINI_APP]
     * while keeping Discover, Search and `SilkPaneActivity` gated as [GOOGLE_APP].
     */
    const val GEMINI_APP = "com.google.android.apps.bard"

    const val PLAY_STORE = "com.android.vending"

    /**
     * Named outright because an app's declared category is optional and many
     * leave it unset. Messaging apps are on it by the user's choice; SMS apps
     * are NOT — see [isGated].
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
        "com.whatsapp", WHATSAPP_BUSINESS, "com.facebook.orca", "org.telegram.messenger",
        "org.telegram.messenger.web", "org.thoughtcrime.securesms", "com.discord",
        "com.kakao.talk", "jp.naver.line.android", "com.tencent.mm", "com.viber.voip",
        // Feeds & portals (including Google Discover / in-app search browser)
        GOOGLE_APP,
        "com.google.android.apps.youtube.music", "com.google.android.apps.youtube.kids",
        "com.samsung.android.app.spage", "com.samsung.android.tvplus",
        "net.daum.android.daum", "com.nhn.android.search",
        // The store itself: a new feed is one install away.
        PLAY_STORE,
        // Alternative clients for the apps above.
        "org.schabi.newpipe", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "xyz.blueskyweb.app", "org.joinmastodon.android", "ml.docilealligator.infinityforreddit"
    )

    /**
     * Walled like any gated app, but never by [NewAppQuarantine]: [GOOGLE_APP]
     * also hosts [GEMINI_APP] (`BardShell` trampolines into `.voice.robin.*`
     * inside it), and a new install must never lock [PLAY_STORE], which
     * updates everything else.
     */
    val QUARANTINE_EXEMPT = setOf(PLAY_STORE, GOOGLE_APP)

    /**
     * Attributes Gemini's `.voice.robin.*` UI (and its brief `tiktok.nav.gateway`
     * trampoline) hosted inside [GOOGLE_APP] to [GEMINI_APP], while leaving
     * Google Discover (`GoogleAppActivity`, `ContentViewerActivity`), Search
     * (`SearchActivity`), and the in-app web viewer (`SilkPaneActivity`) under
     * [GOOGLE_APP] so they remain gated.
     */
    fun effectivePackage(packageName: String, className: String): String =
        if (packageName == GOOGLE_APP &&
            (".voice.robin." in className || "tiktok.nav.gateway." in className)
        ) {
            GEMINI_APP
        } else {
            packageName
        }

    /**
     * Browsers, named outright for the same reason as [KNOWN_GATED_PACKAGES];
     * [browserPackages] adds whatever else on the phone opens a plain web link.
     */
    val KNOWN_BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser",
        "org.mozilla.firefox", "com.brave.browser", "com.opera.browser",
        "com.opera.mini.native", "com.microsoft.emmx", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "com.naver.whale",
        "com.UCMobile.intl", "org.mozilla.focus", "org.mozilla.fenix"
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
     * The default SMS app is not gated, even if it declares itself social: it
     * carries one-time sign-in codes and bank confirmations that expire in
     * minutes, and nobody scrolls an SMS inbox for fun. It is still behind the
     * focus wall during a session; the code itself shows in the notification.
     * A named messenger stays gated: making one the SMS app must not free it.
     *
     * Browsers are gated when [gateBrowsers]: otherwise instagram.com and
     * youtube.com in a browser skip every wall the apps themselves are behind.
     */
    fun isGated(
        packageName: String,
        category: Int,
        defaultSmsPackage: String?,
        isBrowser: Boolean = false,
        gateBrowsers: Boolean = false
    ): Boolean =
        packageName in KNOWN_GATED_PACKAGES || (
            packageName != defaultSmsPackage && (
                category == ApplicationInfo.CATEGORY_SOCIAL ||
                    category == ApplicationInfo.CATEGORY_VIDEO ||
                    (gateBrowsers && isBrowser)
                )
            )

    /** The default SMS app the walls spare; a named messenger is not freed by becoming it. */
    fun sparedSms(defaultSmsPackage: String?): String? =
        defaultSmsPackage?.takeUnless { it in KNOWN_GATED_PACKAGES }

    /**
     * The gated apps among [packages], for lists such as the launcher's and the
     * usage card's. Reads the defaults once for the whole list. Any thread but main.
     */
    fun gatedPackages(context: Context, packages: Collection<String>, gateBrowsers: Boolean): Set<String> {
        val apps = readDefaultApps(context)
        val browsers = browserPackages(context)
        return packages.filter { isGated(context, it, apps.essential, browsers, gateBrowsers, apps.sms) }.toSet()
    }

    /** Whether [packageName] is gated, never true for an essential app. */
    fun isGated(
        context: Context,
        packageName: String,
        essentials: Set<String>,
        browsers: Set<String>,
        gateBrowsers: Boolean,
        // A caller that already keeps the default SMS app passes it: one reading
        // for both its cache and this answer, and no lookup per new package.
        defaultSms: String? = defaultSms(context)
    ): Boolean {
        if (packageName in essentials) return false
        val category = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).category
        }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        return isGated(packageName, category, defaultSms, packageName in browsers, gateBrowsers)
    }
}

/**
 * The build the user sideloads onto their own devices, where the harshest walls
 * are a bargain they made with themselves. Not to be sprung on a Play user.
 */
val SIDELOADED_BUILD = BuildConfig.DEBUG

/** What [AppBlockService] does about the app on screen. */
enum class GateVerdict {
    ALLOW, FOCUS_WALL, CAP_WALL, WINDOW_WALL, BEDTIME_WALL, EARN_WALL,
    /** The night's sleep wall over every non-essential app; see [sleepWallActive]. */
    SLEEP_WALL,
    /** An app in its first week; see [NewAppQuarantine]. */
    QUARANTINE_WALL,
    /** The local clock cannot be believed; see [clockUntrusted]. */
    CLOCK_WALL,
    /** Android keeps the text of the notifications the walls strip. */
    HISTORY_WALL
}

/**
 * The single decision, in priority order:
 *
 * 0. Outside a session, an essential app in its first week ([quarantined]) is
 *    walled: a home or SMS app newly made the default, so choosing a default is
 *    not a way past the walls. Its wall offers the default-apps settings.
 * 1. Otherwise essential apps (phone, settings, home, WakeProof itself) are never touched.
 * 2. Outside a focus session, a clock that cannot be believed shuts every other
 *    app the caller names (all of them on the sideloaded build, gated and capped
 *    ones on Play): all the walls below it read that clock. Inside one it is left
 *    to the walls below, because Settings is walled and the fix could not be made.
 * 3. The night's sleep wall over every non-essential app (except focus-list apps
 *    during an active session).
 * 4. During a focus session, anything not on the focus list is walled — the
 *    whole session, breaks included, across all of the user's devices.
 * 5. An app in its first week ([quarantined]).
 * 6. Outside a session, gated and [capped] apps while Notification History is on.
 * 7. An app whose daily cap is spent is walled until midnight (or next open window).
 * 8. Shut outside the Monday–Thursday open window or all day Friday–Sunday
 *    ([GateVerdict.WINDOW_WALL]). The caller scopes [outsideWindow] to gated apps.
 * 9. For gated apps inside the window: bedtime wall, then earn-the-feed; otherwise
 *    [GateVerdict.ALLOW] directly (no countdown wait, no separate daily budget).
 */
fun gateVerdict(
    essential: Boolean,
    focusSessionActive: Boolean,
    onFocusList: Boolean,
    capSpent: Boolean,
    gated: Boolean,
    outsideWindow: Boolean = false,
    bedtime: Boolean = false,
    feedEarned: Boolean = true,
    sleeping: Boolean = false,
    quarantined: Boolean = false,
    clockUntrusted: Boolean = false,
    historyOn: Boolean = false,
    capped: Boolean = false
): GateVerdict = when {
    // A home or SMS app newly made the default keeps its first week, or choosing
    // a default would be the way past every wall. Its own wall comes first, ahead
    // of clock and sleep, because only it offers the way out (another default).
    // Not during a session: Settings is walled then, and the phone would be left
    // without a home screen for hours.
    essential && quarantined && !focusSessionActive -> GateVerdict.QUARANTINE_WALL
    essential -> GateVerdict.ALLOW
    clockUntrusted && !focusSessionActive -> GateVerdict.CLOCK_WALL
    // Every app, gated or not; a focus session into the night keeps its list.
    sleeping && !(focusSessionActive && onFocusList) -> GateVerdict.SLEEP_WALL
    focusSessionActive && !onFocusList -> GateVerdict.FOCUS_WALL
    quarantined -> GateVerdict.QUARANTINE_WALL
    historyOn && !focusSessionActive && (gated || capped) -> GateVerdict.HISTORY_WALL
    capSpent -> GateVerdict.CAP_WALL
    outsideWindow -> GateVerdict.WINDOW_WALL
    !gated -> GateVerdict.ALLOW
    bedtime -> GateVerdict.BEDTIME_WALL
    !feedEarned -> GateVerdict.EARN_WALL
    else -> GateVerdict.ALLOW
}
