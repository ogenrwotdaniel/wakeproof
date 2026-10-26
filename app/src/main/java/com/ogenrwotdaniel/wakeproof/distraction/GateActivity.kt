package com.ogenrwotdaniel.wakeproof.distraction

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import kotlinx.coroutines.delay

/**
 * The five-minute wait in front of a social, video or messaging app (see
 * [AppGate]). Thrown up by [AppBlockService] whichever way the app was opened,
 * and opened directly by the WakeProof home screen.
 *
 * Built to be dull: grey on black, a countdown, one plain line at a time.
 * Leaving it for anything — Home, Recents, the Edge panel, the screen going
 * off — finishes it, so the five minutes have to be sat through in one go and
 * cannot be run down in the background while the user does something else.
 * Only when they are up does "Open" appear, and it grants a [GatePasses] pass.
 */
class GateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName == null) {
            finish()
            return
        }
        val label = intent.getStringExtra(EXTRA_LABEL) ?: packageName
        // Survives a rotation (which recreates the activity) but not leaving.
        val startedAt = savedInstanceState?.getLong(STATE_STARTED_AT)
            ?: SystemClock.elapsedRealtime()
        this.startedAt = startedAt

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            WakeProofTheme {
                GateScreen(
                    label = label,
                    startedAt = startedAt,
                    onOpen = { open(packageName) },
                    onLeave = ::goHome
                )
            }
        }
    }

    private var startedAt = 0L

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_STARTED_AT, startedAt)
    }

    override fun onStop() {
        super.onStop()
        // Walking away resets the wait. A rotation also passes through onStop,
        // and must not.
        if (!isChangingConfigurations) finish()
    }

    private fun open(packageName: String) {
        GatePasses.grant(packageName, System.currentTimeMillis())
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { startActivity(it) } }
        finish()
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
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LABEL = "label"
        private const val STATE_STARTED_AT = "started_at"

        fun intent(context: Context, packageName: String, label: String): Intent =
            Intent(context, GateActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LABEL, label)
    }
}

/** How long each of [WAIT_LINES] stays up. Slow on purpose. */
private const val LINE_SECONDS = 20

/**
 * Shown one after another while the wait runs. Plain and a little deflating:
 * every line is an honest reason to tap "Not now" instead.
 */
private val WAIT_LINES = listOf(
    "Nothing in there is new since last time.",
    "In ten minutes you won't remember what you saw.",
    "This wait is the most boring part of your day. The app is the second.",
    "You could have read a page of your notes by now.",
    "Still here? The urge usually passes before the timer does.",
    "Whoever messaged you can wait as long as you can.",
    "The feed is built to be opened. You don't have to be the one opening it.",
    "Five minutes of nothing, for what? Put the phone face down."
)

@Composable
private fun GateScreen(
    label: String,
    startedAt: Long,
    onOpen: () -> Unit,
    onLeave: () -> Unit
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = ((now - startedAt) / 1_000).toInt().coerceAtLeast(0)
    val left = (AppGate.GATE_WAIT_SECONDS - elapsed).coerceAtLeast(0)
    val grey = Color(0xFF8A8A8A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$label opens in",
            color = grey,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "%d:%02d".format(left / 60, left % 60),
            color = grey,
            fontSize = 56.sp
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = WAIT_LINES[(elapsed / LINE_SECONDS) % WAIT_LINES.size],
            color = grey,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onLeave) {
            Text("Not now", color = Color.White)
        }
        if (left == 0) {
            TextButton(onClick = onOpen) {
                Text("Open $label", color = grey, textAlign = TextAlign.Start)
            }
        } else {
            Text(
                text = "Leaving this screen starts the wait again.",
                color = grey.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp)
            )
        }
    }
}
