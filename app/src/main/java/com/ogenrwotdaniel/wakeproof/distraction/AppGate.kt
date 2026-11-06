package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony

/**
 * Social, video, messaging and feed apps — and browsers, while
 * [WallRule.GATE_BROWSERS] is on — are walled by [AppBlockService] outside the
 * Monday–Thursday 08:10–08:50 open window and all day Friday through Sunday.
 *
 * Inside that 40-minute window on Monday–Thursday they open directly with no
 * countdown wait and no separate 25-minute budget (2026-11-06, user's choice:
 * only 40 minutes exist in the window anyway). Outside it they are held
 * OS-suspended via Device Owner and covered by [BlockedActivity].
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
     * Gated via [BlockedActivity] / [PopupCover] / [WindowNotificationGuard],
     * but never OS-suspended: suspending [PLAY_STORE] stalls app updates, and
     * suspending [GOOGLE_APP] breaks [GEMINI_APP] (`BardShell` trampolines into
     * `.voice.robin.*` inside [GOOGLE_APP]) as well as system voice services.
     */
    val NEVER_SUSPEND = setOf(PLAY_STORE, GOOGLE_APP)

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
     * The default SMS app is never gated, even if it declares itself social: it
     * carries one-time sign-in codes and bank confirmations that expire in
     * minutes, and nobody scrolls an SMS inbox for fun. It is still behind the
     * focus wall during a session; the code itself shows in the notification.
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
        packageName != defaultSmsPackage && (
            packageName in KNOWN_GATED_PACKAGES ||
                category == ApplicationInfo.CATEGORY_SOCIAL ||
                category == ApplicationInfo.CATEGORY_VIDEO ||
                (gateBrowsers && isBrowser)
            )

    /** Whether [packageName] is gated, never true for an essential app. */
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

/** What [AppBlockService] does about the app on screen. */
enum class GateVerdict {
    ALLOW, FOCUS_WALL, CAP_WALL, WINDOW_WALL, BEDTIME_WALL, EARN_WALL,
    /** The night's sleep wall over every non-essential app; see [sleepWallActive]. */
    SLEEP_WALL
}

/**
 * The single decision, in priority order:
 *
 * 1. Essential apps (phone, settings, home, WakeProof itself) are never touched.
 * 2. The night's sleep wall over every non-essential app (except focus-list apps
 *    during an active session).
 * 3. During a focus session, anything not on the focus list is walled — the
 *    whole session, breaks included, across all of the user's devices.
 * 4. An app whose daily cap is spent is walled until midnight (or next open window).
 * 5. Shut outside the Monday–Thursday open window or all day Friday–Sunday
 *    ([GateVerdict.WINDOW_WALL]). The caller scopes [outsideWindow] to gated apps.
 * 6. For gated apps inside the window: bedtime wall, then earn-the-feed; otherwise
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
    sleeping: Boolean = false
): GateVerdict = when {
    essential -> GateVerdict.ALLOW
    // Every app, gated or not; a focus session into the night keeps its list.
    sleeping && !(focusSessionActive && onFocusList) -> GateVerdict.SLEEP_WALL
    focusSessionActive && !onFocusList -> GateVerdict.FOCUS_WALL
    capSpent -> GateVerdict.CAP_WALL
    outsideWindow -> GateVerdict.WINDOW_WALL
    !gated -> GateVerdict.ALLOW
    bedtime -> GateVerdict.BEDTIME_WALL
    !feedEarned -> GateVerdict.EARN_WALL
    else -> GateVerdict.ALLOW
}

/**
 * Pure: whether a gated or capped app is held suspended
 * (`AppBlockService.syncSuspensions`). [forced] holds whatever the verdict: an
 * app in its first week ([NewAppQuarantine]), or every gated app while
 * notification history is on.
 */
fun holdSuspended(neverSuspend: Boolean, verdict: GateVerdict, forced: Boolean = false): Boolean =
    !neverSuspend && (forced || verdict != GateVerdict.ALLOW)
