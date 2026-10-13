package app.daybook.alarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.daybook.container
import app.daybook.data.hhmm
import app.daybook.scan.QrScanner
import app.daybook.ui.theme.DaybookTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Shown over the lock screen while the alarm rings. The only way out is the porch code. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) { /* no escaping by back button */ }

        val c = container
        setContent {
            DaybookTheme {
                val ring by AlarmService.ringing.collectAsStateWithLifecycle()
                LaunchedEffect(ring) { if (ring == null) finish() }
                val current = ring ?: return@DaybookTheme
                var stopping by remember { mutableStateOf(false) }

                AlarmScreen(
                    test = current.test,
                    hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                    onCode = { code ->
                        lifecycleScope.launch {
                            if (stopping) return@launch
                            if (code != c.settings.secret()) return@launch
                            stopping = true
                            val date = current.date
                            if (!current.test && date != null) {
                                c.days.recordMorning(date, LocalDateTime.now().hhmm(), "scan")
                            }
                            stop()
                        }
                    },
                    onStopTest = { stop() },
                )
            }
        }
    }

    private fun stop() {
        startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        finish()
    }
}

@Composable
private fun AlarmScreen(test: Boolean, hasCamera: Boolean, onCode: (String) -> Unit, onStopTest: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var torch by remember { mutableStateOf(false) }
    var lastWrong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(1000) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(now.hhmm(), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text(
                if (test) "Test alarm. Scan your porch code, or stop it below."
                else "Go outside and scan your porch code.",
                style = MaterialTheme.typography.titleMedium,
            )
            if (hasCamera) {
                QrScanner(
                    torch = torch,
                    onCode = { code ->
                        // A matching code closes this screen, so the hint only stays up for wrong codes.
                        lastWrong = true
                        onCode(code)
                    },
                    // Takes whatever height is left, so the buttons below always fit.
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { torch = !torch }) { Text(if (torch) "Light off" else "Light on") }
                }
                if (lastWrong) Text("Point it at the porch code. Other codes won't stop the alarm.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Camera permission is off, so the porch code can't be scanned. Grant it in Daybook's Setup tab.")
            }
            if (test) Button(onClick = onStopTest) { Text("Stop test") }
        }
    }
}
