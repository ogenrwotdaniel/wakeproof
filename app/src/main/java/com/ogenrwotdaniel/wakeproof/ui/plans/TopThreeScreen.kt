package com.ogenrwotdaniel.wakeproof.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Tomorrow's top three: three lines and a save (see [TopThreeViewModel]).
 *
 * [ringing] true is the 19:50 alarm's screen: saving silences it
 * ([onVerified]), and it has the ordinary snooze and emergency dismiss, since
 * the slot is fixed but not hard. False is the same screen opened from Home.
 */
@Composable
fun TopThreeScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    ringing: Boolean = false,
    onVerified: () -> Unit = {},
    onEmergencyDismiss: () -> Unit = {},
    snoozesLeft: Int = 0,
    onSnooze: () -> Unit = {},
    viewModel: TopThreeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.saved) {
        if (!state.saved) return@LaunchedEffect
        if (ringing) onVerified() else onFinish()
    }
    // Quiet enough to think and type over, never silent.
    LaunchedEffect(ringing) {
        if (ringing) com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService.setVolume(context, 0.3f)
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ringing && state.saved) {
                Text(
                    text = "Saved. They wait on Home in the morning. Now to bed.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                com.ogenrwotdaniel.wakeproof.ui.verify.AutoReturnFooter(onFinish = onFinish)
                return@Column
            }
            Text(text = "Tomorrow's top three", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "The three things that matter most tomorrow, most important first. " +
                    "They wait on Home in the morning.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            state.items.forEachIndexed { index, item ->
                OutlinedTextField(
                    value = item,
                    onValueChange = { viewModel.setItem(index, it) },
                    enabled = state.loaded,
                    label = { Text("${index + 1}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (index == state.items.lastIndex) ImeAction.Done else ImeAction.Next
                    ),
                    // Constraint first: after fillMaxWidth a width cap is ignored.
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                )
            }
            Button(
                onClick = { viewModel.save(ringing) },
                enabled = state.canSave,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
            ) { Text("Save") }
            if (!ringing) {
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                ) { Text("Not now") }
            }
            if (ringing && snoozesLeft > 0) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                ) {
                    Text(
                        "Snooze ${com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore.SNOOZE_MINUTES} min " +
                            "($snoozesLeft left today)"
                    )
                }
            }
            if (ringing && state.emergencyAvailable) {
                com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton(
                    label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s",
                    holdSeconds = state.emergencyHoldSeconds,
                    onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                )
            }
        }
    }
}
