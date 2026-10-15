package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton
import kotlin.math.roundToInt

private val KEY_GAP = 8.dp

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
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            // Side by side on anything wide and short — a tablet or a phone in
            // landscape. Stacked, the keypad alone was taller than a landscape
            // tablet: the 0 / ✓ row fell off the bottom and no answer could be
            // submitted at all.
            val twoPane = maxWidth >= 600.dp && maxWidth > maxHeight
            // Two-pane: the whole height minus padding and room for the snooze /
            // emergency buttons under the keys.
            val keypadHeight = if (twoPane) maxHeight - 32.dp - 140.dp else maxHeight * 0.45f
            val keyHeight = ((keypadHeight - KEY_GAP * 3) / 4).coerceIn(48.dp, 88.dp)

            val problem: @Composable (Modifier) -> Unit = { m ->
                ProblemPanel(
                    solved = state.progress.solved,
                    required = state.progress.required,
                    mistakes = state.progress.mistakes,
                    prompt = state.progress.problem.prompt,
                    input = state.input,
                    shakeOffset = shake.value.roundToInt(),
                    modifier = m
                )
            }
            val controls: @Composable () -> Unit = {
                Keypad(
                    canSubmit = state.input.isNotEmpty(),
                    keyHeight = keyHeight,
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

            if (twoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    problem(Modifier.weight(1f).fillMaxHeight())
                    // The cap lives on a child of the weighted Box: weight() hands
                    // its child a FIXED width, and a cap applied after it (like
                    // one after a fill modifier) is silently ignored.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 480.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) { controls() }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 480.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    problem(Modifier.weight(1f).fillMaxWidth())
                    controls()
                }
            }

            if (state.verified) {
                MathSuccess(solved = state.progress.required, onFinish = onFinish)
            }
        }
    }
}

/** Progress, the problem, and the answer so far. */
@Composable
private fun ProblemPanel(
    solved: Int,
    required: Int,
    mistakes: Int,
    prompt: String,
    input: String,
    shakeOffset: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressDots(solved = solved, required = required)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (mistakes > 0 && solved == 0) {
                "Not quite — new problem. The run starts again."
            } else {
                "Solve $required in a row"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .offset { IntOffset(shakeOffset, 0) },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = prompt,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.7f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "Your answer: $input" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = input.ifEmpty { "?" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 40.sp,
                    color = if (input.isEmpty()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
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
    keyHeight: Dp,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit
) {
    val rows = listOf("123", "456", "789")
    // Height is handed down, not derived from width with aspectRatio: on a
    // tablet a width-derived key was ~245 dp tall and four rows of them did
    // not fit on the screen.
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEach { digit -> Key(text = digit.toString(), height = keyHeight) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            Key(text = "⌫", description = "Delete", height = keyHeight, onClick = onBackspace)
            Key(text = "0", height = keyHeight) { onDigit('0') }
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .semantics { contentDescription = "Submit answer" }
            ) { Text("✓", fontSize = 26.sp) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Key(
    text: String,
    height: Dp,
    description: String = text,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .weight(1f)
            .height(height)
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
