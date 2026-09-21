package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.ogenrwotdaniel.wakeproof.alarm.SnoozeStore
import com.ogenrwotdaniel.wakeproof.ui.components.HoldToDismissButton
import com.ogenrwotdaniel.wakeproof.verify.pushup.PoseAnalyzer
import com.ogenrwotdaniel.wakeproof.verify.pushup.PushupCounter
import java.util.concurrent.Executors

/**
 * Camera-verified pushups: front-camera preview + on-device ML Kit pose
 * detection with a live rep counter. Completes via [onVerified] only when the
 * target rep count is reached, or [onEmergencyDismiss] after the escape-hatch
 * gates open (3 resets / 5 minutes / no camera) and a long hold.
 *
 * [onSnooze] is the third way out, and unlike the other two it is offered from
 * the first second: the emergency dismiss stays hidden until its gates open, so
 * before then the screen looked like it had no exit at all and testers were
 * rebooting to escape it. [snoozesLeft] runs the button down to nothing.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PushupVerifyScreen(
    onVerified: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    snoozesLeft: Int,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PushupVerifyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }
    LaunchedEffect(cameraPermission.status.isGranted) {
        if (!cameraPermission.status.isGranted) viewModel.onCameraUnavailable()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (cameraPermission.status.isGranted) {
                CameraWithPoseOverlay(viewModel)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Camera permission is required to count pushups. " +
                            "Grant it, or use the emergency dismiss below.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            CounterOverlay(
                reps = state.reps,
                target = state.target,
                status = state.status,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (snoozesLeft > 0 && !state.verified) {
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Snooze ${SnoozeStore.SNOOZE_MINUTES} min " +
                                "($snoozesLeft left today)"
                        )
                    }
                }
                if (state.emergencyAvailable && !state.verified) {
                    HoldToDismissButton(
                        label = "Emergency dismiss — hold ${state.emergencyHoldSeconds}s (logged as skipped)",
                        holdSeconds = state.emergencyHoldSeconds,
                        onCompleted = { viewModel.logSkipped(onEmergencyDismiss) }
                    )
                }
            }

            if (state.verified) {
                SuccessOverlay(
                    target = state.target,
                    motivation = state.motivation,
                    onContinue = onVerified
                )
            }
        }
    }
}

@Composable
private fun CameraWithPoseOverlay(viewModel: PushupVerifyViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { PoseAnalyzer(viewModel::onPoseFrame) }
    var useFrontCamera by remember { mutableStateOf(true) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.shutdown()
            analysisExecutor.shutdown()
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
    }

    LaunchedEffect(previewView, useFrontCamera) {
        val view = previewView ?: return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
            provider.unbindAll()
            provider.bindCamera(lifecycleOwner, useFrontCamera, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    // Read outside the Canvas: a draw lambda is not a composable scope.
    val skeletonColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PreviewView(ctx).also { previewView = it } }
        )
        // Skeleton overlay: map image coords to a FILL_CENTER preview, mirrored
        // horizontally only for the front camera's selfie view.
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (state.skeleton.isEmpty()) return@Canvas
            val scale = maxOf(
                size.width / state.imageWidth, size.height / state.imageHeight
            )
            val dx = (size.width - state.imageWidth * scale) / 2f
            val dy = (size.height - state.imageHeight * scale) / 2f
            state.skeleton.forEach { p ->
                val mapped = p.x * scale + dx
                val x = if (useFrontCamera) size.width - mapped else mapped
                val y = p.y * scale + dy
                drawCircle(skeletonColor, radius = 8f, center = Offset(x, y))
            }
        }
        FlipCameraButton(
            onFlip = { useFrontCamera = !useFrontCamera },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}

@Composable
private fun CounterOverlay(
    reps: Int,
    target: Int,
    status: PushupCounter.Status,
    modifier: Modifier = Modifier
) {
    val skeletonColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier.padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(160.dp)) {
                drawArc(
                    color = Color.White.copy(alpha = 0.2f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = 16f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = skeletonColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (reps.toFloat() / target).coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(width = 16f, cap = StrokeCap.Round)
                )
            }
            Text(
                text = "$reps / $target",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = when (status) {
                PushupCounter.Status.NO_POSE ->
                    "Lean the phone against a wall 1–2 m away — whole body visible"
                PushupCounter.Status.NOT_HORIZONTAL -> "Get into pushup position"
                PushupCounter.Status.DOWN -> "Up!"
                PushupCounter.Status.UP -> "Down!"
                PushupCounter.Status.BETWEEN -> "Keep going"
            },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun SuccessOverlay(
    target: Int,
    motivation: String?,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "🎉", style = MaterialTheme.typography.displayLarge)
            Text(
                text = "$target pushups. Verified.",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            motivation?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Silence the alarm")
            }
        }
    }
}
