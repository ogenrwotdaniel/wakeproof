package com.ogenrwotdaniel.wakeproof.ui.verify

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.data.ReviewGrade
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton
import com.ogenrwotdaniel.wakeproof.verify.review.REVIEW_THINK_MILLIS
import kotlinx.coroutines.delay

/**
 * The review mission: answer the due flashcards to silence the alarm (see
 * [ReviewVerifyViewModel]). Each question stands for [REVIEW_THINK_MILLIS]
 * before its answer can be shown, so tapping through is slower than recalling.
 *
 * [onNoCards] runs when there is nothing to review; the caller sends the alarm
 * to the sums. Same exits as the other verify screens: [onSnooze] from the
 * first second, the emergency dismiss once its gate opens.
 */
@Composable
fun ReviewVerifyScreen(
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    onNoCards: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewVerifyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noCards by rememberUpdatedState(onNoCards)

    LaunchedEffect(state.noCards) {
        if (state.noCards) noCards()
    }
    LaunchedEffect(state.verified) {
        if (state.verified) onVerified()
    }
    // Quiet enough to think over, never silent; and only once there are cards:
    // ducked before that, the sums this may hand over to would ring at the
    // ducked volume, quieter than the math alarm they stand in for.
    val hasCards = !state.loading && !state.noCards
    LaunchedEffect(hasCards) {
        if (hasCards) AlarmRingingService.setVolume(context, 0.3f)
    }

    var mayReveal by remember { mutableStateOf(false) }
    LaunchedEffect(state.cardShownAt, state.loading) {
        mayReveal = false
        if (state.loading) return@LaunchedEffect
        delay(state.cardShownAt + REVIEW_THINK_MILLIS - SystemClock.elapsedRealtime())
        mayReveal = true
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val card = state.run.current
            when {
                state.verified -> Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "🗂", style = MaterialTheme.typography.displayLarge)
                    Text(
                        text = "${state.run.required} cards reviewed. That's today's recall done.",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    AutoReturnFooter(onFinish = onFinish)
                }

                card == null -> Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }

                else -> {
                    Text(
                        text = "Card ${state.run.done + 1} of ${state.run.required}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = card.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Card(
                        // widthIn before fillMaxWidth: the other order ignores the cap.
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(text = card.front, style = MaterialTheme.typography.headlineSmall)
                            if (state.revealed) {
                                HorizontalDivider()
                                Text(
                                    text = card.back,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!state.revealed) {
                        Button(
                            onClick = viewModel::reveal,
                            enabled = mayReveal,
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        ) {
                            Text(if (mayReveal) "Show answer" else "Recall it first…")
                        }
                    } else {
                        Text(
                            text = "How well did you know it?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReviewGrade.entries.forEach { grade ->
                                FilledTonalButton(
                                    onClick = { viewModel.grade(grade) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                ) { Text(grade.label, maxLines = 1) }
                            }
                        }
                    }
                }
            }

            if (snoozesLeft > 0 && !state.verified) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                    Text("Snooze ${SnoozeStore.SNOOZE_MINUTES} min ($snoozesLeft left today)")
                }
            }
            if (state.emergencyAvailable && !state.verified) {
                Spacer(Modifier.height(12.dp))
                HoldToDismissButton(
                    label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s (logged as skipped)",
                    holdSeconds = state.emergencyHoldSeconds,
                    onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                )
            }
        }
    }
}
