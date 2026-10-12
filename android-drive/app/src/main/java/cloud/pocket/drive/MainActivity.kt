package cloud.pocket.drive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cloud.pocket.drive.ui.DriveApp
import cloud.pocket.drive.ui.theme.DriveTheme
import cloud.pocket.drive.update.Updates

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Speaks only when a newer build exists (or one is already downloaded); silent otherwise.
        if (savedInstanceState == null) Updates.of(this).checkQuietly()
        val open = intent?.getStringExtra("open")
        setContent {
            DriveTheme {
                DriveApp(open)
            }
        }
    }
}
