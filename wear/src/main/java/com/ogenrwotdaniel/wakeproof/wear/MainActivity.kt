package com.ogenrwotdaniel.wakeproof.wear

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal launcher screen for the watch app: shows the same progress snapshot
 * as the tile. Kept dependency-light (plain [Activity] + a [TextView]) — the
 * Tile is the primary surface.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1B2A3A"))
            textSize = 16f
            setPadding(24, 24, 24, 24)
            text = getString(R.string.app_name)
        }
        setContentView(textView)
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
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
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
