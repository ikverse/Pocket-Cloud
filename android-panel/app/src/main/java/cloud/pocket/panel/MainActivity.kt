package cloud.pocket.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cloud.pocket.panel.ui.PocketApp
import cloud.pocket.panel.ui.theme.PocketCloudTheme
import cloud.pocket.panel.update.Updates

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Speaks only when a newer build exists (or one is already downloaded); silent otherwise.
        if (savedInstanceState == null) Updates.of(this).checkQuietly()
        val open = intent?.getStringExtra("open")      // "alerts" when opened from an alert notification
        setContent {
            PocketCloudTheme {
                PocketApp(open)
            }
        }
    }
}
