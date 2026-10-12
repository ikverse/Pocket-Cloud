package cloud.pocket.panel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.data.ApiException
import cloud.pocket.panel.data.PocketApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What every screen needs: the API, a way to report outcomes, and navigation. */
class Panel(
    val api: PocketApi,
    val snackbar: SnackbarHostState,
    val scope: CoroutineScope,
    val open: (String) -> Unit,
    val back: () -> Unit,
    val signedOut: () -> Unit,
) {
    /** Run an action; show its outcome (or error) in the snackbar. Returns true on success. */
    fun act(done: String?, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                done?.let { snackbar.showSnackbar(it) }
                onDone()
            } catch (e: ApiException) {
                if (e.status == 401) signedOut() else snackbar.showSnackbar(e.message ?: "Something went wrong.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {           // files on the phone itself: can't open, can't write…
                snackbar.showSnackbar(e.message ?: "Something went wrong.")
            }
        }
    }
}

val LocalPanel = compositionLocalOf<Panel> { error("no panel") }

/** Data loaded from the API, reloadable, with loading and error states. */
class Load<T>(initial: T?) {
    var data by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var version by mutableIntStateOf(0)
    fun reload() { version++ }
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, loader: suspend PocketApi.() -> T): Load<T> {
    val panel = LocalPanel.current
    val state = remember(*keys) { Load<T>(null) }
    LaunchedEffect(state, state.version) {
        state.loading = true
        try {
            state.data = panel.api.loader()
            state.error = null
        } catch (e: ApiException) {
            if (e.status == 401) panel.signedOut() else state.error = e.message
        } finally {
            state.loading = false
        }
    }
    return state
}

/** A screen: top app bar (with back when it's a sub-page), pull to refresh, snackbar and an optional button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PocketScreen(
    title: String,
    load: Load<T>,
    showBack: Boolean = false,
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: LazyListScope.(T) -> Unit,
) {
    val panel = LocalPanel.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (showBack) IconButton(onClick = panel.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = { actions() },
            )
        },
        snackbarHost = { SnackbarHost(panel.snackbar) },
        floatingActionButton = fab,
    ) { inner ->
        PullToRefreshBox(isRefreshing = load.loading && load.data != null, onRefresh = { load.reload() },
            modifier = Modifier.padding(inner).fillMaxSize()) {
            val data = load.data
            when {
                data != null -> LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { content(data) }
                load.error != null -> ErrorBox(load.error!!) { load.reload() }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
fun ErrorBox(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = retry) { Text("Try again") }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp, start = 4.dp))
}

/** A titled card holding list rows. */
@Composable
fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            content()
        }
    }
}

@Composable
fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** Two stat cards side by side. */
@Composable
fun StatRow(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        a(Modifier.weight(1f))
        b(Modifier.weight(1f))
    }
}

/** Colour for a use level: error at 100%, tertiary from the warning level, primary below. */
@Composable
fun levelColor(pct: Double, warn: Double = 80.0): Color = when {
    pct >= 100 -> MaterialTheme.colorScheme.error
    pct >= warn -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun UsageBar(pct: Double, warn: Double = 80.0, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth(),
        color = levelColor(pct, warn))
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String = "", confirm: String = "Save", password: Boolean = false,
                    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text))
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small "Save" button row at the end of a form. */
@Composable
fun SaveRow(saving: Boolean = false, label: String = "Save", onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
        Button(onClick = onSave, enabled = !saving) { Text(label) }
    }
}

fun Modifier.gap() = this.width(8.dp)

/** Launch a coroutine from a click. */
@Composable
fun rememberLauncher(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { block() } }
}
