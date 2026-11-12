package com.ogenrwotdaniel.wakeproof.ui.verify

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.plan.decodeUprightJpeg
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The day-plan mission: write down everything the day holds, photograph the
 * page, keep the photo. It lands in the Day plans library; nothing reads or
 * judges it (see [PlanVerifyViewModel]).
 *
 * [ringing] false is the same screen opened from the library, with no alarm
 * behind it: no ducking, no snooze, no emergency dismiss, and it closes as soon
 * as the photo is kept.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PlanVerifyScreen(
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
    ringing: Boolean = true,
    viewModel: PlanVerifyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val done = state.step == PlanVerifyViewModel.Step.SUCCESS

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }
    LaunchedEffect(cameraPermission.status.isGranted) {
        viewModel.onCameraPermission(cameraPermission.status.isGranted)
    }
    LaunchedEffect(done) {
        if (!done) return@LaunchedEffect
        onVerified()
        // From the library there is no alarm to announce as off: straight back.
        if (!ringing) onFinish()
    }
    // Quieter while the page is being written and photographed, never silent.
    LaunchedEffect(ringing) {
        if (ringing) AlarmRingingService.setVolume(context, 0.4f)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (state.step) {
                    PlanVerifyViewModel.Step.CAPTURE ->
                        "Write down everything you will do today, then photograph the page"
                    PlanVerifyViewModel.Step.CONFIRM -> "Can you read it? Keep this photo or take it again"
                    PlanVerifyViewModel.Step.SAVING -> "Saving…"
                    PlanVerifyViewModel.Step.SUCCESS -> "Plan saved"
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            if (state.step == PlanVerifyViewModel.Step.CAPTURE && state.topThree.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Last night you put first: " + state.topThree.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            if (state.saveFailed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The photo could not be saved. Unlock the phone and try again.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (state.step) {
                    PlanVerifyViewModel.Step.CAPTURE ->
                        if (cameraPermission.status.isGranted) {
                            PhotoCaptureView(
                                onCaptured = viewModel::onCaptured,
                                onCameraFailed = viewModel::onCameraUnavailable,
                                cameraFailed = state.cameraFailed,
                                onRetry = viewModel::retryCamera,
                                failedHint = if (ringing) {
                                    "Try it again, or use the emergency dismiss below."
                                } else {
                                    "Try it again."
                                }
                            )
                        } else {
                            Centered {
                                Text(
                                    text = "Camera permission is needed to photograph your plan.",
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                    PlanVerifyViewModel.Step.CONFIRM -> Column {
                        state.photo?.let { photo ->
                            // Off the main thread: decoding and turning a full page
                            // would stall the screen with the alarm ringing.
                            val bitmap by produceState<Bitmap?>(initialValue = null, photo) {
                                value = withContext(Dispatchers.Default) { decodeUprightJpeg(photo) }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "The photo of your plan",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::onKeep, modifier = Modifier.fillMaxWidth()) {
                            Text("Keep this photo")
                        }
                        OutlinedButton(onClick = viewModel::onRetake, modifier = Modifier.fillMaxWidth()) {
                            Text("Retake")
                        }
                    }

                    PlanVerifyViewModel.Step.SAVING -> Centered { CircularProgressIndicator() }

                    PlanVerifyViewModel.Step.SUCCESS -> Centered {
                        Text(text = "📝", style = MaterialTheme.typography.displayLarge)
                        Text(
                            text = "Today's plan is in Day plans. Now go and do it.",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        if (ringing) {
                            Spacer(Modifier.height(24.dp))
                            AutoReturnFooter(onFinish = onFinish)
                        }
                    }
                }
            }

            if (ringing && snoozesLeft > 0 && !done) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                    Text("Snooze ${SnoozeStore.SNOOZE_MINUTES} min ($snoozesLeft left today)")
                }
            }
            if (ringing && state.emergencyAvailable && !done) {
                Spacer(Modifier.height(12.dp))
                HoldToDismissButton(
                    label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s (logged as skipped)",
                    holdSeconds = state.emergencyHoldSeconds,
                    onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                )
            }
            if (!ringing && !done) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { content() }
}
