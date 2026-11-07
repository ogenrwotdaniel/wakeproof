package com.ogenrwotdaniel.wakeproof.ui.verify

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner

/**
 * Binds [useCases] to the requested lens, falling back to the opposite camera
 * when the device lacks it (e.g. cover-screen mode on foldables), so the
 * verification flow never dead-ends on a black preview. False when neither
 * lens could be bound.
 */
internal fun ProcessCameraProvider.bindCamera(
    lifecycleOwner: LifecycleOwner,
    useFrontCamera: Boolean,
    vararg useCases: UseCase
): Boolean {
    val preferred =
        if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    val fallback =
        if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    return try {
        bindToLifecycle(lifecycleOwner, preferred, *useCases)
        true
    } catch (e: Exception) {
        try {
            bindToLifecycle(lifecycleOwner, fallback, *useCases)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** Front/back camera toggle shown over the verification previews. */
@Composable
internal fun FlipCameraButton(onFlip: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onFlip, modifier = modifier) {
        Text("Flip camera")
    }
}

/**
 * Binds the use cases [build] makes, from a LaunchedEffect: its cancellation is
 * what drops a bind meant for a screen already gone. False for any failure,
 * never a throw, because a crash would bring a ringing alarm straight back to it.
 */
internal suspend fun bindForScreen(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    useFrontCamera: Boolean,
    build: () -> List<UseCase>
): Boolean = try {
    val provider = awaitCameraProvider(context)
    val useCases = build()
    provider.unbindAll()
    provider.bindCamera(lifecycleOwner, useFrontCamera, *useCases.toTypedArray())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { runCatching { future.get() }.fold(cont::resume, cont::resumeWithException) },
            ContextCompat.getMainExecutor(context)
        )
    }

/**
 * Lets the camera go when the screen leaves, so the sensor never runs on behind
 * it. [onRelease] runs after the unbind: an analyzer's executor shut down while
 * still bound would be handed frames it must refuse.
 */
@Composable
internal fun ReleaseCameraOnDispose(onRelease: () -> Unit = {}) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose {
            val provider = ProcessCameraProvider.getInstance(context)
            val release = {
                runCatching { provider.get().unbindAll() }
                onRelease()
            }
            // At once when it can be: a posted unbind could land after the next
            // screen's bind and leave it black.
            if (provider.isDone) release() else provider.addListener(release, ContextCompat.getMainExecutor(context))
        }
    }
}

/**
 * Pure bookkeeping for the camera permission on a verify screen: only a change
 * back to granted counts. It is reported on every rotation too, which must not
 * take back a fallback a dead camera has earned.
 */
internal class CameraPermissionLatch {
    private var missing = false

    fun report(granted: Boolean, onLost: () -> Unit, onBack: () -> Unit) {
        if (!granted) {
            missing = true
            onLost()
        } else if (missing) {
            missing = false
            onBack()
        }
    }
}
