package app.diurna.debug

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class DebugReportTest {
    @Test fun reportContainsManifestLogAndOnlySelectedAttachments() {
        val dir = Files.createTempDirectory("report").toFile()
        val image = File(dir, "source").apply { writeBytes(byteArrayOf(1,2,3)) }
        val one = DebugAttachment("opaque-id", AttachmentKind.IMAGE, image, "correlation")
        val destination = File(dir, "report.zip")
        val data = ReportData("1.2", 123, "Test device", "37", Screen.DAY, 1000L, "a note")
        DebugReport.write(destination, data, listOf("{\"version\":1}"), listOf(one))
        ZipFile(destination).use { zip ->
            assertNotNull(zip.getEntry("manifest.json")); assertNotNull(zip.getEntry("actions.jsonl"))
            assertNotNull(zip.getEntry("attachments/opaque-id.image"))
            val manifest = org.json.JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertEquals("a note", manifest.getString("note")); assertEquals(123, manifest.getLong("versionCode"))
            assertEquals("DAY", manifest.getString("screen")); assertEquals(1, manifest.getJSONArray("attachments").length())
        }
        DebugReport.write(destination, data, emptyList(), emptyList())
        ZipFile(destination).use { assertEquals(2, it.size()); assertEquals(0, it.getEntry("actions.jsonl").size) }
    }
    @Test fun generatedArchiveNamesCannotTraversePaths() {
        val dir = Files.createTempDirectory("report").toFile()
        val file = File(dir, "source").apply { writeText("safe") }
        assertThrows(IllegalArgumentException::class.java) {
            DebugReport.write(File(dir, "out.zip"), ReportData("1", 1, "d", "a", Screen.ALARM, 1, ""), emptyList(),
                listOf(DebugAttachment("../../escape", AttachmentKind.IMAGE, file, "id")))
        }
    }
    @Test fun noteIsBoundedAndMissingAttachmentsAreSkipped() {
        val dir = Files.createTempDirectory("report").toFile(); val out = File(dir, "report.zip")
        DebugReport.write(out, ReportData("1", 1, "d", "a", Screen.WEEK, 1, "x".repeat(9000)), emptyList(),
            listOf(DebugAttachment("missing", AttachmentKind.IMAGE, File(dir, "missing"), "id")))
        ZipFile(out).use { zip ->
            val j = org.json.JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertEquals(4000, j.getString("note").length); assertEquals(0, j.getJSONArray("attachments").length())
        }
    }
}
