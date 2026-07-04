package com.ogenrwotdaniel.wakeproof.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dashboard. For now it is a navigation smoke-test hub; the stats phase turns it
 * into the streak/status dashboard.
 */
@Composable
fun HomeScreen(
    onOpenOnboarding: () -> Unit,
    onOpenAlarmRinging: () -> Unit,
    onOpenPushupVerify: () -> Unit,
    onOpenWaterVerify: () -> Unit,
    onOpenPomodoro: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "WakeProof", style = MaterialTheme.typography.displaySmall)
            Text(
                text = "Prove it. Then the alarm stops.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = onOpenPomodoro, modifier = Modifier.fillMaxWidth()) {
                Text("Pomodoro study mode")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
            OutlinedButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Onboarding")
            }
            OutlinedButton(onClick = onOpenAlarmRinging, modifier = Modifier.fillMaxWidth()) {
                Text("Preview: alarm ringing")
            }
            OutlinedButton(onClick = onOpenPushupVerify, modifier = Modifier.fillMaxWidth()) {
                Text("Preview: pushup verification")
            }
            OutlinedButton(onClick = onOpenWaterVerify, modifier = Modifier.fillMaxWidth()) {
                Text("Preview: water verification")
            }
        }
    }
}
