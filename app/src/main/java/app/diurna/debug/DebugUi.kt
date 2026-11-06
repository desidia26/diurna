package app.diurna.debug

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Intent
import android.graphics.ImageDecoder
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.AppContainer
import app.diurna.alarm.AlarmActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun DebugCard(debug: DebugLog) {
    val enabled by debug.enabled.collectAsStateWithLifecycle()
    val includeText by debug.includeText.collectAsStateWithLifecycle()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Debug reports", style = MaterialTheme.typography.titleMedium)
            Toggle("Record debug actions", enabled) { debug.configure(it, includeText) }
            Text("Off by default. Private actions and captured photos/barcodes stay on this phone: up to 20 MB, 7 days. Turning off clears them. Nothing uploads automatically.")
            Toggle("Include typed field text", includeText, enabled) { debug.configure(enabled, it) }
            Text("Text is off by default. Initially records submitted food text, not every field or keystroke. Keys and credentials are never intentionally recorded. Photos can contain private information; review before sharing.")
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** Sits above the tab content (and independently in AlarmActivity); never dismisses an alarm. */
@Composable
fun DebugHost(c: AppContainer, screen: Screen, content: @Composable () -> Unit) {
    val enabled by c.debug.enabled.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context = context
        while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
        c as? Activity
    }
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { open = false }
    Box(Modifier.fillMaxSize()) {
        content()
        if (enabled) TextButton(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding(),
            onClick = {
                if (keyguard.isKeyguardLocked && activity != null) {
                    activity.requestDismissKeyguard(object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() { if (!keyguard.isDeviceLocked) open = true }
                    })
                } else if (!keyguard.isDeviceLocked) open = true
            },
        ) { Text("Flag failure") }
    }
    if (enabled && open) {
        // A ringing activity is lock-screen-visible; a report must not be.
        DisposableEffect(activity) {
            val secureAlready = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (activity is AlarmActivity) activity.setShowWhenLocked(false)
            onDispose {
                if (!secureAlready) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (activity is AlarmActivity) activity.setShowWhenLocked(true)
            }
        }
        ReportDialog(c, screen) { open = false }
    }
}

@Composable
private fun ReportDialog(c: AppContainer, screen: Screen, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actions by remember { mutableStateOf<List<String>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<DebugAttachment>>(emptyList()) }
    var removed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var includeLog by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val capturedAt = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        try {
            val snapshot = withContext(Dispatchers.IO) { c.debug.flush(); c.debug.recorder.snapshot() to c.debug.recorder.attachments() }
            actions = snapshot.first; attachments = snapshot.second
        } catch (_: Exception) { problem = "Couldn't load the debug log. Try again." }
        loading = false
    }
    Dialog(onDismissRequest = { if (!sharing) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review failure report", style = MaterialTheme.typography.titleLarge)
                Text("Screen: ${screen.name}. Images are NOT redacted. Remove anything private. Sharing opens Android's chooser; nothing is sent automatically.")
                OutlinedTextField(note, { note = it.take(4000) }, label = { Text("What went wrong? (optional)") }, modifier = Modifier.fillMaxWidth())
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Toggle("Include action log (${actions.size} entries)", includeLog) { includeLog = it }
                        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log preview" else "Preview action log") }
                    }
                    if (showLog) items(actions) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    items(attachments, key = { it.id }) { a ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(a.id !in removed, onCheckedChange = { checked -> removed = if (checked) removed - a.id else removed + a.id })
                                Text("${a.kind.name.lowercase()} · ${a.file.length() / 1024} KB", Modifier.weight(1f))
                                TextButton(onClick = { preview = if (preview == a.id) null else a.id }) { Text("Preview") }
                            }
                            if (preview == a.id) AttachmentPreview(a)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss, enabled = !sharing) { Text("Cancel") }
                    Button(enabled = !loading && !sharing && problem == null, onClick = {
                        sharing = true
                        val reviewedActions = if (includeLog) actions.toList() else emptyList()
                        val reviewedAttachments = attachments.filter { it.id !in removed }
                        val reviewedNote = note
                        scope.launch {
                            var out: File? = null
                            try {
                                val keyguard = context.getSystemService(KeyguardManager::class.java)
                                check(!keyguard.isDeviceLocked && c.debug.enabled.value)
                                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                                val result = withContext(Dispatchers.IO) {
                                    c.debug.reportsDir.mkdirs()
                                    c.debug.reportsDir.listFiles()?.forEach { it.delete() }
                                    val file = File(c.debug.reportsDir, "failure-${UUID.randomUUID()}.zip")
                                    out = file
                                    val data = ReportData(info.versionName ?: "unknown", info.longVersionCode,
                                        "${Build.MANUFACTURER} ${Build.MODEL}", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                        screen, capturedAt, Redaction.text(reviewedNote, c.debug.recorder.knownSecrets()))
                                    DebugReport.write(file, data, reviewedActions, reviewedAttachments)
                                    file
                                }
                                check(!keyguard.isDeviceLocked && c.debug.enabled.value)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", result)
                                val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.clipData = ClipData.newRawUri("Failure report", uri)
                                context.startActivity(Intent.createChooser(intent, "Share failure report"))
                                dismiss()
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) { out?.delete(); throw e }
                            catch (_: Exception) { out?.delete(); problem = "Couldn't prepare or share the report. Close and try again." }
                            finally { sharing = false }
                        }
                    }) { Text(if (sharing) "Preparing…" else "Share ZIP…") }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(a: DebugAttachment) {
    if (a.kind == AttachmentKind.BARCODE) {
        val digits by produceState("") { value = withContext(Dispatchers.IO) { runCatching { a.file.readText().take(14) }.getOrDefault("Unavailable") } }
        Text(digits)
    } else {
        val bitmap by produceState<android.graphics.Bitmap?>(null, a.id) {
            value = withContext(Dispatchers.IO) { runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(a.file)) { decoder, info, _ ->
                    val scale = 320.0 / maxOf(info.size.width, info.size.height).coerceAtLeast(320)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull() }
        }
        bitmap?.let { Image(it.asImageBitmap(), "Captured image preview", Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            ?: Text("Preview unavailable (image may be unreadable)")
    }
}
