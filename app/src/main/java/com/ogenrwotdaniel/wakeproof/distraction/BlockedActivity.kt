package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The wall shown when a [com.ogenrwotdaniel.wakeproof.data.LimitMode.BLOCK] app
 * is opened after its daily allowance is gone.
 *
 * Deliberately offers nothing but the way out: no override, no "5 more minutes",
 * no dismiss that lands back in the app. That is the whole point of a limit you
 * chose in advance — the version of you that set it knew more than the version
 * that wants around it. It lifts at midnight on its own.
 *
 * Also the nuclear-focus wall: during a focus session, any app not on the
 * focus list lands here instead, until the session ends ([focusIntent]).
 */
class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "That app"
        val used = intent.getIntExtra(EXTRA_USED, 0)
        val cap = intent.getIntExtra(EXTRA_CAP, 0)
        val focus = intent.getBooleanExtra(EXTRA_FOCUS, false)

        // Back must leave, not fall through to the app behind this screen.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            WakeProofTheme {
                if (focus) FocusWall(label = label, onLeave = ::goHome)
                else BlockedWall(label = label, usedMinutes = used, capMinutes = cap, onLeave = ::goHome)
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_USED = "used"
        private const val EXTRA_CAP = "cap"
        private const val EXTRA_FOCUS = "focus"

        fun intent(context: Context, label: String, usedMinutes: Int, capMinutes: Int): Intent =
            Intent(context, BlockedActivity::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_USED, usedMinutes)
                .putExtra(EXTRA_CAP, capMinutes)

        /** The wall for an app off the focus list while a session runs. */
        fun focusIntent(context: Context, label: String): Intent =
            Intent(context, BlockedActivity::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_FOCUS, true)
    }
}

@Composable
private fun BlockedWall(
    label: String,
    usedMinutes: Int,
    capMinutes: Int,
    onLeave: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$label is done for today",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "$usedMinutes of $capMinutes minutes used.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = "Unlocks in ${timeUntilMidnight()}. You set this limit — there is no " +
                    "way around it, which is exactly why it works.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 24.dp)
            )
            Button(
                onClick = onLeave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp)
            ) { Text("Back to it") }
        }
    }
}

/**
 * Says what the session's state is rather than the app's: there is nothing
 * about this app to argue with, only a session that has not ended yet.
 */
@Composable
private fun FocusWall(label: String, onLeave: () -> Unit) {
    val timer by PomodoroTimerService.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Focus session",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = Color(0xFFBDBDBD)
            )
            Text(
                text = "$label stays shut until the session ends.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color(0xFF8A8A8A),
                modifier = Modifier.padding(top = 12.dp)
            )
            if (timer.active && timer.targetFocusBlocks > 0) {
                Text(
                    text = "${timer.completedFocusSessions} of ${timer.targetFocusBlocks} " +
                        "pomodoros done · ${timer.phase.label.lowercase()} " +
                        "%d:%02d left".format(timer.remainingSeconds / 60, timer.remainingSeconds % 60),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = Color(0xFF8A8A8A),
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
            Text(
                text = "Only your focus list opens now (Settings → Study apps). The phone " +
                    "always works.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color(0xFF6E6E6E),
                modifier = Modifier.padding(top = 24.dp)
            )
            Button(
                onClick = onLeave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp)
            ) { Text("Back to it") }
        }
    }
}

/** "3 h 20 m" of remaining lock, so the wall says something concrete. */
private fun timeUntilMidnight(): String {
    val until = Duration.between(
        LocalDateTime.now(),
        LocalDate.now().plusDays(1).atStartOfDay()
    )
    val hours = until.toHours()
    val minutes = until.toMinutes() % 60
    return if (hours > 0) "$hours h ${minutes.toString().padStart(2, '0')} m" else "$minutes min"
}
