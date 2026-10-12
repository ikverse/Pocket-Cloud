package cloud.pocket.drive.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** What a setup QR code holds: the server address and the enrollment secret. */
data class QrSetup(val endpoint: String, val secret: String)

object QrPayload {
    /**
     * Read a setup code. Accepted forms:
     *  - the "machine setup" text of the control panel (an [pocket] section with endpoint and enroll_secret),
     *  - JSON with endpoint and secret (or enroll_secret),
     *  - a pocket://enroll?endpoint=...&secret=... address.
     */
    fun parse(text: String): QrSetup? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) {
            val j = runCatching { org.json.JSONObject(t) }.getOrNull() ?: return null
            val ep = j.optString("endpoint")
            val sec = j.optString("secret").ifBlank { j.optString("enroll_secret") }
            return if (ep.isNotBlank() && sec.isNotBlank()) QrSetup(ep, sec) else null
        }
        if (t.startsWith("pocket://")) {
            val q = t.substringAfter('?', "")
            val map = q.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
            val ep = map["endpoint"].orEmpty()
            val sec = map["secret"].orEmpty().ifBlank { map["enroll_secret"].orEmpty() }
            return if (ep.isNotBlank() && sec.isNotBlank()) QrSetup(ep, sec) else null
        }
        var ep = ""
        var sec = ""
        for (line in t.lines()) {
            val i = line.indexOfFirst { it == '=' || it == ':' && !line.trimStart().startsWith("http") }
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k == "endpoint") ep = v
            if (k == "enroll_secret" || k == "secret") sec = v
        }
        return if (ep.isNotBlank() && sec.isNotBlank()) QrSetup(ep, sec) else null
    }
}

/** A camera view that reads one QR code and reports it. */
@Composable
fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize()) {
        if (granted) CameraPreview(onResult)
        else Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("The camera is needed to scan the code.")
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Point the camera at the code in the control panel's Settings page.", color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
                analysis.setAnalyzer(executor) { image ->
                    val text = decode(reader, image)
                    image.close()
                    if (text != null && !done) { done = true; view.post { onResult(text) } }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val rowStride = plane.rowStride
    val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width.coerceAtMost(rowStride), image.height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
