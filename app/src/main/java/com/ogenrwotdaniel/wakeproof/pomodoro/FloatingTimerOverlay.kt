package com.ogenrwotdaniel.wakeproof.pomodoro

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ogenrwotdaniel.wakeproof.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draggable Pomodoro countdown bubble drawn over other apps
 * (TYPE_APPLICATION_OVERLAY). Owned by [PomodoroTimerService]; shown only
 * while a timer is active, the setting is on, overlay permission is granted
 * and the app itself is not on screen. Tapping the bubble reopens the app.
 *
 * Built with programmatic views (not Compose): an overlay window has no
 * lifecycle/saved-state owner, which ComposeView requires.
 */
class FloatingTimerOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubble: LinearLayout? = null
    private var phaseView: TextView? = null
    private var timeView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Adds the bubble if permitted and not already visible. */
    fun show() {
        if (bubble != null || !canShow()) return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val phase = TextView(context).apply {
            setTextColor(Color.WHITE)
            alpha = 0.8f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val time = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(BUBBLE_COLOR)
            }
            elevation = dp(6).toFloat()
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(phase)
            addView(time)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        attachDragHandler(container, params)

        runCatching { windowManager.addView(container, params) }.onSuccess {
            bubble = container
            phaseView = phase
            timeView = time
            layoutParams = params
        }
    }

    /**
     * Keeps the screen from timing out while the bubble is visible. The bubble is
     * a real (if tiny) window, so FLAG_KEEP_SCREEN_ON on it holds the screen on
     * even though another app is in front — that's how "keep screen awake in
     * focus" survives leaving WakeProof, with no wake lock to leak.
     */
    fun setKeepScreenOn(enabled: Boolean) {
        val params = layoutParams ?: return
        val view = bubble ?: return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val updated = if (enabled) params.flags or flag else params.flags and flag.inv()
        if (updated == params.flags) return
        params.flags = updated
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** Updates the countdown text; no-op while hidden. */
    fun update(state: PomodoroState) {
        phaseView?.text = state.phase.label + if (state.running) "" else " · paused"
        timeView?.text = "%d:%02d".format(
            state.remainingSeconds / 60, state.remainingSeconds % 60
        )
    }

    fun hide() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        phaseView = null
        timeView = null
        layoutParams = null
    }

    /** Drag moves the bubble; a press that never leaves the touch slop opens the app. */
    private fun attachDragHandler(view: LinearLayout, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                        openApp()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        /** Dark translucent scrim; readable over both light and dark apps. */
        const val BUBBLE_COLOR = 0xE61C1B1F.toInt()
    }
}
