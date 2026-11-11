package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.pomodoro.PeerSessions
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroPhase
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import com.ogenrwotdaniel.wakeproof.pomodoro.SharedSession
import com.ogenrwotdaniel.wakeproof.pomodoro.joinableAt
import kotlinx.coroutines.delay

/** A session on another device that this one can run too, and how long its phase has left. */
data class JoinablePeer(val session: SharedSession, val remainingSeconds: Long)

/**
 * The session running on the watch or the user's other phone or tablet, while
 * it can be joined from here: not while this device already runs a session,
 * and not in a phase's last seconds. Ticks once a second while there is one.
 */
@Composable
fun rememberJoinablePeer(): JoinablePeer? {
    val peer by PeerSessions.current.collectAsStateWithLifecycle()
    val local by PomodoroTimerService.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(peer) {
        while (peer != null) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    val session = peer?.takeIf { !local.active && it.joinableAt(now) } ?: return null
    // Rounded up, like every countdown here: "0:01" until the phase is really over.
    return JoinablePeer(session, (session.phaseEndsAtMillis - now + 999) / 1_000)
}

/**
 * "Focus on your watch · 12:34 · Continue here" (2026-11-11). One tap runs the
 * same session on this device: the same countdown to the second, with this
 * device's ring, sound and tree. Nothing is handed over or stopped; a block
 * finished on both is recorded once.
 */
@Composable
fun PeerSessionCard(peer: JoinablePeer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = peer.session
    val where = if (session.origin == SharedSession.ORIGIN_WATCH) "your watch" else "your other device"
    // In a break the block just finished is the one to name, as on the watch.
    val block = if (session.phase == PomodoroPhase.FOCUS) session.completedFocus + 1 else session.completedFocus
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "${session.phase.label} on $where", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = listOfNotNull("Block $block of ${session.targetFocusBlocks}", session.subject)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Text(
                    text = "%d:%02d".format(peer.remainingSeconds / 60, peer.remainingSeconds % 60),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            Button(
                onClick = { PomodoroTimerService.join(context, session) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue here") }
        }
    }
}
