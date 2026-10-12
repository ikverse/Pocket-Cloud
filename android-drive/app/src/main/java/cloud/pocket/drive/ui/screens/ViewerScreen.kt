package cloud.pocket.drive.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cloud.pocket.drive.data.Entry
import cloud.pocket.drive.ui.components.ErrorBox
import cloud.pocket.drive.ui.components.LoadingBox
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ViewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT }

object Viewer {
    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val videos = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v")
    private val audio = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")
    private val text = setOf("txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "ini", "conf", "kt", "py", "js", "html", "css", "sh", "tsv", "toml", "java", "c", "h", "cpp")
    const val TEXT_LIMIT = 1_000_000L

    /** How a file of this name is shown inside the app, or null when it is only opened by another app. */
    fun kind(name: String, size: Long = 0): ViewKind? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            in images -> ViewKind.IMAGE
            in videos -> ViewKind.VIDEO
            in audio -> ViewKind.AUDIO
            "pdf" -> ViewKind.PDF
            in text -> if (size <= TEXT_LIMIT) ViewKind.TEXT else null
            else -> null
        }
    }
}

/** Shows one file inside the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(model: BrowseModel, entry: Entry, kind: ViewKind, onClose: () -> Unit, onShare: () -> Unit, onDownload: () -> Unit) {
    BackHandler { onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                actions = {
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Download") }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize().background(if (kind == ViewKind.IMAGE || kind == ViewKind.VIDEO) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surface)) {
            when (kind) {
                ViewKind.VIDEO, ViewKind.AUDIO -> MediaPlayer(model, entry)
                else -> FileViewer(model, entry, kind)
            }
        }
    }
}

/** Kinds that need the whole file first: it is fetched, then shown. */
@Composable
private fun FileViewer(model: BrowseModel, entry: Entry, kind: ViewKind) {
    var progress by remember { mutableStateOf(0L) }
    val state by produceState<Result<File>?>(null, entry.key) {
        value = runCatching { model.fetch(entry) { progress = it } }
    }
    val result = state
    when {
        result == null -> Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("${progress * 100 / entry.size.coerceAtLeast(1)}%", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
        result.isFailure -> ErrorBox(cloud.pocket.drive.data.Remote.explain(result.exceptionOrNull()!!))
        else -> {
            val file = result.getOrThrow()
            when (kind) {
                ViewKind.IMAGE -> ZoomImage(file)
                ViewKind.PDF -> PdfPages(file)
                else -> TextBody(file)
            }
        }
    }
}

@Composable
private fun ZoomImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    AsyncImage(
        model = file, contentDescription = file.name, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale == 1f) { offsetX = 0f; offsetY = 0f } else { offsetX += pan.x; offsetY += pan.y }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
    )
}

@Composable
private fun TextBody(file: File) {
    val text by produceState("", file) { value = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("This file can't be read as text.") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PdfPages(file: File) {
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val renderer by produceState<PdfRenderer?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    }
    val lock = remember { Mutex() }
    val r = renderer
    DisposableEffect(r) { onDispose { runCatching { r?.close() } } }       // r, not the state: the one that was current when this started
    if (r == null) { LoadingBox(); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(r.pageCount) { index ->
            val bitmap by produceState<Bitmap?>(null, index) {
                value = lock.withLock {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            r.openPage(index).use { page ->
                                val height = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }.getOrNull()
                    }
                }
            }
            val b = bitmap
            if (b != null) Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            else Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

/** Plays video or audio straight from the server through a temporary link. */
@Composable
private fun MediaPlayer(model: BrowseModel, entry: Entry) {
    val context = LocalContext.current
    val url by produceState<Result<String>?>(null, entry.key) { value = runCatching { model.playableSource(entry) } }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(url) { url?.getOrNull()?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare(); player.playWhenReady = true } }
    val r = url
    when {
        r == null -> LoadingBox()
        r.isFailure -> ErrorBox(cloud.pocket.drive.data.Remote.explain(r.exceptionOrNull()!!))
        else -> AndroidView(
            factory = { c -> PlayerView(c).apply { this.player = player; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
