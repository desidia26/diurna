package app.diurna.debug

import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Screen { DAY, WEEK, METRICS, ROUTINE, SETUP, LIBRARY, ALARM, BACKGROUND }
enum class TextField { FOOD, EXERCISE, RECIPE, SEARCH }
enum class AttachmentKind { IMAGE, BARCODE }

/** Version 1 records intent/results, never application state. No request/response payloads. */
sealed interface Action {
    val screen: Screen
    data class Event(override val screen: Screen, val name: String, val correlationId: String = UUID.randomUUID().toString(),
                     val result: Result = Result.STARTED, val latencyMs: Long? = null, val details: Map<String, String> = emptyMap()) : Action {
        init { require(name.matches(Regex("[a-z][a-z0-9_.]{0,79}"))) }
    }
    data class Text(override val screen: Screen, val field: TextField, val value: String) : Action
    enum class Result { STARTED, OK, FAILED, CANCELLED }
}

fun interface Middleware { fun invoke(action: Action, next: (Action) -> Unit) }

/** Redux middleware pipeline with an intentionally no-op reducer. Logging cannot break app actions. */
class Store(private val middleware: List<Middleware> = emptyList(), private val reducer: (Action) -> Unit = {}) {
    fun dispatch(action: Action) {
        fun next(index: Int, a: Action) {
            if (index == middleware.size) { reducer(a); return }
            var forwarded = false
            try { middleware[index].invoke(a) { forwarded = true; next(index + 1, it) } }
            catch (_: Exception) { if (!forwarded) next(index + 1, a) }
        }
        next(0, action)
    }
}

object Redaction {
    fun text(raw: String, secrets: Iterable<String> = emptyList()): String {
        var s = raw.take(4000)
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { s = s.replace(it, "[REDACTED]") }
        s = s.replace(Regex("(?i)bearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
        s = s.replace(Regex("(?i)(api[_ -]?key|authorization|password|token|secret)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        s = s.replace(Regex("daybook:porch:[A-Za-z0-9-]+"), "[REDACTED]")
        return s.replace(Regex("(?:sk-|xai-)[A-Za-z0-9_-]{8,}"), "[REDACTED]")
    }
}

data class DebugAttachment(val id: String, val kind: AttachmentKind, val file: File, val correlationId: String)

/** App-private bounded journal. All methods serialize on this instance; filenames never come from callers. */
class ActionRecorder(private val dir: File, private val maxBytes: Long = 20L * 1024 * 1024,
                     private val clock: () -> Long = System::currentTimeMillis) {
    companion object { const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 }
    @Volatile var knownSecrets: () -> Iterable<String> = { emptyList() }
    var enabled = false
        @Synchronized set(value) { field = value; if (!value) { includeTypedText = false; dir.listFiles()?.forEach { it.delete() } } }
    var includeTypedText = false
        @Synchronized set(value) { field = value && enabled }
    val store = Store(listOf(Middleware { a, next -> record(a); next(a) }))

    @Synchronized private fun record(a: Action) {
        if (!enabled) return
        if (a is Action.Text && !includeTypedText) return
        val j = JSONObject().put("version", 1).put("timestamp", clock()).put("screen", a.screen.name)
        when (a) {
            is Action.Event -> j.put("action", a.name).put("correlationId", safeId(a.correlationId))
                .put("result", a.result.name).put("latencyMs", a.latencyMs).also { json ->
                    val details = JSONObject()
                    a.details.filterKeys { it in setOf("exception", "message", "frames", "format", "checksum", "candidates", "source", "variant", "raw_candidates", "accepted_candidates", "lines", "numeric_runs", "rejected_runs") }
                        .forEach { (key, value) -> details.put(key, Redaction.text(value.take(256), knownSecrets())) }
                    if (details.length() > 0) json.put("details", details)
                }
            is Action.Text -> j.put("action", "field.text").put("field", a.field.name)
                .put("text", Redaction.text(a.value, knownSecrets()))
        }
        dir.mkdirs()
        File(dir, "${clock()}-${UUID.randomUUID()}.jsonl").writeText(j.toString() + "\n")
        trim()
    }

    /** Returns an opaque id, or null when disabled/oversize/unreadable. meta is deliberately allowlisted. */
    @Synchronized fun registerAttachment(bytes: ByteArray, kind: AttachmentKind,
        meta: Map<String, String> = emptyMap(), correlationId: String = UUID.randomUUID().toString()): String? {
        if (!enabled || bytes.isEmpty() || bytes.size > maxBytes / 2) return null
        return runCatching {
            dir.mkdirs(); val id = UUID.randomUUID().toString()
            val f = File(dir, "${clock()}-$id.${if (kind == AttachmentKind.IMAGE) "image" else "barcode"}")
            // Barcode attachments are digits only. Images are private, unredacted, and require report review.
            if (kind == AttachmentKind.BARCODE && !bytes.toString(Charsets.UTF_8).matches(Regex("[0-9]{6,14}"))) return null
            f.writeBytes(bytes)
            val j = JSONObject().put("id", id).put("kind", kind.name).put("correlationId", safeId(correlationId))
            meta["format"]?.takeIf { it.matches(Regex("[A-Z0-9_]{1,32}")) }?.let { j.put("format", it) }
            File(dir, f.name + ".meta").writeText(j.toString()); trim()
            if (f.exists()) id else null
        }.getOrNull()
    }
    fun registerAttachment(file: File, kind: AttachmentKind, meta: Map<String, String> = emptyMap(),
        correlationId: String = UUID.randomUUID().toString()): String? = runCatching {
        if (!enabled || file.length() > maxBytes / 2) null else registerAttachment(file.readBytes(), kind, meta, correlationId)
    }.getOrNull()

    @Synchronized fun snapshot(): List<String> { trim(); return dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }.map { it.readText().trim() } }
    @Synchronized fun attachments(): List<DebugAttachment> {
        trim(); return dir.listFiles().orEmpty().filter { it.extension == "meta" }.mapNotNull { m -> runCatching {
            val j = JSONObject(m.readText()); val f = File(dir, m.name.removeSuffix(".meta"))
            if (!f.isFile) null else DebugAttachment(j.getString("id"), AttachmentKind.valueOf(j.getString("kind")), f, j.getString("correlationId"))
        }.getOrNull() }
    }
    @Synchronized fun storageBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }
    @Synchronized fun trim() {
        val files = dir.listFiles().orEmpty().sortedBy { it.name }
        files.filter { (it.name.substringBefore('-').toLongOrNull() ?: 0) < clock() - MAX_AGE_MS }.forEach { it.delete() }
        // Bound inode use as well as content bytes on filesystems with large allocation blocks.
        dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }.dropLast(2000).forEach { it.delete() }
        var size = storageBytes()
        files.filter { it.exists() }.forEach { if (size > maxBytes) { size -= it.length(); it.delete() } }
        dir.listFiles().orEmpty().filter { it.extension == "meta" && !File(dir, it.name.removeSuffix(".meta")).exists() }.forEach { it.delete() }
    }
    private fun safeId(id: String): String = id.takeIf { it.matches(Regex("[a-fA-F0-9-]{1,36}")) } ?: "invalid"
}
