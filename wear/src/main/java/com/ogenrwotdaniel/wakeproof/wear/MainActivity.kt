package com.ogenrwotdaniel.wakeproof.wear

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Launcher screen for the watch app.
 *
 * The watch RUNS its own pomodoro now ([WatchTimerEngine]): start, pause, skip
 * a break and stop all work with the phone in another room, flat, or never
 * paired. Each finished focus block waits on the Data Layer and lands in the
 * phone's history — and from there in cloud sync — whenever the two next meet.
 *
 * Three states, in this order of precedence:
 * 1. the watch's own timer, if one is running;
 * 2. the phone's timer, mirrored and remote-controlled as before;
 * 3. idle: today's progress, plus Start (on the watch) and Start on phone.
 *
 * The alarm is deliberately not controllable from here. See the note on
 * `WearContract.COMMAND_PATH`: an alarm you can silence from your wrist is one
 * you can silence without getting up, which is the whole thing the app exists
 * to prevent.
 *
 * ### Staying on screen during focus
 *
 * Ambient-enabled: when the wrist drops mid-session the watch keeps THIS
 * screen up, dimmed, rather than returning to the watch face — so a glance
 * shows the block, not the time and a row of notifications. In ambient the
 * buttons go, the colours go to grey on black (OLED burn-in), and the
 * countdown drops to whole minutes, updated by the system's once-a-minute
 * ambient tick instead of a per-second loop. With nothing running there is
 * nothing to keep up, so ambient entry while idle finishes the activity and
 * the watch face returns as usual.
 *
 * This keeps WakeProof on the screen; it cannot keep you IN it. Wear OS has no
 * screen pinning for ordinary apps, so pressing a side button still leaves.
 *
 * Kept dependency-light (framework views, no Compose) — the Tile is still the
 * primary glanceable surface.
 */
class MainActivity : ComponentActivity() {

