package com.ogenrwotdaniel.wakeproof.ui.verify

import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.util.Size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingService
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton

/**
 * Photo hydration verification: shoot the bottle, drink, shoot it again; the drop
 * in the water level is credited in ml and the shots repeat until the per-alarm
 * target is covered (see [WaterVerifyViewModel]). No printed ml scale needed. The
 * alarm keeps ringing, ducked while the wizard runs. Offline/no-key falls back to
 * a 30s honor-mode hold.
 *
 * [onSnooze] is offered from the first second, unlike the emergency dismiss which
 * stays hidden until its gates open — see [com.ogenrwotdaniel.wakeproof.ui.verify.PushupVerifyScreen].
 * [snoozesLeft] runs the button down to nothing.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WaterVerifyScreen(
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WaterVerifyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }
    // Duck the ringtone while the wizard runs; quietest while drinking/proving.
    // As with pushups: the alarm stops the moment the target is met.
    LaunchedEffect(state.step) {
        if (state.step == WaterVerifyViewModel.Step.SUCCESS) onVerified()
    }
    LaunchedEffect(state.step) {
        val volume = when (state.step) {
            WaterVerifyViewModel.Step.CAPTURE_AFTER, WaterVerifyViewModel.Step.UPLOADING -> 0.2f
            else -> 0.5f
        }
        AlarmRingingService.setVolume(context, volume)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (state.step) {
                    WaterVerifyViewModel.Step.CAPTURE_BEFORE ->
                        "Photograph your bottle side-on, so the water line is clearly visible"
                    WaterVerifyViewModel.Step.CONFIRM_BEFORE ->
                        "Use this photo? The water line must be easy to see"
                    WaterVerifyViewModel.Step.CAPTURE_AFTER ->
                        if (state.consumedMl > 0) {
                            "${state.consumedMl} of ${state.waterAmountMl} ml — drink more, " +
                                "then shoot the same bottle again"
                        } else {
                            "Drink some water, then photograph the SAME bottle again"
                        }
                    WaterVerifyViewModel.Step.UPLOADING -> "Comparing the water levels…"
                    WaterVerifyViewModel.Step.SUCCESS -> "Verified!"
                    WaterVerifyViewModel.Step.FALLBACK -> "Verification unavailable"
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            state.progressNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            state.rejectionReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Rejected: $it (${state.rejections}/3)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            // Bottle empty before the target is met? Top it up and re-baseline —
            // only drops from a baseline count, so this can't be used to cheat.
            if (state.step == WaterVerifyViewModel.Step.CAPTURE_AFTER && state.consumedMl > 0) {
                TextButton(onClick = viewModel::onRefilled) {
                    Text("I refilled the bottle — new baseline photo")
                }
            }
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (state.step) {
                    WaterVerifyViewModel.Step.CAPTURE_BEFORE ->
                        if (cameraPermission.status.isGranted) {
                            PhotoCaptureView(onCaptured = viewModel::onBeforeCaptured)
                        } else {
                            CameraDeniedHint()
                        }

                    WaterVerifyViewModel.Step.CONFIRM_BEFORE -> Column {
                        state.beforePhoto?.let { photo ->
                            val bitmap = remember(photo) {
                                BitmapFactory.decodeByteArray(photo, 0, photo.size)
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Before photo",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::onBeforeConfirmed, modifier = Modifier.fillMaxWidth()) {
                            Text("Looks good — I'll drink now")
                        }
                        OutlinedButton(onClick = viewModel::onRetakeBefore, modifier = Modifier.fillMaxWidth()) {
                            Text("Retake")
                        }
                    }

                    WaterVerifyViewModel.Step.CAPTURE_AFTER ->
                        if (cameraPermission.status.isGranted) {
                            PhotoCaptureView(onCaptured = viewModel::onAfterCaptured)
                        } else {
                            CameraDeniedHint()
                        }

                    WaterVerifyViewModel.Step.UPLOADING -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Measuring how far the level dropped…")
                    }

                    WaterVerifyViewModel.Step.SUCCESS -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "💧", style = MaterialTheme.typography.displayLarge)
                        Text(
                            text = "${state.consumedMl} ml down. Logged.",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        state.motivation?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        AutoReturnFooter(onFinish = onFinish)
                    }

                    WaterVerifyViewModel.Step.FALLBACK -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${state.fallbackReason}\n\nHonor mode: hold for 30 seconds to " +
                                "confirm you drank the water. It logs as unverified.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        HoldToDismissButton(
                            label = "I drank it — hold 30 s",
                            holdSeconds = 30,
                            onCompleted = {
                                viewModel.logHonorMode {
                                    onVerified()
                                    onFinish()
                                }
                            }
                        )
                    }
                }
            }

            if (snoozesLeft > 0 && state.step != WaterVerifyViewModel.Step.SUCCESS) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Snooze ${SnoozeStore.SNOOZE_MINUTES} min ($snoozesLeft left today)")
                }
            }
            if (state.emergencyAvailable && state.step != WaterVerifyViewModel.Step.SUCCESS) {
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

@Composable
private fun CameraDeniedHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Camera permission is required to photograph the container.",
            textAlign = TextAlign.Center
        )
    }
}

/** Camera preview (rear by default, flippable) with a capture button; emits JPEG bytes. */
@Composable
private fun PhotoCaptureView(onCaptured: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Neither reader needs a 12 MP frame: the on-device detector resamples to a
    // few hundred rows and the cloud path re-encodes to ~1 K. Capturing near
    // that size skips seconds of sensor readout, JPEG encode and decode on the
    // way to the answer.
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
    }
    var useFrontCamera by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(previewView, useFrontCamera) {
        val view = previewView ?: return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            provider.unbindAll()
            provider.bindCamera(lifecycleOwner, useFrontCamera, preview, imageCapture)
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PreviewView(ctx).also { previewView = it } }
        )
        FlipCameraButton(
            onFlip = { useFrontCamera = !useFrontCamera },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
        Button(
            onClick = {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            onCaptured(bytes)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            // Keep the preview up; the user just taps again.
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Text("Capture")
        }
    }
}
