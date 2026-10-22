package app.daybook.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.daybook.data.LibraryRepository.ScanResult
import app.daybook.data.MealEntity
import app.daybook.scan.CodeScanner
import app.daybook.scan.PRODUCT_FORMATS
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch

/**
 * Scans a package barcode and looks it up. Hands back a meal already in the library ([onKnown]) or
 * a pre-filled one from Open Food Facts to check and save ([onFound]); otherwise says why not.
 */
@Composable
fun BarcodeScanDialog(
    scan: suspend (String, Boolean) -> ScanResult,
    onKnown: (MealEntity) -> Unit,
    onFound: (MealEntity) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA) }

    var looking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Scan barcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !hasCamera -> Text("Diurna needs the camera to scan.")
                    problem != null -> Text(problem!!)
                    looking -> Text("Looking up…")
                    else -> {
                        CodeScanner(
                            PRODUCT_FORMATS,
                            onCode = { code, format ->
                                // The first read wins; the camera keeps reporting the same code.
                                if (!looking) {
                                    looking = true
                                    scope.launch {
                                        when (val r = scan(code, format == Barcode.FORMAT_UPC_E)) {
                                            is ScanResult.Known -> onKnown(r.meal)
                                            is ScanResult.Found -> onFound(r.draft)
                                            ScanResult.NotFound -> problem = "Not found."
                                            is ScanResult.Failed -> problem = r.message
                                        }
                                        looking = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                            torch = torch,
                        )
                        Hint("Hold the barcode inside the frame.")
                    }
                }
            }
        },
        confirmButton = {
            when {
                problem != null -> TextButton(onClick = { problem = null }) { Text("Scan again") }
                hasCamera && !looking -> TextButton(onClick = { torch = !torch }) { Text(if (torch) "Light off" else "Light on") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
