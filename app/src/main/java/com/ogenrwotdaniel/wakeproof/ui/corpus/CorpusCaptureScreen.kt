package com.ogenrwotdaniel.wakeproof.ui.corpus

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.ui.verify.bindCamera

/**
 * Debug-only tool for shooting the offline water detector's depth corpus.
 *
 * Every threshold in `verify/water/` was fitted on synthetic scenes, and
 * `tools/water/evaluate.py --gate` has never been cleared against a real
 * photograph. This screen is what makes clearing it an evening rather than a
 * project: it enforces the shooting protocol in `tools/water/README.md`, keeps
 * the framing consistent within a sequence, and writes the `shots.csv` manifest
 * as you go so the bookkeeping cannot drift from the images.
 *
 * Pull the result straight into the harness:
 *
 * ```
 * adb pull /sdcard/Android/data/com.ogenrwotdaniel.wakeproof/files/water-corpus tools/water/corpus
 * cd tools/water && $PY sequences.py corpus/shots.csv > corpus/manifest.csv
 * $PY evaluate.py --corpus corpus/ --gate -v --overlays /tmp/ov
 * ```
 *
 * Reachable only from the debug-gated section of Home: it retains photographs,
 * and the shipping app deliberately does not.
 */
@Composable
fun CorpusCaptureScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CorpusCaptureViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Water corpus capture", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Fill the bottle, shoot, pour a measured amount into a jug, update the " +
                    "cumulative total, shoot again. Keep the framing and the background " +
                    "the same within a sequence — and use a cluttered background, not a " +
                    "plain wall, or the corpus is easier than reality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Sequence ${state.sequence}: ${state.shotsInSequence} shots → " +
                            "${state.pairsSoFar} labelled pairs",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "${state.totalShots} photographs recorded in total. " +
                            "Aim for ~8 shots per sequence, across 3 bottles × 3 lightings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    state.lastSaved?.let {
                        Text(
                            "Saved $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.sequence,
                    onValueChange = viewModel::setSequence,
                    label = { Text("Sequence") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.lighting,
                    onValueChange = viewModel::setLighting,
                    label = { Text("Lighting") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.bottle,
                    onValueChange = viewModel::setBottle,
                    label = { Text("Bottle") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.capacityMl,
                    onValueChange = viewModel::setCapacity,
                    label = { Text("Capacity ml") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = state.pouredTotalMl,
                onValueChange = viewModel::setPoured,
                label = { Text("Cumulative ml poured out so far") },
                supportingText = {
                    Text(
                        "Read this off the jug and update it BEFORE each shot. It is " +
                            "never advanced for you — a guessed number produces a corpus " +
                            "that looks complete and is quietly wrong."
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Switch(checked = state.unreadable, onCheckedChange = viewModel::setUnreadable)
                Text(
                    "Opaque container (the reject class — mark a steel flask, whose " +
                        "water line genuinely cannot be seen)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            CorpusCameraView(
                onCaptured = viewModel::capture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            )

            Text(
                state.directory,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

/**
 * Rear camera only, and no flip control.
 *
 * The verification screens offer a flip because someone doing pushups needs the
 * front lens. A corpus shot must never change lens mid-sequence: the two
 * cameras differ in field of view, colour rendering and resolution, and the
 * same-bottle check compares exactly those properties across a pair.
 */
@Composable
private fun CorpusCameraView(onCaptured: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    androidx.compose.runtime.LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            provider.unbindAll()
            provider.bindCamera(lifecycleOwner, useFrontCamera = false, preview, imageCapture)
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PreviewView(ctx).also { previewView = it } }
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
                            // Preview stays up; the shot is simply retaken.
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Text("Record shot")
        }
    }
}
