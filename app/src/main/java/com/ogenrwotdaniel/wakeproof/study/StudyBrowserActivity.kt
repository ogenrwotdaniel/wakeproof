package com.ogenrwotdaniel.wakeproof.study

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import com.ogenrwotdaniel.wakeproof.distraction.SLEEP_END_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.SIDELOADED_BUILD
import com.ogenrwotdaniel.wakeproof.distraction.clockUntrusted
import com.ogenrwotdaniel.wakeproof.distraction.sleepWallActive
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import java.time.LocalDate
import java.time.LocalTime

/**
 * Blackboard inside WakeProof, locked to [STUDY_HOST_SUFFIXES]. Any other link is
 * handed to the phone (so it lands in a browser, behind the gated apps' walls)
 * instead of opening here, which is what keeps this screen open when they are not.
 *
 * WakeProof is essential to the block service, so no wall ever lands here; the
 * night's sleep wall is kept by this screen itself, checked every half minute.
 * Uploads (assignments) go through the system file picker; downloads (slides)
 * go to Downloads. Time spent here is kept per day for the weekly report.
 */
class StudyBrowserActivity : ComponentActivity() {

    /** Null once the sleep wall has replaced the page. */
    private var web: WebView? = null
    private var pendingUpload: ValueCallback<Array<Uri>>? = null
    private var resumedAt = 0L
    /** Elapsed realtime since which every sleep check has found the clock doubted; 0 when trusted. */
    private var doubtedSince = 0L

