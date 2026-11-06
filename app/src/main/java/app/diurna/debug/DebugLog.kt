package app.diurna.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Android settings and a FIFO disk writer; application state stays outside this store. */
class DebugLog(context: Context) {
    val reportsDir = File(context.cacheDir, "debug-reports")
    private val prefs = context.getSharedPreferences("debug-log", Context.MODE_PRIVATE)
    val recorder = ActionRecorder(File(context.noBackupFilesDir, "debug-log"))
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(256), ThreadPoolExecutor.AbortPolicy())
    @Volatile private var generation = 0L
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()
    private val _includeText = MutableStateFlow(prefs.getBoolean("text", false) && _enabled.value)
    val includeText = _includeText.asStateFlow()
    private val _screen = MutableStateFlow(Screen.DAY)
    val currentScreen = _screen.asStateFlow()
    val actions = Store(listOf(Middleware { a, next ->
        if (a is Action.Event && (a.name == "navigation.tab" || a.name.startsWith("setup.page."))) _screen.value = a.screen
        val epoch = generation
        if (_enabled.value && (a !is Action.Text || _includeText.value)) writer.execute {
            if (epoch == generation) recorder.store.dispatch(a)
        }
        next(a)
    }))
    init { recorder.enabled = _enabled.value; recorder.includeTypedText = _includeText.value; writer.execute { recorder.trim(); reportsDir.listFiles()?.forEach { it.delete() } } }
    fun configure(enabled: Boolean, includeText: Boolean) {
        generation++
        _enabled.value = enabled; _includeText.value = includeText && enabled
        prefs.edit().putBoolean("enabled", enabled).putBoolean("text", _includeText.value).apply()
        recorder.enabled = enabled; recorder.includeTypedText = _includeText.value
        if (!enabled) reportsDir.listFiles()?.forEach { it.delete() }
    }
    fun flush() { writer.submit {}.get(5, TimeUnit.SECONDS) }
    fun registerAttachment(bytes: ByteArray, kind: AttachmentKind, meta: Map<String, String> = emptyMap(),
        correlationId: String = java.util.UUID.randomUUID().toString()): String? = recorder.registerAttachment(bytes, kind, meta, correlationId)
    fun registerAttachment(file: File, kind: AttachmentKind, meta: Map<String, String> = emptyMap(),
        correlationId: String = java.util.UUID.randomUUID().toString()): String? = recorder.registerAttachment(file, kind, meta, correlationId)
}
