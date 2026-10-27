package moe.desupon.feature.subscriptions.qr

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import moe.desupon.feature.subscriptions.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen camera QR scanner; the caller must already hold the camera permission. */
@Composable
internal fun QrScannerDialog(onResult: (String) -> Unit, onError: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            QrCameraPreview(onResult = onResult, onError = onError, modifier = Modifier.fillMaxSize())
            Text(
                text = stringResource(R.string.subs_qr_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 24.dp),
            )
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 32.dp),
            ) { Text(stringResource(R.string.subs_close)) }
        }
    }
}

@Composable
private fun QrCameraPreview(onResult: (String) -> Unit, onError: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val bound = remember { BoundCamera() }

    LaunchedEffect(lifecycleOwner) {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentOnError()
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        analysis.setAnalyzer(executor) { image ->
            val text = image.use(::decodeFrame)
            if (text != null && delivered.compareAndSet(false, true)) {
                mainExecutor.execute { currentOnResult(text) }
            }
        }
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            bound.provider = provider
            bound.useCases = listOf(preview, analysis)
        } catch (_: Exception) {
            currentOnError()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            bound.provider?.unbind(*bound.useCases.toTypedArray())
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private class BoundCamera {
    var provider: ProcessCameraProvider? = null
    var useCases: List<UseCase> = emptyList()
}

private fun decodeFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val bytes = ByteArray(plane.rowStride * image.height)
    buffer.get(bytes, 0, minOf(buffer.remaining(), bytes.size))
    return QrDecoder.decodeLuminance(bytes, plane.rowStride, image.width, image.height)
}
