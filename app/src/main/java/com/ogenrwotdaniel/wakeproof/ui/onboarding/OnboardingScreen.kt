package com.ogenrwotdaniel.wakeproof.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * First-launch walkthrough of everything the 5 AM alarm needs. Each step shows
 * a green check once granted; Finish unlocks when the critical ones
 * (notifications, camera, full-screen intent, exact alarm) are all green.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val packageUri = Uri.parse("package:${context.packageName}")

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Welcome to WakeProof", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Alarms here can only be dismissed with camera proof. For the 5 AM " +
                    "alarm to actually fire, grant each of these:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OnboardingStep(
                title = "Notifications",
                description = "Required — the ringing screen is launched by a full-screen notification.",
                granted = status.notifications,
                actionLabel = "Grant",
                onAction = { notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            )
            OnboardingStep(
                title = "Camera",
                description = "Required — pushups and water are verified through the camera.",
                granted = status.camera,
                actionLabel = "Grant",
                onAction = { cameraLauncher.launch(android.Manifest.permission.CAMERA) }
            )
            OnboardingStep(
                title = "Full-screen alarms",
                description = "Required — lets the alarm take over the lock screen.",
                granted = status.fullScreenIntent,
                actionLabel = "Open settings",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
                    )
                }
            )
            OnboardingStep(
                title = "Exact alarms",
                description = "Required — WakeProof is an alarm clock and needs exact timing.",
                granted = status.exactAlarm,
                actionLabel = "Open settings",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                    )
                }
            )
            OnboardingStep(
                title = "Battery: unrestricted",
                description = "Strongly recommended — otherwise One UI can silently kill the 5 AM alarm.",
                granted = status.batteryUnrestricted,
                actionLabel = "Request",
                onAction = {
                    @Suppress("BatteryLife")
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
                    )
                }
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Samsung extra steps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Settings → Apps → WakeProof → Battery → Unrestricted\n" +
                            "Settings → Battery → Background usage limits → remove WakeProof " +
                            "from \"Sleeping apps\" / \"Deep sleeping apps\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = { viewModel.complete(onDone) },
                enabled = status.criticalOk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (status.criticalOk) "Finish setup" else "Grant the required items first")
            }
            if (status.criticalOk && !status.batteryUnrestricted) {
                Text(
                    text = "You can finish now, but without unrestricted battery the alarm may " +
                        "not fire on Samsung devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (if (granted) "✅ " else "⬜ ") + title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
