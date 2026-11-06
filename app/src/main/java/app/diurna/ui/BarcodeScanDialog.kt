package app.diurna.ui

import app.diurna.container
import app.diurna.scan.DebugScanDiagnostics
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.MealEntity
import app.diurna.scan.ProductScanner
import app.diurna.domain.ProductCode
import app.diurna.domain.ScanOutcome
import kotlinx.coroutines.CancellationException
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
    val container = context.container
    val diagnostics = remember(container) { DebugScanDiagnostics(
        { container.debug.enabled.value }, container.debug.currentScreen.value,
        { container.actions.dispatch(it) },
        { file, kind, id -> container.debug.registerAttachment(file, kind, correlationId = id); Unit },
        { bytes, kind, id -> container.debug.registerAttachment(bytes, kind, correlationId = id); Unit },
        { pixels, id ->
            val bitmap = android.graphics.Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val bytes = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes)
                container.debug.registerAttachment(bytes.toByteArray(), app.diurna.debug.AttachmentKind.IMAGE,
                    mapOf("format" to "SHARPEN"), id)
            } finally { bitmap.recycle() }
        },
    ) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(hasCamera) { diagnostics.onEvent("permission", if (hasCamera) "granted" else "denied") }
    LaunchedEffect(Unit) { if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA) }

    var looking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<MealEntity?>(null) }
    var candidates by remember { mutableStateOf<List<ProductCode>>(emptyList()) }
    var torch by remember { mutableStateOf(false) }
    val lookup: (ProductCode) -> Unit = { code ->
        if (!looking && code.isValid()) {
            looking = true
            candidates = emptyList()
            scope.launch {
                try {
                    diagnostics.onResult("barcode", listOf(code))
                    diagnostics.onEvent("lookup", "start")
                    val started = System.nanoTime()
                    val result = scan(code.digits, code.upcE)
                    diagnostics.onEvent("lookup", when (result) {
                        is ScanResult.Known -> "known"; is ScanResult.Found -> "found"
                        is ScanResult.NotFound -> "not_found"; is ScanResult.Failed -> "failed"
                    }, latencyMs = (System.nanoTime() - started) / 1_000_000)
                    when (result) {
                        is ScanResult.Known -> onKnown(result.meal)
                        is ScanResult.Found -> onFound(result.draft)
                        is ScanResult.NotFound -> {
                            draft = result.draft.takeIf { it.barcode != null }
                            problem = "Barcode read, but this product isn't in Open Food Facts. You can add its nutrition manually."
                        }
                        is ScanResult.Failed -> {
                            draft = result.draft.takeIf { it.barcode != null }
                            problem = "Barcode read, but the product lookup failed. ${result.message} You can try again or add it manually."
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    diagnostics.onEvent("lookup", "failed")
                    problem = "Barcode read, but the product lookup couldn't connect. Check your connection and try again."
                } finally { looking = false }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Scan barcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                when {
                    !hasCamera -> Text("Diurna needs the camera to scan.")
                    problem != null -> {
                        Text(problem!!)
                        TextButton(onClick = { problem = null; draft = null }) { Text("Scan again") }
                    }
                    looking -> Text("Looking up product…")
                    candidates.isNotEmpty() -> {
                        Text("Check the printed numbers on the package, then choose the matching barcode. Text recognition can make mistakes.")
                        candidates.forEach { code ->
                            TextButton(onClick = { lookup(code) }) {
                                Text(code.digits + if (code.upcE) " (UPC-E)" else "")
                            }
                        }
                        TextButton(onClick = { candidates = emptyList() }) { Text("Retake photo") }
                    }
                    else -> ProductScanner(
                        onResult = { result ->
                            when (result) {
                                is ScanOutcome.Decoded -> lookup(result.code)
                                is ScanOutcome.Confirm -> candidates = result.codes
                                ScanOutcome.Unreadable -> problem = "Couldn't read a valid barcode after the saved photo and five retries, or from its printed digits. Try moving closer, tapping to focus, or changing the light."
                                ScanOutcome.TextModelNotReady -> problem = "Text recognition model not ready. Barcode scanning still works; try again after Google Play finishes downloading the OCR model, or capture a clearer barcode."
                            }
                        },
                        onProblem = { problem = it },
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                        torch = torch,
                        diagnostics = diagnostics,
                    )
                }
            }
        },
        confirmButton = {
            when {
                draft != null -> TextButton(onClick = { draft?.let(onFound) }) { Text("Add manually") }
                problem == null && candidates.isEmpty() && hasCamera && !looking ->
                    TextButton(onClick = { torch = !torch }) { Text(if (torch) "Light off" else "Light on") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
