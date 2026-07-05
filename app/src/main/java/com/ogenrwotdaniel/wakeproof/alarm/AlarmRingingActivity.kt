package com.ogenrwotdaniel.wakeproof.alarm

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ogenrwotdaniel.wakeproof.ui.alarm.AlarmFlowNavGraph
import com.ogenrwotdaniel.wakeproof.ui.alarm.AlarmRingingViewModel
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Launched by the full-screen intent notification while the alarm rings.
 * Shows over the lock screen, turns the screen on, and hosts the
 * ringing → verification flow. The alarm stops only via verification.
 */
@AndroidEntryPoint
class AlarmRingingActivity : ComponentActivity() {

    private val viewModel: AlarmRingingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        viewModel.setAlarmId(intent.getLongExtra(EXTRA_ALARM_ID, -1L))

        setContent {
            WakeProofTheme {
                AlarmFlowNavGraph(
                    viewModel = viewModel,
                    onVerified = {
                        viewModel.markCompletedToday {
                            AlarmRingingService.stop(this)
                            finishAndRemoveTask()
                        }
                    }
                )
            }
        }
    }

    // singleInstance: a second alarm's full-screen intent lands here, not in a
    // new instance — without this the stale alarm stays on screen and gets
    // marked complete instead of the one actually ringing.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.setAlarmId(intent.getLongExtra(EXTRA_ALARM_ID, -1L))
    }
}
