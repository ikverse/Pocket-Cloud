package cloud.pocket.drive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.data.AppGraph
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.Settings
import cloud.pocket.drive.ui.components.Monogram
import cloud.pocket.drive.ui.components.PrimaryButton
import cloud.pocket.drive.ui.theme.PocketTheme
import kotlinx.coroutines.launch

/** First start: scan the control panel's setup code, or type the address and enrollment secret. */
@Composable
fun SetupScreen(g: AppGraph, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(g.settings.address.ifBlank { "pocket:9000" }) }
    var secret by remember { mutableStateOf(g.settings.secret ?: "") }
    var name by remember { mutableStateOf(g.settings.deviceName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun connect() {
        busy = true
        error = null
        scope.launch {
            try {
                g.settings.address = address
                g.settings.secret = secret.trim()
                g.settings.deviceName = name.ifBlank { android.os.Build.MODEL }
                g.settings.endpointInUse = ""
                g.settings.enrolled = false
                g.remote.reset()
                g.remote.connect()
                g.remote.list(SHARED, "")      // proves the key works
                onDone()
            } catch (e: Exception) {
                g.settings.enrolled = false
                error = Remote.explain(e)
            } finally {
                busy = false
            }
        }
    }

    if (scanning) {
        QrScanner(
            onResult = { text ->
                scanning = false
                val setup = QrPayload.parse(text)
                if (setup == null) error = "That code isn't a Pocket Cloud setup code."
                else { address = setup.endpoint; secret = setup.secret; error = null }
            },
            onCancel = { scanning = false })
        return
    }
    val c = MaterialTheme.colorScheme
    val line = c.outlineVariant
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Monogram(56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Connect to your Pocket Cloud", style = MaterialTheme.typography.headlineSmall)
                Text("Open the control panel on your PC, go to Settings, and show the setup QR code.", style = MaterialTheme.typography.bodyLarge,
                    color = c.onSurfaceVariant)
            }
            val ready = secret.isNotBlank() && address.isNotBlank()
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (ready) 2.4f else 1.15f).clip(RoundedCornerShape(26.dp)).background(c.surfaceContainerLow)
                    .drawBehind {
                        drawRoundRect(line, cornerRadius = CornerRadius(26.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))))
                    }
                    .clickable { scanning = true },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(44.dp), tint = c.primary)
                    Text(if (ready) "Scan again" else "Scan the QR code", style = MaterialTheme.typography.titleSmall)
                    Text("Tap to open the camera", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            Text("or type it in", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                supportingText = { Text("For example pocket:9000. Used as ${Settings.normalizeAddress(address)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            OutlinedTextField(secret, { secret = it }, label = { Text("Enrollment secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                supportingText = { Text("From the control panel: Settings, Machine setup. The QR code fills it in.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
            OutlinedTextField(name, { name = it }, label = { Text("This device's name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            if (error != null) Text(error!!, style = MaterialTheme.typography.bodyMedium, color = PocketTheme.extra.bad)
            Box(contentAlignment = Alignment.Center) {
                PrimaryButton(if (busy) "Connecting…" else "Connect", ::connect, Modifier.fillMaxWidth(), enabled = !busy && ready)
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(20.dp), strokeWidth = 2.dp, color = c.onPrimary)
            }
            Text("Tailscale must be on. This phone gets its own key, which works only from this phone.", style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant)
        }
    }
}
