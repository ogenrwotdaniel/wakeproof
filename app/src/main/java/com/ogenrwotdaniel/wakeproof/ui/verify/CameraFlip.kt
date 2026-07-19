package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner

/**
 * Binds [useCases] to the requested lens, falling back to the opposite camera
 * when the device lacks it (e.g. cover-screen mode on foldables), so the
 * verification flow never dead-ends on a black preview.
 */
internal fun ProcessCameraProvider.bindCamera(
    lifecycleOwner: LifecycleOwner,
    useFrontCamera: Boolean,
    vararg useCases: UseCase
) {
    val preferred =
        if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    val fallback =
        if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    try {
        bindToLifecycle(lifecycleOwner, preferred, *useCases)
    } catch (e: Exception) {
        try {
            bindToLifecycle(lifecycleOwner, fallback, *useCases)
        } catch (e: Exception) {
            // No usable camera; the screen's no-camera emergency path applies.
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
