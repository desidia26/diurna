package app.diurna.debug

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ReportData(val versionName: String, val versionCode: Long, val device: String, val androidVersion: String,
                      val screen: Screen, val timestamp: Long, val note: String)

/** Pure archive builder. Call with a reviewed, redacted snapshot on an IO dispatcher. */
object DebugReport {
    const val MAX_BYTES = 20L * 1024 * 1024
    fun write(destination: File, data: ReportData, actions: List<String>, attachments: List<DebugAttachment>) {
        attachments.forEach { require(it.id.matches(Regex("[A-Za-z0-9-]{1,64}"))) }
        val log = actions.joinToString("\n", postfix = if (actions.isEmpty()) "" else "\n").toByteArray()
        var remaining = MAX_BYTES - log.size
        require(remaining >= 0)
        val selected = attachments.filter { it.file.isFile && it.file.length() <= remaining }.filter {
            if (it.file.length() <= remaining) { remaining -= it.file.length(); true } else false
        }
        val manifest = JSONObject().put("formatVersion", 1).put("appVersion", data.versionName).put("versionCode", data.versionCode)
            .put("device", data.device).put("androidVersion", data.androidVersion).put("screen", data.screen.name)
            .put("timestamp", data.timestamp).put("note", data.note.take(4000))
            .put("omittedAttachments", attachments.size - selected.size)
            .put("attachments", JSONArray(selected.map { JSONObject().put("id", it.id).put("kind", it.kind.name)
                .put("correlationId", it.correlationId).put("bytes", it.file.length()) }))
        destination.parentFile?.mkdirs()
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                fun bytes(name: String, value: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
                bytes("manifest.json", manifest.toString(2).toByteArray()); bytes("actions.jsonl", log)
                selected.forEach { a ->
                    zip.putNextEntry(ZipEntry("attachments/${a.id}.${a.kind.name.lowercase()}"))
                    a.file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
