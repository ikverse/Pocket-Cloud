package cloud.pocket.drive.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.pocket.drive.R
import cloud.pocket.drive.data.AppGraph
import cloud.pocket.drive.data.Remote
import cloud.pocket.drive.data.SHARED
import cloud.pocket.drive.data.Settings
import kotlinx.coroutines.launch

/** First start: the server's address and enrollment secret (from the control panel's Settings page). */
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.pocket_logo), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Pocket Drive", style = MaterialTheme.typography.headlineMedium)
        Text("Your files on your own Pocket Cloud. Connect this phone to the server.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
        OutlinedTextField(address, { address = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Usually pocket:9000. Tailscale must be on.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        OutlinedTextField(secret, { secret = it }, label = { Text("Enrollment secret") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            supportingText = { Text("From the control panel: Settings, Machine setup.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
        OutlinedTextField(name, { name = it }, label = { Text("This device's name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::connect, enabled = !busy && address.isNotBlank() && secret.isNotBlank()) { Text("Connect") }
            androidx.compose.material3.OutlinedButton(onClick = { scanning = true }, modifier = Modifier.padding(start = 12.dp)) { Text("Scan QR code") }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 16.dp).size(24.dp), strokeWidth = 2.dp)
        }
        Text("The address will be used as ${Settings.normalizeAddress(address)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }
}
