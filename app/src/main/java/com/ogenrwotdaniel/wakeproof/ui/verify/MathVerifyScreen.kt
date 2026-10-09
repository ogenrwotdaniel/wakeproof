package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton
import kotlin.math.roundToInt

/**
 * The math mission: solve [MathVerifyViewModel]'s problems in an unbroken run
 * to silence the alarm. No camera, no light needed — the mission for mornings
 * the pushup and water ones cannot serve.
 *
 * Same exits as the camera screens: [onSnooze] from the first second, the
 * emergency dismiss once its gates open.
 */
@Composable
fun MathVerifyScreen(
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MathVerifyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }

    LaunchedEffect(state.verified) {
        if (state.verified) onVerified()
    }
    LaunchedEffect(state.wrongPulse) {
        if (state.wrongPulse == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = 360
                -18f at 60
                16f at 120
                -10f at 200
                6f at 280
            }
        )
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .widthIn(max = 480.dp)
                    .align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProgressDots(solved = state.progress.solved, required = state.progress.required)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.progress.mistakes > 0 && state.progress.solved == 0) {
                        "Not quite — new problem. The run starts again."
                    } else {
                        "Solve ${state.progress.required} in a row"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(shake.value.roundToInt(), 0) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.progress.problem.prompt,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(vertical = 12.dp)
                            .semantics { contentDescription = "Your answer: ${state.input}" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.input.ifEmpty { "?" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 40.sp,
                            color = if (state.input.isEmpty()) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                Keypad(
                    canSubmit = state.input.isNotEmpty(),
                    onDigit = viewModel::onDigit,
                    onBackspace = viewModel::onBackspace,
                    onSubmit = viewModel::onSubmit
                )

                Spacer(Modifier.height(12.dp))
                if (snoozesLeft > 0 && !state.verified) {
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                        Text("Snooze ${SnoozeStore.SNOOZE_MINUTES} min ($snoozesLeft left today)")
                    }
                }
                if (state.emergencyAvailable && !state.verified) {
                    Spacer(Modifier.height(8.dp))
                    HoldToDismissButton(
                        label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s (logged as skipped)",
                        holdSeconds = state.emergencyHoldSeconds,
                        onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                    )
                }
            }

            if (state.verified) {
                MathSuccess(solved = state.progress.required, onFinish = onFinish)
            }
        }
    }
}

@Composable
private fun ProgressDots(solved: Int, required: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = "$solved of $required solved" }
    ) {
        repeat(required) { index ->
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < solved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            )
        }
    }
}

/** A phone-dialler keypad: large targets, because this is used half asleep. */
@Composable
private fun Keypad(
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit -> Key(text = digit.toString()) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(text = "⌫", description = "Delete", onClick = onBackspace)
            Key(text = "0") { onDigit('0') }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.6f)
                    .semantics { contentDescription = "Submit answer" }
            ) { Text("✓", fontSize = 26.sp) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Key(
    text: String,
    description: String = text,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1.6f)
            .semantics { contentDescription = description }
    ) {
        Text(text, fontSize = 26.sp)
    }
}

@Composable
private fun MathSuccess(solved: Int, onFinish: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "🧠", style = MaterialTheme.typography.displayLarge)
            Text(
                text = "$solved for $solved. You're awake.",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            AutoReturnFooter(onFinish = onFinish)
        }
    }
}
