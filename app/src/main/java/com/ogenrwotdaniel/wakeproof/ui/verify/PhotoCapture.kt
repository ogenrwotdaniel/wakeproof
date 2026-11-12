package com.ogenrwotdaniel.wakeproof.ui.verify

import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Large enough to read handwriting on a full page, small enough to keep a year of them. */
internal val PAGE_PHOTO_SIZE = Size(1920, 1440)

/**
 * Camera preview (rear by default, flippable) with a capture button; hands
 * back the JPEG bytes, EXIF orientation included.
 *
 * [cameraFailed] shows the retry over the preview; [failedHint] says what else
 * the screen offers then.
 */
@Composable
internal fun PhotoCaptureView(
    onCaptured: (ByteArray) -> Unit,
    onCameraFailed: () -> Unit,
    cameraFailed: Boolean,
    onRetry: () -> Unit,
    failedHint: String,
    modifier: Modifier = Modifier,
    targetSize: Size = PAGE_PHOTO_SIZE
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
    }
    var useFrontCamera by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // Read only in callbacks, never in composition.
    val session = remember { CaptureSession() }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }

    ReleaseCameraOnDispose { session.dispose() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) session.leftForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(previewView, useFrontCamera, attempt) {
        val view = previewView ?: return@LaunchedEffect
        session.bound()
        val bound = bindForScreen(context, lifecycleOwner, useFrontCamera) {
            listOf(Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }, imageCapture)
        }
        // Again once the bind is done: a photo tapped while it was still running
        // had its camera closed by it, and that is not the camera's fault.
        session.bound()
        if (!bound) onCameraFailed()
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
        if (cameraFailed) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "The camera isn't working. $failedHint",
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                OutlinedButton(
                    onClick = {
                        session.reset()
                        onRetry()
                        attempt++
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text("Try the camera again") }
            }
        }
        Button(
            onClick = {
                // One photo at a time: a double tap must not queue a second frame.
                val thisShot = session.begin() ?: return@Button
                // A queued photo on a stalled camera neither arrives nor errors;
                // without this the button would stay locked with no retry offered.
                session.arm(
                    scope.launch {
                        delay(CaptureSession.CAPTURE_TIMEOUT_MS)
                        if (session.timedOut(thisShot)) onCameraFailed()
                    }
                )
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            if (!session.succeeded(thisShot)) {
                                image.close()
                                return
                            }
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            onCaptured(bytes)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            // Keep the preview up; the user just taps again. Twice
                            // running is a camera that can't take the photo.
                            val fault = when (exception.imageCaptureError) {
                                ImageCapture.ERROR_CAPTURE_FAILED -> CaptureFault.FAILED
                                ImageCapture.ERROR_UNKNOWN -> CaptureFault.UNKNOWN
                                ImageCapture.ERROR_CAMERA_CLOSED -> CaptureFault.CLOSED
                                else -> CaptureFault.OTHER
                            }
                            if (session.failed(thisShot, fault)) onCameraFailed()
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Text("Take photo")
        }
    }
}
