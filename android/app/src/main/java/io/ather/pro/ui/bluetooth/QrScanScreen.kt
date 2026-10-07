package io.ather.pro.ui.bluetooth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Full-screen camera scanner for the QR shown on the scooter's dashboard. Decodes
 * with the embedded ZXing core over CameraX luma frames; reports the first QR text
 * and then stops analysing.
 */
@Composable
fun QrScanScreen(onDecoded: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraWithDecoder(onDecoded = onDecoded)
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera needed to scan the pairing QR", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The QR is shown on the scooter's dashboard during pairing.",
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White)
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
        ) {
            Text(
                "Scan the QR on the scooter's dashboard",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun CameraWithDecoder(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerState = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val decoded = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose {
            providerState.value?.unbindAll()
            executor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        providerState.value = suspendCancellableCoroutine { cont ->
            future.addListener({
                runCatching { cont.resume(future.get()) }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    providerState.value?.let { provider ->
        LaunchedEffect(provider) {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                if (decoded.get()) { image.close(); return@setAnalyzer }
                val text = decodeQr(image)
                image.close()
                if (text != null && decoded.compareAndSet(false, true)) {
                    mainHandler.post { onDecoded(text) }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = minOf(maxWidth, maxHeight) * 0.68f
        Box(
            Modifier.align(Alignment.Center)
                .size(frame)
                .clip(RoundedCornerShape(28.dp))
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(28.dp))
        )
    }
}

/** Y-plane luma extraction, then decode via [QrDecoder] (handles inverted QRs). */
private fun decodeQr(image: ImageProxy): String? {
    return try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride
        val data = if (pixelStride == 1 && stride == width) {
            ByteArray(width * height).also { out -> buffer.get(out, 0, width * height) }
        } else {
            ByteArray(width * height).also { out ->
                for (row in 0 until height) {
                    buffer.position(row * stride)
                    for (col in 0 until width) {
                        out[row * width + col] = buffer.get(row * stride + col * pixelStride)
                    }
                }
            }
        }
        io.ather.pro.ble.QrDecoder.decode(data, width, height)
    } catch (_: Exception) {
        null
    }
}

private fun hasCamera(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
