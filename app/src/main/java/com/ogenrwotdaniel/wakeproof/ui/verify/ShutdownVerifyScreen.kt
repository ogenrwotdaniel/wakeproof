package com.ogenrwotdaniel.wakeproof.ui.verify

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.plan.PlanDone
import com.ogenrwotdaniel.wakeproof.plan.loadDayPlanBitmap
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton

/**
 * The evening review: this morning's plan, how much of it got done, and
 * tomorrow's top three (see [ShutdownVerifyViewModel]).
 *
 * [ringing] false is the same screen opened from Day plans, with no alarm
 * behind it: no ducking, snooze or emergency dismiss, and it closes on save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShutdownVerifyScreen(
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
    ringing: Boolean = true,
    viewModel: ShutdownVerifyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.verified) {
        if (!state.verified) return@LaunchedEffect
        onVerified()
        if (!ringing) onFinish()
    }
    // Quiet enough to think and type over, never silent.
    LaunchedEffect(ringing) {
        if (ringing) AlarmRingingService.setVolume(context, 0.3f)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.verified) {
                Spacer(Modifier.height(48.dp))
                Text(text = "🌙", style = MaterialTheme.typography.displayLarge)
                Text(
                    text = "Day closed. Tomorrow's top three are waiting on Home.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                if (ringing) AutoReturnFooter(onFinish = onFinish)
                return@Column
            }

            Text(text = "Evening review", style = MaterialTheme.typography.headlineSmall)

            val plan = state.plan
            if (plan != null) {
                val bitmap by produceState<Bitmap?>(initialValue = null, plan.file) {
                    value = loadDayPlanBitmap(plan.file, PLAN_SIDE)
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "This morning's plan",
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else if (state.planLooked) {
                Text(
                    text = "No plan was photographed today. Judge the day from memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Text(text = "How much of today's plan got done?", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanDone.entries.forEach { choice ->
                    FilterChip(
                        selected = state.done == choice,
                        onClick = { viewModel.setDone(choice) },
                        label = { Text(choice.label) }
                    )
                }
            }

            Text(text = "Tomorrow's top three", style = MaterialTheme.typography.titleMedium)
            state.items.forEachIndexed { index, item ->
                OutlinedTextField(
                    value = item,
                    onValueChange = { viewModel.setItem(index, it) },
                    label = { Text("${index + 1}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (index == state.items.lastIndex) ImeAction.Done else ImeAction.Next
                    ),
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            ) { Text("Close the day") }
            if (!state.canSave) {
                Text(
                    text = "Pick how the day went and write at least one thing for tomorrow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (ringing && snoozesLeft > 0) {
                OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                    Text("Snooze ${SnoozeStore.SNOOZE_MINUTES} min ($snoozesLeft left today)")
                }
            }
            if (ringing && state.emergencyAvailable) {
                HoldToDismissButton(
                    label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s (logged as skipped)",
                    holdSeconds = state.emergencyHoldSeconds,
                    onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                )
            }
            if (!ringing) {
                OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

private const val PLAN_SIDE = 1400
