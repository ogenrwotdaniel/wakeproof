package com.ogenrwotdaniel.wakeproof.study

import java.net.URI

/**
 * The sites WakeProof's own study browser ([StudyBrowserActivity]) opens: UNIST's
 * Blackboard and what signing in to it passes through. It is open whenever the
 * phone is (outside the night's sleep wall), during focus sessions too, with no
 * daily hour, which a browser could never be: a browser reaches every site, this one
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

/**
 * Pure: [studySiteAllowed] from a scheme and host already parsed (WebView's own
 * parse), for a request whose full URL java.net.URI might refuse (a raw `|` or `{`
 * in a sign-in query), which would leave a real page blank.
 */
fun studyPageAllowed(scheme: String?, host: String?): Boolean =
    scheme == "https" && host != null && studyHostAllowed(host.lowercase())

/** Pure: [host] is one of [STUDY_HOST_SUFFIXES] or under one, and not a proxy. */
fun studyHostAllowed(host: String): Boolean =
    underAny(host, STUDY_HOST_SUFFIXES) && !looksLikeProxy(host)

/**
 * A university proxy or redirector (`*.libproxy.unist.ac.kr`, EZproxy's
 * `www-youtube-com.<proxy>`) would put any site under an allowed suffix. None of
 * the hosts Blackboard was seen using (2026-11-10) has such a label.
 */
private fun looksLikeProxy(host: String): Boolean =
    host.split('.').any { label -> "proxy" in label } ||
        // EZproxy's host-for-host form; on UNIST only, where Blackboard's own regional
        // hosts (several hyphens, on blackboard.com) do not live.
        (underAny(host, listOf("unist.ac.kr")) && host.substringBefore('.').count { it == '-' } >= 2)

private fun underAny(host: String, suffixes: List<String>): Boolean =
    suffixes.any { host == it || host.endsWith(".$it") }

/**
 * Hosts a study page may load parts from (scripts, styles, files, video, its
 * notification socket, and any frame inside it), beyond the page hosts. Taken from
 * what Blackboard Ultra loaded on 2026-11-10 with a sign-in and a lecture: its
 * file store and CDN, the Ally accessibility service, Pendo and New Relic (usage
 * tracking it breaks without), and Microsoft's sign-in assets. A frame from
 * anywhere else, a YouTube embed included, does not load: the part check is what
 * keeps a feed out of the one browser no wall lands on.
 */
val STUDY_PART_HOSTS = listOf(
    "blackboard.com",
    // Course content (attached images, document and PDF previews) on hosted Learn.
    "blackboardcdn.com",
    "ally.ac",
    "dmuwut6e40u5o.cloudfront.net",
    "pendo.io",
    "pendo-static-6207542686449664.storage.googleapis.com",
    "js-agent.newrelic.com",
    "bam.nr-data.net",
    // Microsoft's sign-in page loads these by tenant and region; any one missing
    // leaves the UNIST sign-in blank.
    "aadcdn.msauth.net",
    "aadcdn.msftauth.net",
    "aadcdn.msftauthimages.net",
    "logincdn.msauth.net",
    "logincdn.msftauth.net",
    "acctcdn.msauth.net",
    "acctcdn.msftauth.net"
)

/** Pure: whether a part of a study page (anything not the whole page) may load from [host]. */
fun studyPartAllowed(host: String): Boolean {
    val h = host.lowercase()
    return studyHostAllowed(h) || underAny(h, STUDY_PART_HOSTS)
}
