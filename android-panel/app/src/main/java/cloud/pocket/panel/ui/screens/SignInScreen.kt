package cloud.pocket.panel.ui.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.R
import cloud.pocket.panel.data.ApiException
import cloud.pocket.panel.data.PocketApi
import cloud.pocket.panel.data.Session
import cloud.pocket.panel.data.body
import cloud.pocket.panel.ui.theme.PocketBlue
import kotlinx.coroutines.launch

/** First screen: the panel address and the admin password (or setting the password on first use). */
@Composable
fun SignInScreen(session: Session, onSignedIn: () -> Unit) {
    var address by remember { mutableStateOf(session.address.ifBlank { "http://pocket:8000" }) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var setup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val device = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                val api = PocketApi(address.trim(), null)
                val status = api.get("/api/status")
                if (!status.b("password_set") && !setup) {
                    setup = true
                    error = "This Pocket CloudServer has no admin password yet. Choose one (at least 8 characters)."
                    return@launch
                }
                if (setup && password != again) {
                    error = "The two passwords are different."
                    return@launch
                }
                val r = api.post(if (setup) "/api/setup" else "/api/login", body("password" to password, "device" to device))
                session.address = address.trim()
                session.token = r.s("token")
                onSignedIn()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Image(painterResource(R.drawable.pocket_logo), contentDescription = null, modifier = Modifier.size(72.dp))
            Text("Pocket CloudServer", style = MaterialTheme.typography.headlineMedium, color = PocketBlue)
            Text(if (setup) "Choose the admin password for the control panel." else "Sign in to your control panel.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text("Control panel address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Admin password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (setup) OutlinedTextField(again, { again = it }, label = { Text("Password again") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = ::submit, enabled = !busy && address.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text(if (setup) "Set password" else "Sign in") }
            }
        }
    }
}
