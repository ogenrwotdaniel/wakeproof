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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.ogenrwotdaniel.wakeproof.distraction.SLEEP_END_MINUTES
import com.ogenrwotdaniel.wakeproof.distraction.SIDELOADED_BUILD
import com.ogenrwotdaniel.wakeproof.distraction.clockUntrusted
import com.ogenrwotdaniel.wakeproof.distraction.sleepWallActive
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import java.time.LocalDate
import java.time.LocalTime

/**
 * Blackboard inside WakeProof, locked to [STUDY_HOST_SUFFIXES]. Any other link is
 * handed to the phone (so it lands in Chrome, under Chrome's hours) instead of
 * opening here, which is what keeps this screen open when Chrome is not.
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
    private val handler = Handler(Looper.getMainLooper())

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingUpload?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingUpload = null
    }

    private val sleepCheck = object : Runnable {
        override fun run() {
            when {
                web != null && asleep() -> showSleep()
                // On the sleep screen: the page comes back by itself at 04:00, or once the clock is fixed.
                web == null && !asleep() -> recreate()
            }
            if (web != null || asleep()) handler.postDelayed(this, SLEEP_CHECK_MILLIS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContentView(view)
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
        handler.post(sleepCheck)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(sleepCheck)
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
        pendingUpload?.onReceiveValue(null)
        pendingUpload = null
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /**
     * Shut for the night, or on a clock that cannot be believed: the night is read
     * off that clock, and the clock wall that covers every other app never lands on
     * WakeProof's own screens.
     */
    private fun asleep(): Boolean =
        clockDoubted() || LocalTime.now().let { sleepWallActive(it.hour * 60 + it.minute) }

    /** As the clock wall: the sideloaded build only, where it covers every app. */
    private fun clockDoubted(): Boolean = SIDELOADED_BUILD && clockUntrusted(this)

    /** The night's wall, kept here because no wall lands on WakeProof's own screens. */
    private fun showSleep() {
        // The page's time so far, before it goes: onPause skips a screen with no page.
        if (web != null) {
            StudyBrowserTime.add(this, LocalDate.now().toEpochDay(), SystemClock.elapsedRealtime() - resumedAt)
            resumedAt = SystemClock.elapsedRealtime()
        }
        web?.destroy()
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
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(text, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })
    }

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
