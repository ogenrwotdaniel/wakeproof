package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroPhase
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService

/**
 * Pomodoro study mode: 25/5 cycles with a long break every 4th, running in a
 * foreground service. The AI study chat (needs a Kimi key) is deliberately
 * locked during focus phases — that's the point of the app.
 */
@Composable
fun PomodoroScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PomodoroViewModel = hiltViewModel()
) {
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val chatAllowed = viewModel.hasKimiKey && (!timer.active || !timer.running ||
        timer.phase != PomodoroPhase.FOCUS)

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (timer.active) timer.phase.label else "Pomodoro",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(16.dp))

            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(220.dp)) {
                    drawArc(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = 20f, cap = StrokeCap.Round)
                    )
                    val fraction =
                        if (timer.totalSeconds == 0) 0f
                        else timer.remainingSeconds.toFloat() / timer.totalSeconds
                    drawArc(
                        color = androidx.compose.ui.graphics.Color(0xFF4FD1C5),
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        style = Stroke(width = 20f, cap = StrokeCap.Round)
                    )
                }
                Text(
                    text = "%d:%02d".format(timer.remainingSeconds / 60, timer.remainingSeconds % 60),
                    style = MaterialTheme.typography.displayMedium
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { index ->
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = CircleShape,
                        color = if (index < timer.completedFocusSessions % 4 ||
                            (timer.completedFocusSessions > 0 && timer.completedFocusSessions % 4 == 0)
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {}
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !timer.active -> Button(onClick = { PomodoroTimerService.start(context) }) {
                        Text("Start")
                    }
                    timer.running -> Button(onClick = { PomodoroTimerService.pause(context) }) {
                        Text("Pause")
                    }
                    else -> Button(onClick = { PomodoroTimerService.resume(context) }) {
                        Text("Resume")
                    }
                }
                OutlinedButton(
                    onClick = { PomodoroTimerService.reset(context) },
                    enabled = timer.active
                ) { Text("Reset") }
                OutlinedButton(
                    onClick = { PomodoroTimerService.stop(context) },
                    enabled = timer.active
                ) { Text("Stop") }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Study music",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = settings.focusMusicEnabled,
                    onCheckedChange = viewModel::setMusicEnabled
                )
            }
            if (settings.focusLockPhone) {
                Text(
                    text = if (timer.active && timer.running && timer.phase == PomodoroPhase.FOCUS) {
                        "🔒 Phone is pinned to WakeProof until the break"
                    } else {
                        "🔒 Phone locks to WakeProof while focusing (Settings)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            if (viewModel.hasKimiKey) {
                StudyChatPanel(
                    messages = chat.messages,
                    sending = chat.sending,
                    error = chat.error,
                    enabled = chatAllowed,
                    onSend = viewModel::sendChat,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun StudyChatPanel(
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (enabled) "AI study coach (break time)"
            else "AI study coach — locked during focus",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { message ->
                Text(
                    text = (if (message.role == "user") "You: " else "Coach: ") + message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.role == "user") MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = enabled && !sending,
                placeholder = { Text("Quiz me / explain…") },
                singleLine = true
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = enabled && !sending && input.isNotBlank()
            ) { Text(if (sending) "…" else "Send") }
        }
    }
}
