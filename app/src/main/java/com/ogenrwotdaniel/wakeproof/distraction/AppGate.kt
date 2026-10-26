package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Telephony

/**
 * Social, video and messaging apps open only after a [GATE_WAIT_SECONDS] wait,
 * however they are opened.
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

    /** Five minutes: long enough that the urge rarely survives it. */
    const val GATE_WAIT_SECONDS = 5 * 60

    /**
     * How long a pass outlives the app leaving the screen. Covers a glance at a
     * notification or the in-call screen and back; anything longer and the next
     * visit waits again.
     */
    const val PASS_GRACE_MILLIS = 60_000L

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
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.discord", "com.kakao.talk",
        "jp.naver.line.android", "com.tencent.mm", "com.viber.voip"
    )

    /**
     * Pure rule, so it can be tested without a PackageManager.
     *
     * The default SMS app never waits, even if it declares itself social. It
     * carries one-time sign-in codes and bank confirmations that expire in
     * minutes, and nobody scrolls an SMS inbox for fun. (It waited at first,
     * by the user's choice, and was exempted on review, 2026-10-27.) It is
     * still behind the focus wall during a session; the code itself shows in
     * the notification.
     */
    fun isGated(packageName: String, category: Int, defaultSmsPackage: String?): Boolean =
        packageName != defaultSmsPackage && (
            packageName in KNOWN_GATED_PACKAGES ||
                category == ApplicationInfo.CATEGORY_SOCIAL ||
                category == ApplicationInfo.CATEGORY_VIDEO
            )

    /** Whether [packageName] waits, never true for an essential app. */
    fun isGated(context: Context, packageName: String, essentials: Set<String>): Boolean {
        if (packageName in essentials) return false
        val category = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).category
        }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return isGated(packageName, category, sms)
    }
}

/**
 * Passes earned by sitting out the wait, one per app. Process-wide on purpose:
 * [GateActivity] grants them and [AppBlockService] reads them, and both run in
 * the app's process. Losing them to process death only means waiting again,
 * which is the strict direction.
 */
object GatePasses {
    private val lastSeen = mutableMapOf<String, Long>()

    @Synchronized
    fun grant(packageName: String, now: Long) {
        lastSeen[packageName] = now
    }

    /** Called every tick the app is on screen, so the pass lasts while in use. */
    @Synchronized
    fun seen(packageName: String, now: Long) {
        if (packageName in lastSeen) lastSeen[packageName] = now
    }

    @Synchronized
    fun isValid(packageName: String, now: Long): Boolean =
        passStillValid(lastSeen[packageName], now)

    /** Pure: a pass holds until the app has been off screen for the grace. */
    fun passStillValid(lastSeenAt: Long?, now: Long): Boolean =
        lastSeenAt != null && now - lastSeenAt <= AppGate.PASS_GRACE_MILLIS
}

/** What [AppBlockService] does about the app on screen. */
enum class GateVerdict { ALLOW, FOCUS_WALL, CAP_WALL, WAIT }

/**
 * The single decision, in priority order:
 *
 * 1. Essential apps (phone, settings, home, WakeProof itself) are never touched.
 * 2. During a focus session, anything not on the focus list is walled — the
 *    whole session, breaks included, by the user's choice.
 * 3. An app whose daily cap is spent is walled until midnight.
 * 4. A social, video or messaging app waits unless a pass is still valid.
 */
fun gateVerdict(
    essential: Boolean,
    focusSessionActive: Boolean,
    onFocusList: Boolean,
    capSpent: Boolean,
    gated: Boolean,
    hasPass: Boolean
): GateVerdict = when {
    essential -> GateVerdict.ALLOW
    focusSessionActive && !onFocusList -> GateVerdict.FOCUS_WALL
    capSpent -> GateVerdict.CAP_WALL
    gated && !hasPass -> GateVerdict.WAIT
    else -> GateVerdict.ALLOW
}