    private fun doubtedFor(): Long = if (doubtedSince > 0) SystemClock.elapsedRealtime() - doubtedSince else 0L
    private val handler = Handler(Looper.getMainLooper())

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingUpload?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingUpload = null
    }

    private val sleepCheck = object : Runnable {
        override fun run() {
            when {
                // Out of sight a clock doubt must last five minutes of checks to close the
                // page: a passing network-time blip must not end a lecture or an upload the
                // user cannot see, but a clock set back must not let one play past the sleep wall's start.
                web != null && (nightNow() || (clockDoubted() && (visible() || doubtedFor() >= DOUBT_OUT_OF_SIGHT_MILLIS))) -> showSleep()
                // On the sleep screen: the page comes back by itself when the wall lifts, or once the clock is fixed.
                // Only while visible (split screen counts): behind other apps, onResume brings it back.
                web == null && !asleep() && visible() -> recreate()
            }
            // Real time, not a count of checks: those run on uptime, which stands still in deep sleep.
            doubtedSince = if (clockDoubted()) doubtedSince.takeIf { it > 0 } ?: SystemClock.elapsedRealtime() else 0L
            // On the sleep screen behind other apps there is nothing left to close, and
            // onResume checks at once on return: no need to wake every 30 s all night.
            if (web != null || (asleep() && visible())) handler.postDelayed(this, SLEEP_CHECK_MILLIS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // For the screen's whole life, not only while it is in front: a lecture left
        // playing behind another app is stopped at the sleep wall by the page closing, and a
        // WebView has no MediaSession for the media guard to pause. Pausing the page
        // on every trip away was tried and dropped: it froze live Collaborate classes.
        handler.post(sleepCheck)
        if (asleep()) {
            showSleep()
            return
        }
        val view = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true // Blackboard Ultra is a script app
            settings.domStorageEnabled = true
            // A new window would be a second, unchecked browser: everything opens here.
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Frames inside a Blackboard page (an embedded lecture video) are left
                    // to the page: handing them off would break the embed and launch Chrome.
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    if (studySiteAllowed(url)) return false
                    handOff(url)
                    return true
                }

                // Every request, before it is sent: shouldOverrideUrlLoading never sees a
                // form post, a back/forward step or a frame inside the page, so this is
                // what keeps a feed out of the one browser no wall lands on. A whole page
                // must be a study site; any part of one (frames included) must come from
                // STUDY_PART_HOSTS. Refused requests get an empty 403: a frame stays blank.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme != "https" && url.scheme != "http") return null // data:, blob: stay with the page
                    val host = url.host ?: return refused()
                    val allowed = if (request.isForMainFrame) studyPageAllowed(url.scheme, host) else studyPartAllowed(host)
                    return if (allowed) null else refused()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    pendingUpload?.onReceiveValue(null)
                    pendingUpload = callback
                    return runCatching { pickFiles.launch(params.createIntent()) }.onFailure {
                        pendingUpload = null
                        callback.onReceiveValue(null)
                    }.isSuccess
                }
            }
            setDownloadListener { url, userAgent, disposition, mimeType, _ -> download(url, userAgent, disposition, mimeType) }
        }
        web = view
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true) // the sign-in hops between hosts
        setContentView(insetFrame(view))
        // A bundle saved from the sleep screen holds no page, so restoring can come back empty.
        if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) {
            view.loadUrl(BLACKBOARD_URL)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val page = web
                if (page != null && page.canGoBack()) page.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.elapsedRealtime()
        // Left on the sleep screen overnight: the morning brings the page back.
        if (web == null && !asleep()) {
            recreate()
            return
        }
        // At once: the loop's 30 s are uptime, which stands still while the phone sleeps,
        // so a page left open at 20:50 would otherwise come back usable at 21:30.
        handler.removeCallbacks(sleepCheck)
        handler.post(sleepCheck)
    }

    override fun onPause() {
        super.onPause()
        // Only time on the page counts; the sleep screen is not Blackboard.
        if (web != null) {
            StudyBrowserTime.add(this, LocalDate.now().toEpochDay(), SystemClock.elapsedRealtime() - resumedAt)
        }
        if (web != null) CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepCheck)
        pendingUpload?.onReceiveValue(null)
        pendingUpload = null
        web?.let { page ->
            (page.parent as? ViewGroup)?.removeView(page)
            page.destroy()
        }
        web = null
        super.onDestroy()
    }

    /**
     * Shut for the night, or on a clock that cannot be believed: the night is read
     * off that clock, and the clock wall that covers every other app never lands on
     * WakeProof's own screens.
     */
    private fun asleep(): Boolean = clockDoubted() || nightNow()

    private fun nightNow(): Boolean = LocalTime.now().let { sleepWallActive(it.hour * 60 + it.minute) }

    /** On screen at all, split screen and pop-up view included. */
    private fun visible(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** As the clock wall: the sideloaded build only, where it covers every app. */
    private fun clockDoubted(): Boolean = SIDELOADED_BUILD && clockUntrusted(this)

    /** The night's wall, kept here because no wall lands on WakeProof's own screens. */
    private fun showSleep() {
        // A file picker open now would hand its file to a destroyed page: cancel it, so
        // the upload fails visibly on return instead of vanishing.
        pendingUpload?.onReceiveValue(null)
        pendingUpload = null
        // The page's time so far, before it goes: onPause skips a screen with no page.
        // Behind another app, onPause has already counted it.
        if (web != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            StudyBrowserTime.add(this, LocalDate.now().toEpochDay(), SystemClock.elapsedRealtime() - resumedAt)
            resumedAt = SystemClock.elapsedRealtime()
        }
        // Detached first: destroying a WebView still on screen can leak or misbehave.
        web?.let { page ->
            (page.parent as? ViewGroup)?.removeView(page)
            page.destroy()
        }
        web = null
        val text = TextView(this).apply {
            text = if (clockDoubted()) {
                "The phone's clock can't be trusted. Turn on automatic date, time and time zone " +
                    "and connect to the internet, then open Blackboard again."
            } else {
                "Time to sleep. Blackboard opens again at " +
                    "${formatAlarmTime(context, SLEEP_END_MINUTES / 60, SLEEP_END_MINUTES % 60)}."
            }
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        setContentView(insetFrame(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(text, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }))
    }

    /**
     * [content] inside a frame padded clear of the status bar, the navigation bar and
     * the keyboard. Android 15 draws every app edge to edge, so without it Blackboard's
     * bottom bar sat under the navigation buttons and could not be tapped.
     */
    private fun insetFrame(content: android.view.View): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { frame, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            frame.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    private fun refused(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Not a study site", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    /** A link off the study sites goes to the phone's browser, under its own walls. */
    private fun handOff(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return // intent:, javascript: and the like
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        Toast.makeText(this, "Only Blackboard and UNIST pages open here", Toast.LENGTH_SHORT).show()
    }

    private fun download(url: String, userAgent: String, disposition: String?, mimeType: String?) {
        if (!url.startsWith("https://")) return // blob: and data: need the page; Blackboard serves files over https
        val name = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .apply { CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) } }
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        runCatching { getSystemService(DownloadManager::class.java).enqueue(request) }
            .onSuccess { Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val SLEEP_CHECK_MILLIS = 30_000L
        /** How long a doubted clock must last before it closes a page out of sight. */
        private const val DOUBT_OUT_OF_SIGHT_MILLIS = 5 * 60_000L

        fun intent(context: Context): Intent =
            Intent(context, StudyBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Minutes spent in [StudyBrowserActivity] per day, for the weekly report. Local only. */
object StudyBrowserTime {
    private const val PREFS = "study_browser_time"
    private const val KEEP_DAYS = 14

    fun add(context: Context, epochDay: Long, millis: Long) {
        if (millis <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putLong(epochDay.toString(), prefs.getLong(epochDay.toString(), 0L) + millis)
        prefs.all.keys.mapNotNull { it.toLongOrNull() }.filter { it < epochDay - KEEP_DAYS }
            .forEach { edit.remove(it.toString()) }
        edit.apply()
    }

    /** Minutes per day over [days], 0 for a day with none. */
    fun minutes(context: Context, days: LongRange): Map<Long, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return days.associateWith { prefs.getLong(it.toString(), 0L) / 60_000 }
    }
}
