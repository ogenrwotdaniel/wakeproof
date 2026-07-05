package com.ogenrwotdaniel.wakeproof.ui.verify

import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton

/**
 * Two-photo hydration verification: photograph the FULL container, drink,
 * photograph it EMPTY; Kimi judges. The alarm keeps ringing, ducked while the
 * wizard runs. Offline/no-key falls back to a 30s honor-mode hold.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WaterVerifyScreen(
    onVerified: () -> Unit,
    onEmergencyDismiss: () -> Unit,
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
    LaunchedEffect(state.step) {
        val volume = when (state.step) {
            WaterVerifyViewModel.Step.CAPTURE_EMPTY, WaterVerifyViewModel.Step.UPLOADING -> 0.2f
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
                    WaterVerifyViewModel.Step.CAPTURE_FULL -> "Step 1/3 — photograph your FULL ~${state.waterAmountMl} ml bottle or glass"
                    WaterVerifyViewModel.Step.CONFIRM_FULL -> "Step 1/3 — use this photo?"
                    WaterVerifyViewModel.Step.CAPTURE_EMPTY -> "Step 2/3 — drink it all, then photograph the SAME container EMPTY"
                    WaterVerifyViewModel.Step.UPLOADING -> "Step 3/3 — verifying…"
                    WaterVerifyViewModel.Step.SUCCESS -> "Verified!"
                    WaterVerifyViewModel.Step.FALLBACK -> "Verification unavailable"
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            state.rejectionReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Rejected: $it (${state.rejections}/3)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (state.step) {
                    WaterVerifyViewModel.Step.CAPTURE_FULL ->
                        if (cameraPermission.status.isGranted) {
                            PhotoCaptureView(onCaptured = viewModel::onFullCaptured)
                        } else {
                            CameraDeniedHint()
                        }

                    WaterVerifyViewModel.Step.CONFIRM_FULL -> Column {
                        state.fullPhoto?.let { photo ->
                            val bitmap = remember(photo) {
                                BitmapFactory.decodeByteArray(photo, 0, photo.size)
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Full container photo",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::onFullConfirmed, modifier = Modifier.fillMaxWidth()) {
                            Text("Looks good — I'll drink it now")
                        }
                        OutlinedButton(onClick = viewModel::onRetakeFull, modifier = Modifier.fillMaxWidth()) {
                            Text("Retake")
                        }
                    }

                    WaterVerifyViewModel.Step.CAPTURE_EMPTY ->
                        if (cameraPermission.status.isGranted) {
                            PhotoCaptureView(onCaptured = viewModel::onEmptyCaptured)
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
                        Text("Sending both photos to Kimi…")
                    }

                    WaterVerifyViewModel.Step.SUCCESS -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "💧", style = MaterialTheme.typography.displayLarge)
                        Text(
                            text = "${state.waterAmountMl} ml down. Logged.",
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
                        Button(onClick = onVerified, modifier = Modifier.fillMaxWidth()) {
                            Text("Silence the alarm")
                        }
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
                            onCompleted = { viewModel.logHonorMode(onVerified) }
                        )
                    }
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

/** Rear-camera preview with a capture button; emits JPEG bytes. */
@Composable
private fun PhotoCaptureView(onCaptured: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
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
