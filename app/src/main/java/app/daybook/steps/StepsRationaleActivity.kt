package app.daybook.steps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.daybook.ui.theme.DaybookTheme

/** Health Connect requires every app that reads health data to explain why; it opens this screen. */
class StepsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DaybookTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("How Daybook uses your steps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Daybook reads your daily step count from Health Connect to show progress toward your step goal " +
                                "on the Day screen, in the Week view and on the lock screen, and saves each day's total in its own history."
                        )
                        Text("It only reads steps. It never writes to Health Connect, and the counts stay on this phone.")
                        Button(onClick = { finish() }) { Text("OK") }
                    }
                }
            }
        }
    }
}
