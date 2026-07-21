package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Minimal launcher screen for the watch app. When a Pomodoro is running on the
 * phone it shows the live countdown (self-ticking from the pushed finish time,
 * so it stays smooth with no per-second sync); otherwise it shows the same
 * progress snapshot as the tile. Kept dependency-light (plain [Activity] + a
 * [TextView]) — the Tile is the primary surface.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private var renderJob: Job? = null
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0F1417"))
            textSize = 16f
            setPadding(24, 24, 24, 24)
            text = getString(R.string.app_name)
        }
        setContentView(textView)

        // Needed so the timer Ongoing Activity can be posted on the watch face.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        renderJob?.cancel()
        renderJob = scope.launch {
            val timer = TimerSnapshot.read(applicationContext)
            if (timer != null) {
                renderTimer(timer)
            } else {
                renderStats()
            }
        }
    }

    override fun onPause() {
        renderJob?.cancel()
        super.onPause()
    }

    /** Shows the live Pomodoro countdown, ticking every second while running. */
    private suspend fun renderTimer(timer: TimerSnapshot) {
        val phase = timer.phase.ifBlank { "Focus" }
        if (!timer.running) {
            val minutes = timer.remainingSeconds / 60
            val seconds = timer.remainingSeconds % 60
            textView.text = "$phase\n\n%d:%02d\n\n(paused)".format(minutes, seconds)
            return
        }
        // Self-tick from the absolute finish time so the display is smooth even
        // though the phone only pushes on transitions.
        while (coroutineContext.isActive) {
            val remaining = ((timer.finishAtMillis - System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(0L)
            val minutes = remaining / 60
            val seconds = remaining % 60
            textView.text = "$phase\n\n%d:%02d".format(minutes, seconds)
            if (remaining <= 0L) break
            delay(1000L - SystemClock.elapsedRealtime() % 1000L)
        }
    }

    private suspend fun renderStats() {
        val s = WearStatsReader.read(applicationContext)
        textView.text = if (!s.present) {
            "No data yet.\nOpen WakeProof on your phone."
        } else {
            val star = if (s.asterisk) "*" else ""
            buildString {
                append("🔥 ${s.streak}$star-day streak\n\n")
                append("Focus ${s.focusMinutes}/${s.focusGoal} min\n")
                append("💧 ${s.waterCount}/${s.waterTarget}  (${s.waterMl} ml)")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
