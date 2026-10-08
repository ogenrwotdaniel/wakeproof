package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Launcher screen for the watch app: the live Pomodoro countdown when one is
 * running on the phone (self-ticking from the pushed finish time, so it stays
 * smooth with no per-second sync), otherwise the same progress snapshot as the
 * tile.
 *
 * It also *drives* the timer now. The watch used to be a pure display, which
 * meant starting a focus block required picking up the phone — the exact thing
 * a focus block exists to avoid. Commands go out over [TimerCommands]; the
 * phone remains the only place the timer actually runs, and this screen shows
 * whatever the phone pushes back.
 *
 * The alarm is deliberately not controllable from here. See the note on
 * `WearContract.COMMAND_PATH`: an alarm you can silence from your wrist is one
 * you can silence without getting up, which is the whole thing the app exists
 * to prevent.
 *
 * Kept dependency-light (plain [Activity] and framework views, no Compose) —
 * the Tile is still the primary glanceable surface.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private var renderJob: Job? = null
    private lateinit var textView: TextView
    private lateinit var buttons: LinearLayout

    /**
     * Re-renders when the phone pushes new timer or stats state, so a command
     * sent from here visibly lands rather than needing the screen reopened.
     * Registered only while resumed — a watch screen that listens in the
     * background is a battery cost with nothing to show for it.
     */
    private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
        val relevant = events.any { it.dataItem.uri.path?.startsWith("/wakeproof") == true }
        events.release()
        if (relevant) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            text = getString(R.string.app_name)
        }
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(textView)
            addView(buttons)
        }
        // Round watch faces clip the corners, and the button row can push the
        // content past the bottom on a small face; scrolling is what keeps the
        // last control reachable rather than merely drawn.
        val root = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(root)

        // Needed so the timer Ongoing Activity can be posted on the watch face.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(dataListener)
        render()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(dataListener)
        renderJob?.cancel()
        super.onPause()
    }

    private fun render() {
        renderJob?.cancel()
        renderJob = scope.launch {
            val timer = TimerSnapshot.read(applicationContext)
            showControlsFor(timer)
            if (timer != null) renderTimer(timer) else renderStats()
        }
    }

    /**
     * The controls that make sense for the state the phone reports.
     *
     * Offering only the applicable ones — rather than greying out the rest — is
     * what keeps this legible on a 45 mm screen, where every control costs a
     * meaningful share of the face.
     */
    private fun showControlsFor(timer: TimerSnapshot?) {
        buttons.removeAllViews()
        when {
            timer == null -> addButton("Start focus", TimerCommands.START)
            timer.running -> {
                addButton("Pause", TimerCommands.PAUSE)
                addButton("Stop", TimerCommands.STOP)
            }
            else -> {
                addButton("Resume", TimerCommands.RESUME)
                addButton("Stop", TimerCommands.STOP)
            }
        }
    }

    private fun addButton(label: String, command: String) {
        val button = Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setOnClickListener { send(command, label) }
        }
        buttons.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        )
    }

    /**
     * Fires a command and reports honestly if it did not land.
     *
     * There is no optimistic local state: the phone owns the timer, and the
     * screen changes only when the phone pushes back. A watch that showed
     * "paused" while the phone kept counting would be worse than one that
     * showed nothing.
     */
    private fun send(command: String, label: String) {
        scope.launch {
            setButtonsEnabled(false)
            val delivered = TimerCommands.send(applicationContext, command)
            if (!delivered) {
                textView.text = "Phone unreachable.\nCouldn't $label."
            }
            setButtonsEnabled(true)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = enabled
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
        // Rounded UP and flipped on the finish time's own second boundaries, as
        // the phone does — flooring, on unaligned boundaries, ran up to a second
        // behind the phone on top of any clock difference.
        while (coroutineContext.isActive) {
            val msLeft = (timer.finishAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val remaining = (msLeft + 999) / 1000L
            val minutes = remaining / 60
            val seconds = remaining % 60
            textView.text = "$phase\n\n%d:%02d".format(minutes, seconds)
            if (remaining <= 0L) break
            delay((msLeft - 1) % 1000L + 1)
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

    private companion object {
        val BACKGROUND = Color.parseColor("#0F1417")
    }
}
