package app.diurna

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.diurna.ui.theme.DiurnaTheme

/**
 * The privacy note Health Connect shows from its permission screens ("Read privacy policy").
 * Health Connect requires one; it's static text and reads nothing.
 */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DiurnaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("How Diurna uses Health Connect", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Diurna only reads. It never writes to Health Connect.")
                        Text("It reads sleep sessions and stages, heart rate, heart rate variability, and, when a device writes them, breathing rate, blood oxygen and resting heart rate.")
                        Text("It uses them to show last night's sleep next to your alarm, porch scan and workouts, and in Metrics.")
                        Text("Everything stays on this phone, with your other Diurna data and in the backups you make. Nothing is uploaded or shared.")
                        Text("You can take access back at any time in Health Connect.")
                        TextButton(onClick = ::finish) { Text("Close") }
                    }
                }
            }
        }
    }
}
