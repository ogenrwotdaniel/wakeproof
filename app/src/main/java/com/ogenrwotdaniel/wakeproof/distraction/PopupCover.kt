package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Pure: whether the pop-up cover should be up (2026-11-01).
 *
 * A wall is an activity in its own full-screen task, and a Samsung pop-up
 * window (or the other half of a split screen) floats ABOVE full-screen tasks
 * — so an app opened in a pop-up kept running, usable, on top of its own wall.
 * An overlay window draws above pop-ups, so the cover goes there.
 *
 * Up when the app last walled ([wallAgeMillis] ago) is still resumed beside
 * WakeProof's wall, which is in front. Not in the first [COVER_SETTLE_MILLIS]:
 * a full-screen app's PAUSED lands a moment after the wall's RESUMED, and the
 * cover must not flash over every ordinary wall. Never past [COVER_MAX_MILLIS]
 * after the wall: the resumed set is folded from events, and a PAUSED that was
 * never recorded would otherwise hold a screen-sized, touch-eating window over
 * the phone for good. Re-entering the app raises a fresh wall, so the limit
 * costs nothing against a genuine pop-up. Not during the "let me close it"
 * grace either ([inGrace]), which is how the user reaches the pop-up's X.
 */
fun popupCoverWanted(
    wallAgeMillis: Long?,
    ownWallInFront: Boolean,
    walledStillResumed: Boolean,
    inGrace: Boolean
): Boolean =
    wallAgeMillis != null &&
        wallAgeMillis in COVER_SETTLE_MILLIS..COVER_MAX_MILLIS &&
        ownWallInFront && walledStillResumed && !inGrace

/** How long after a wall a still-resumed app counts as sitting beside it. */
const val COVER_SETTLE_MILLIS = 1_500L

/** The cover's longest life per wall; see [popupCoverWanted]. */
const val COVER_MAX_MILLIS = 2 * 60_000L

/** How long "Let me close it" lifts the cover. */
const val COVER_GRACE_MILLIS = 8_000L

/**
 * Pure: whether the PIN cover should be up (2026-11-04, user report: "you can
 * bypass all restrictions by pinning the app on the screen").
 *
 * While the user has a task pinned (Android's screen pinning,
 * `LOCK_TASK_MODE_PINNED`), the system refuses to start any activity outside
 * that task — so every wall, which is an activity in its own task, silently
 * failed to appear and a pinned SNS app ran free. An overlay window still
 * draws over a pinned task, so the cover goes there instead, for as long as
 * the pin lasts and the app in front would be walled.
 *
 * No time limit, unlike [popupCoverWanted]: this reads the system's live pin
 * state every tick rather than a fold of events, so there is no lost event
 * that could hold it up. And it never stops the way out — the cover is not
 * focusable, so the system's unpin gesture still works, and unpinning drops it.
 */
fun pinCoverWanted(userPinned: Boolean, verdict: GateVerdict): Boolean =
    userPinned && verdict != GateVerdict.ALLOW

/**
 * The full-screen overlay itself. Programmatic views, like
 * [com.ogenrwotdaniel.wakeproof.pomodoro.FloatingTimerOverlay]: an overlay
 * window has no lifecycle owner for Compose. Touch-eating, so the pop-up
 * beneath cannot be used through it; its one button lifts it for
 * [COVER_GRACE_MILLIS] so the pop-up can be closed.
 */
class PopupCover(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var label: TextView? = null

    val isShowing: Boolean get() = view != null

    /**
     * Shows (or relabels) the cover with [text]; a button labelled [buttonLabel]
     * runs [onButton], and there is no button when either is null.
     */
    fun show(text: String, buttonLabel: String? = null, onButton: (() -> Unit)? = null) {
        if (!Settings.canDrawOverlays(context)) return
        view?.let {
            label?.text = text
            return
        }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val message = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        val button = if (buttonLabel != null && onButton != null) {
            Button(context).apply {
                this.text = buttonLabel
                setOnClickListener { onButton() }
            }
        } else {
            null
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(COVER_COLOR)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(message)
            if (button != null) {
                addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(24) }
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        runCatching { windowManager.addView(root, params) }.onSuccess {
            view = root
            label = message
        }
    }

    fun hide() {
        val current = view ?: return
        runCatching { windowManager.removeView(current) }
        view = null
        label = null
    }

    private companion object {
        val COVER_COLOR = Color.rgb(0x10, 0x14, 0x18)
    }
}