    private val scope = MainScope()
    private var renderJob: Job? = null
    private var stopArmedJob: Job? = null
    /** Stop is armed by a first tap until this uptime; the second tap within it stops. */
    private var stopArmedUntil = 0L
    private lateinit var textView: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var root: ScrollView
    private var ambient = false

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            val local = WatchTimerEngine.state(applicationContext)
            if (!local.active) {
                // Nothing running here: let the watch face have the screen back.
                finish()
                return
            }
            ambient = true
            root.setBackgroundColor(Color.BLACK)
            textView.setTextColor(Color.GRAY)
            buttons.visibility = View.GONE
            render()
        }

        override fun onUpdateAmbient() = render()

        override fun onExitAmbient() {
            ambient = false
            root.setBackgroundColor(BACKGROUND)
            textView.setTextColor(Color.WHITE)
            buttons.visibility = View.VISIBLE
            render()
        }
    }

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
        root = ScrollView(this).apply {
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
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        // Needed so the timer Ongoing Activity can be posted on the watch face.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app — from the alert or the launcher — is the acknowledgement.
        Buzzer.stop()
        Wearable.getDataClient(this).addListener(dataListener)
        render()
        scope.launch { FocusSessionOutbox.flush(applicationContext) }
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(dataListener)
        renderJob?.cancel()
        super.onPause()
    }

    private fun render() {
        renderJob?.cancel()
        renderJob = scope.launch {
            val local = WatchTimerEngine.state(applicationContext)
            if (local.active) {
                showLocalControls(local)
                renderLocal(local)
                return@launch
            }
            val phone = TimerSnapshot.read(applicationContext)
            if (phone != null) {
                showPhoneControls(phone)
                renderPhoneTimer(phone)
            } else {
                showIdleControls()
                renderStats()
            }
        }
    }

    // --- The watch's own timer -------------------------------------------------

    /**
     * Pause/resume, skip (breaks only — skipping focus would be "stop" with
     * better manners) and stop. Stop takes two taps: a stop mid-focus throws
     * the block away, and a wrist is easy to brush.
     */
    private fun showLocalControls(state: WatchTimerState) {
        buttons.removeAllViews()
        if (state.running) {
            addButton("Pause") { WatchTimerEngine.pause(applicationContext) }
        } else {
            addButton("Resume") { WatchTimerEngine.resume(applicationContext) }
        }
        if (state.phase != WatchPhase.FOCUS) {
            addButton("Skip break") { WatchTimerEngine.skipBreak(applicationContext) }
        }
        val armed = SystemClock.uptimeMillis() < stopArmedUntil
        addButton(if (armed) "Tap again to stop" else "Stop") {
            if (SystemClock.uptimeMillis() < stopArmedUntil) {
                stopArmedUntil = 0L
                stopArmedJob?.cancel()
                WatchTimerEngine.stop(applicationContext)
            } else {
                stopArmedUntil = SystemClock.uptimeMillis() + STOP_CONFIRM_MS
                stopArmedJob?.cancel()
                stopArmedJob = scope.launch {
                    delay(STOP_CONFIRM_MS)
                    render()
                }
            }
        }
    }

    private suspend fun renderLocal(state: WatchTimerState) {
        val footer = localFooter(state)
        if (ambient) {
            // Whole minutes, rounded up: the screen updates once a minute here,
            // and a seconds digit that is up to a minute stale is a wrong number.
            val minutesLeft = (state.remainingSecondsAt(System.currentTimeMillis()) + 59) / 60
            val paused = if (state.running) "" else " (paused)"
            textView.text = "${state.phase.label}$paused\n\n$minutesLeft min$footer"
            if (state.isDue(System.currentTimeMillis())) {
                WatchTimerEngine.advanceIfDue(applicationContext)
                render()
            }
            return
        }
        if (!state.running) {
            textView.text = "${state.phase.label}\n\n${clock(state.remainingSecondsAt(0L))}\n\n(paused)$footer"
            return
        }
        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val remaining = state.remainingSecondsAt(now)
            textView.text = "${state.phase.label}\n\n${clock(remaining)}$footer"
            if (remaining <= 0L) {
                // The alarm will get there too; whichever is first credits the
                // block, the other finds nothing due.
                WatchTimerEngine.advanceIfDue(applicationContext)
                render()
                return
            }
            val msLeft = state.phaseEndsAt - now
            delay((msLeft - 1) % 1000L + 1)
        }
    }

    /** "Break at 14:32" — a wall-clock time is easier to hold than a countdown. */
    private fun localFooter(state: WatchTimerState): String {
        val block = if (state.phase == WatchPhase.FOCUS) state.completedFocus + 1 else state.completedFocus
        val of = if (state.targetFocusBlocks > 0) " of ${state.targetFocusBlocks}" else ""
        val lastBlock = state.targetFocusBlocks in 1..block && state.phase == WatchPhase.FOCUS
        val next = when {
            lastBlock -> "Done"
            state.phase == WatchPhase.FOCUS -> "Break"
            else -> "Focus"
        }
        val at = if (state.running) "\n$next at ${timeFormat.format(Date(state.phaseEndsAt))}" else ""
        return "\n\nBlock $block$of$at"
    }

    // --- The phone's timer, mirrored --------------------------------------------

    /**
     * The controls that make sense for the state the phone reports.
     *
     * Offering only the applicable ones — rather than greying out the rest — is
     * what keeps this legible on a 45 mm screen, where every control costs a
     * meaningful share of the face.
     */
    private fun showPhoneControls(timer: TimerSnapshot) {
        buttons.removeAllViews()
        if (timer.running) {
            addPhoneButton("Pause", TimerCommands.PAUSE)
        } else {
            addPhoneButton("Resume", TimerCommands.RESUME)
        }
        addPhoneButton("Stop", TimerCommands.STOP)
    }

    /** Shows the phone's countdown, ticking every second while running. */
    private suspend fun renderPhoneTimer(timer: TimerSnapshot) {
        val phase = timer.phase.ifBlank { "Focus" }
        if (!timer.running) {
            textView.text = "$phase · phone\n\n${clock(timer.remainingSeconds.toLong())}\n\n(paused)"
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
            textView.text = "$phase · phone\n\n${clock(remaining)}"
            if (remaining <= 0L) break
            delay((msLeft - 1) % 1000L + 1)
        }
    }

    // --- Idle --------------------------------------------------------------------

    /**
     * The four session lengths the phone offers — free run, 1 h, 1 h 30, 2 h —
     * all run on the WATCH, and work with no phone at all. Starting on the phone is still offered, second,
     * for a session that wants the phone's music, forest and app blocking.
     */
    private fun showIdleControls() {
        buttons.removeAllViews()
        for (session in WatchSession.entries) {
            addButton(session.label) { WatchTimerEngine.start(applicationContext, session) }
        }
        addPhoneButton("Start on phone", TimerCommands.START)
    }

    private suspend fun renderStats() {
        val s = WearStatsReader.read(applicationContext)
        val pending = FocusSessionOutbox.pending(applicationContext)
        val zone = ZoneId.systemDefault()
        val today = focusToday(s, pending, LocalDate.now(zone).toEpochDay(), zone)
        textView.text = buildString {
            if (s.present) {
                append("🔥 ${s.streak}-day streak\n\n")
                append("Focus ${formatFocusMinutes(today.minutes)}")
                if (s.focusGoal > 0) append(" / ${formatFocusMinutes(s.focusGoal)}")
                append("\n")
                append("💧 ${s.waterCount}/${s.waterTarget}  (${s.waterMl} ml)")
            } else {
                append("Focus today ${formatFocusMinutes(today.minutes)}")
            }
            if (today.unsyncedBlocks > 0) {
                append("\n\n${today.unsyncedBlocks} block")
                if (today.unsyncedBlocks > 1) append("s")
                append(" waiting for phone")
            }
        }
    }

    // --- Shared -------------------------------------------------------------------

    private fun addButton(label: String, action: suspend () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                scope.launch {
                    action()
                    render()
                }
            }
        }
        buttons.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        )
        return button
    }

    /**
     * Fires a command and reports honestly if it did not land.
     *
     * There is no optimistic local state: the phone owns ITS timer, and the
     * screen changes only when the phone pushes back. A watch that showed
     * "paused" while the phone kept counting would be worse than one that
     * showed nothing.
     */
    private fun addPhoneButton(label: String, command: String) {
        val button = Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
        }
        button.setOnClickListener {
            scope.launch {
                setButtonsEnabled(false)
                val delivered = TimerCommands.send(applicationContext, command)
                if (!delivered) {
                    textView.text = "Phone unreachable.\nCouldn't ${label.lowercase()}."
                }
                setButtonsEnabled(true)
            }
        }
        buttons.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        )
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = enabled
    }

    private val timeFormat by lazy { DateFormat.getTimeFormat(this) }

    private fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

    /**
     * Any touch silences a phase-change buzz: the app may already be on screen
     * (ambient) when a phase ends, so no onResume follows to do it.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) Buzzer.stop()
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val BACKGROUND = Color.parseColor("#0F1417")
        const val STOP_CONFIRM_MS = 3_000L
    }
}
