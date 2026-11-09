package com.ogenrwotdaniel.wakeproof.study

import java.net.URI

/**
 * The sites WakeProof's own study browser ([StudyBrowserActivity]) opens: UNIST's
 * Blackboard and what signing in to it passes through. It is open whenever the
 * phone is (outside the night's sleep wall), during focus sessions too, with no
 * daily hour, which Chrome could never be: Chrome reaches every site, this one
 * physically cannot reach a feed. Added 2026-11-09 at the user's request, because
 * Blackboard is where coursework lives.
 */
const val BLACKBOARD_URL = "https://blackboard.unist.ac.kr/ultra/institution-page"

/**
 * Host suffixes a page may be on. UNIST's own domain (Blackboard, the portal and
 * its sign-in), Blackboard's hosting and Collaborate (lectures run there), and
 * Microsoft's sign-in pages, which UNIST's single sign-on may pass through.
 * Deliberately not office.com or google.com: either is a door to everything.
 */
val STUDY_HOST_SUFFIXES = listOf(
    "unist.ac.kr",
    "blackboard.com",
    "bbcollab.com",
    "login.microsoftonline.com",
    "login.microsoft.com",
    "login.live.com"
)

/**
 * Pure: whether [url] may open as a page inside the study browser. Only https
 * (and about:blank); a host must equal a suffix or end in
 * ".suffix", so "evilunist.ac.kr" and "unist.ac.kr.example.com" are refused.
 */
fun studySiteAllowed(url: String): Boolean {
    // Not data: or blob: as a whole page: either could carry any site inside it.
    if (url == "about:blank") return true
    // java.net, not android.net: this is tested off the phone.
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return studyHostAllowed(host)
}

/** Pure: [host] is one of [STUDY_HOST_SUFFIXES] or under one. */
fun studyHostAllowed(host: String): Boolean =
    STUDY_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
