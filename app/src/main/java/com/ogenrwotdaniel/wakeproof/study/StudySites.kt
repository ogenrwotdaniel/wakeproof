package com.ogenrwotdaniel.wakeproof.study

import java.net.URI

/**
 * The sites Euler's Lab's own study & research browser ([StudyBrowserActivity]) opens:
 * UNIST's Blackboard, Microsoft SSO, and whitelisted peer-reviewed research repositories
 * (arXiv, Google Scholar, CVF, OpenReview, Nature, APS, ACS, ScienceDirect, IEEE, etc.).
 * It is open whenever the phone is (outside the night's sleep wall), during focus
 * sessions too, with no daily hour, which a general browser could never be: a general
 * browser reaches every feed, whereas this one physically cannot reach social media,
 * video feeds, or general web search.
 */
const val BLACKBOARD_URL = "https://blackboard.unist.ac.kr/ultra/institution-page"
const val ARXIV_MATERIALS_URL = "https://arxiv.org/list/cond-mat.mtrl-sci/recent"
const val ARXIV_CV_URL = "https://arxiv.org/list/cs.CV/recent"
const val ARXIV_DL_URL = "https://arxiv.org/list/cs.LG/recent"
const val SCHOLAR_URL = "https://scholar.google.com/"
const val MATERIALS_PROJECT_URL = "https://next-gen.materialsproject.org/"
const val GRADESCOPE_URL = "https://www.gradescope.com/"

/** Quick-switch tabs shown at the top of [StudyBrowserActivity]. */
val STUDY_QUICK_TABS = listOf(
    "Blackboard" to BLACKBOARD_URL,
    "arXiv: Materials" to ARXIV_MATERIALS_URL,
    "arXiv: CV" to ARXIV_CV_URL,
    "arXiv: DL" to ARXIV_DL_URL,
    "Scholar" to SCHOLAR_URL,
    "Materials Proj." to MATERIALS_PROJECT_URL,
    "Gradescope" to GRADESCOPE_URL
)

/**
 * Host suffixes a page may be on:
 * - UNIST's own domain (Blackboard, the portal and its sign-in), Blackboard's hosting
 *   and Collaborate, and Microsoft's sign-in pages.
 * - Peer-reviewed preprints, citation indices, and journals for Mechanical Engineering,
 *   Deep Learning, Graphene, Computer Vision, and Materials Science.
 * Deliberately NOT `office.com` or `google.com` (only `scholar.google.com` is allowed):
 * general search or portal domains are doors to everything.
 */
val STUDY_HOST_SUFFIXES = listOf(
    "unist.ac.kr",
    "blackboard.com",
    "bbcollab.com",
    "login.microsoftonline.com",
    "login.microsoft.com",
    "login.live.com",
    // Preprints, citations & paper graphs
    "arxiv.org",
    "scholar.google.com",
    "semanticscholar.org",
    "connectedpapers.com",
    "doi.org",
    // Deep Learning & Computer Vision venues & docs
    "openreview.net",
    "thecvf.com",
    "neurips.cc",
    "mlr.press",
    "icml.cc",
    "iclr.cc",
    "pytorch.org",
    // Graphene, 2D Materials, Physics & Mechanical Engineering journals
    "nature.com",
    "aps.org",
    "acs.org",
    "rsc.org",
    "iop.org",
    "sciencedirect.com",
    "elsevier.com",
    "springer.com",
    "wiley.com",
    "science.org",
    "ieee.org",
    "asme.org",
    "aip.org",
    "mrs.org",
    "annualreviews.org",
    "cambridge.org",
    "oup.com",
    "tandfonline.com",
    "mdpi.com",
    "frontiersin.org",
    "overleaf.com",
    // Computational Materials, Graphene & 2D Materials databases, Coursework & DL benchmarks
    "materialsproject.org",
    "nomad-lab.eu",
    "nist.gov",
    "paperswithcode.com",
    "huggingface.co",
    "gradescope.com",
    "turnitin.com"
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
 * what Blackboard Ultra and academic journal/preprint sites load: Blackboard's
 * file store and CDN, the Ally accessibility service, Pendo and New Relic,
 * Microsoft's sign-in assets, MathJax, and journal/Scholar static asset CDNs.
 * A frame from anywhere else, a YouTube embed included, does not load.
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
    "acctcdn.msftauth.net",
    // MathJax, Google Scholar PDFs/assets, and scientific journal static CDNs.
    "scholar.googleusercontent.com",
    "gstatic.com",
    "fonts.googleapis.com",
    "mathjax.org",
    "jsdelivr.net",
    "cdnjs.cloudflare.com",
    "els-cdn.com",
    "springernature.com",
    "static-content.springer.com",
    "silverchair-cdn.com",
    // Korean university LMS lecture video streams (Kollus / Panopto)
    "kollus.com",
    "panopto.com"
)

/** Pure: whether a part of a study page (anything not the whole page) may load from [host]. */
fun studyPartAllowed(host: String): Boolean {
    val h = host.lowercase()
    return studyHostAllowed(h) || underAny(h, STUDY_PART_HOSTS)
}
