package com.ogenrwotdaniel.wakeproof.clip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
import com.ogenrwotdaniel.wakeproof.ui.theme.WakeProofTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Where the pairing screen is. */
private sealed interface PairState {
    data object Scanning : PairState
    data class Pairing(val computer: String) : PairState
    data class Done(val computer: String) : PairState
    data class Failed(val message: String) : PairState
}

/**
 * "Pair a computer": scans the `wpclip1:` QR the laptop prints (`clipsync pair`)
 * with ML Kit, then runs the pairing exchange over LAN (port 47322) via
 * [ClipHub.pair]. Needs the phone and the laptop on one network, once.
 */
@AndroidEntryPoint
class ClipPairActivity : ComponentActivity() {

    @Inject lateinit var hub: ClipHub
    @Inject lateinit var accounts: AccountRepository

    private var state by mutableStateOf<PairState>(PairState.Scanning)
    private val claimed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WakeProofTheme {
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    PairScreen(
                        state = state,
                        modifier = Modifier.padding(padding),
                        onCode = ::onCode,
                        onRetry = {
                            claimed.set(false)
                            state = PairState.Scanning
                        },
                        onClose = ::finish
                    )
                }
            }
        }
    }

    private fun onCode(raw: String) {
        val offer = ClipCodec.parseQr(raw) ?: return
        if (!claimed.compareAndSet(false, true)) return
        state = PairState.Pairing(offer.name)
        lifecycleScope.launch {
            // Signed out (or the sheet refused) → null: the laptop then pairs LAN-only.
            val token = if (accounts.currentUid() != null) {
                accounts.idTokenForCompanion(this@ClipPairActivity).getOrNull()
            } else {
                null
            }
            state = hub.pair(offer, token).fold(
                onSuccess = { PairState.Done(it.name) },
                onFailure = {
                    Log.w(TAG, "pairing failed", it)
                    PairState.Failed(
                        "Couldn't reach ${offer.name}: ${it.message ?: it.javaClass.simpleName}. " +
                            "Are both on the same Wi-Fi, and is `clipsync pair` still waiting?"
                    )
                }
            )
        }
    }

    private companion object {
        const val TAG = "ClipPair"
    }
}

@Composable
private fun PairScreen(
    state: PairState,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Constrain first, then fill into it: a widthIn after fillMaxWidth is ignored.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Pair a computer", style = MaterialTheme.typography.headlineMedium)
            when (state) {
                PairState.Scanning -> {
                    Text(
                        "On the laptop run `clipsync pair`, then point the camera at the QR code it prints. " +
                            "Both devices must be on the same network for this one step.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Scanner(onCode)
                    OutlinedButton(onClick = onClose) { Text("Cancel") }
                }
                is PairState.Pairing -> {
                    CircularProgressIndicator()
                    Text("Pairing with ${state.computer}…")
                }
                is PairState.Done -> {
                    Text(
                        "Paired with ${state.computer}. Copy something on either side and it arrives on the other.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = onClose) { Text("Done") }
                }
                is PairState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("Scan again") }
                    OutlinedButton(onClick = onClose) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun Scanner(onCode: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Text("The camera is needed to read the QR code.", color = MaterialTheme.colorScheme.error)
        return
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        factory = { ctx -> PreviewView(ctx).also { previewView = it } }
    )
    val view = previewView ?: return
    DisposableEffect(view) {
        val executor = Executors.newSingleThreadExecutor()
        com.ogenrwotdaniel.wakeproof.verify.MlKitInit.ensure(view.context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, QrAnalyzer(scanner, onCode)) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { Log.w("ClipPair", "camera bind failed", it) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
}

private class QrAnalyzer(
    private val scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: androidx.camera.core.ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.startsWith(ClipCodec.QR_PREFIX) } }
                    ?.let(onCode)
            }
            .addOnCompleteListener { image.close() }
    }
}
