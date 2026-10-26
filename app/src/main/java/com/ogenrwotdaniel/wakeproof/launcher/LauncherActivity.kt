package com.ogenrwotdaniel.wakeproof.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ogenrwotdaniel.wakeproof.MainActivity
import com.ogenrwotdaniel.wakeproof.distraction.GateActivity
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * WakeProof as the phone's home screen — opt-in, chosen in Settings → Home
 * screen (the system's own default-home switch). A text-only, Minimalist-Phone-
 * style home: clock, today's focus, the calendar, the permanent tools and a
 * few pins, and every other app in a list that will not hold still. No icons,
 * no badges, no search, nothing designed to be tapped for its own sake.
 *
 * Its own task affinity so pressing Home never drags the main WakeProof task
 * (and whatever screen it was on) to the front.
 */
@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Always dark: a home screen that is mostly black is the point —
            // calmer to look at, and nothing on it competes with the apps.
            WakeProofTheme(darkTheme = true) {
                LauncherScreen(
                    viewModel = viewModel,
                    onLaunch = ::launch,
                    onWait = { app ->
                        startActivity(
                            GateActivity.intent(this, app.packageName, app.label)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onOpenWakeProof = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onAppInfo = { pkg ->
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
        }
    }

    private fun launch(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
