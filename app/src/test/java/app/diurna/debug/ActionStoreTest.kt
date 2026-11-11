package app.diurna.debug

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ActionStoreTest {
    private fun recorder(cap: Long = 20000, now: () -> Long = { 1000L }) =
        ActionRecorder(Files.createTempDirectory("actions").toFile(), maxBytes = cap, clock = now)

    @Test fun disabledIsNoOp() {
        val r = recorder(); r.store.dispatch(Action.Event(Screen.DAY, "food.add"))
        assertTrue(r.snapshot().isEmpty()); assertNull(r.registerAttachment(byteArrayOf(1), AttachmentKind.IMAGE))
    }
    @Test fun middlewareOrderAndNoDataReducer() {
        val seen = mutableListOf<String>()
        val s = Store(listOf(Middleware { a, next -> seen += "before"; next(a); seen += "after" }))
        s.dispatch(Action.Event(Screen.DAY, "food.add")); assertEquals(listOf("before", "after"), seen)
    }
    @Test fun typedTextIsOptInAndSecretsAreRedacted() {
        val r = recorder(); r.enabled = true
        r.store.dispatch(Action.Text(Screen.DAY, TextField.FOOD, "hello"))
        assertFalse(r.snapshot().joinToString().contains("hello"))
        r.includeTypedText = true; r.knownSecrets = { listOf("private-example-value") }
        r.store.dispatch(Action.Text(Screen.DAY, TextField.FOOD, "private-example-value Bearer abcdefghijk sk-abcdefghijk"))
        val log = r.snapshot().joinToString()
        assertFalse(log.contains("private-example-value")); assertFalse(log.contains("abcdefghijk"))
        assertTrue(log.contains("REDACTED"))
    }
    @Test fun boundedAndExpiredTogetherWithAttachments() {
        var now = 1000L; val r = recorder(2000) { now }; r.enabled = true
        repeat(30) { r.store.dispatch(Action.Event(Screen.DAY, "food.add")) }
        r.registerAttachment(ByteArray(500), AttachmentKind.IMAGE)
        assertTrue(r.storageBytes() <= 2000)
        now += ActionRecorder.MAX_AGE_MS + 1
        assertTrue(r.snapshot().isEmpty()); assertTrue(r.attachments().isEmpty())
    }
    @Test fun turningOffPurgesAndTurnsOffText() {
        val r = recorder(); r.enabled = true; r.includeTypedText = true
        r.store.dispatch(Action.Event(Screen.ALARM, "alarm.open")); r.enabled = false
        assertTrue(r.snapshot().isEmpty()); assertFalse(r.includeTypedText)
    }
    @Test fun attachmentsAreCopiedAndUseGeneratedNames() {
        val r = recorder(); r.enabled = true
        val id = r.registerAttachment(byteArrayOf(1,2), AttachmentKind.IMAGE)!!
        assertEquals(1, r.attachments().size); assertEquals(id, r.attachments().single().id)
        assertNull(r.registerAttachment(ByteArray(30000), AttachmentKind.IMAGE))
    }
    @Test fun rejectsNonNumericBarcodeAndUntrustedMetadata() {
        val r = recorder(); r.enabled = true
        assertNull(r.registerAttachment("secret=data".toByteArray(), AttachmentKind.BARCODE))
        val id = r.registerAttachment("0123456789012".toByteArray(), AttachmentKind.BARCODE, mapOf("apiKey" to "never-record-this", "format" to "EAN_13"))
        assertNotNull(id)
        assertFalse(r.attachments().single().file.parentFile!!.listFiles()!!.any { it.readText().contains("never-record-this") })
    }
    @Test fun commonCredentialsAndPorchCodeAreScrubbed() {
        val value = Redaction.text("password=example token=example api_key=example daybook:porch:fake-example")
        assertFalse(value.contains("example")); assertTrue(value.contains("REDACTED"))
    }
    @Test fun oversizedFileIsRejectedBeforeReading() {
        val r = recorder(100); r.enabled = true
        val f = Files.createTempFile("attachment", ".bin").toFile(); f.writeBytes(ByteArray(200))
        assertNull(r.registerAttachment(f, AttachmentKind.IMAGE))
    }
    @Test fun correlationAndTimestampAreRecorded() {
        val r = recorder(); r.enabled = true
        val id = java.util.UUID.randomUUID().toString()
        r.store.dispatch(Action.Event(Screen.WEEK, "week.open", id, Action.Result.OK, 12))
        val j = org.json.JSONObject(r.snapshot().single())
        assertEquals(id, j.getString("correlationId")); assertEquals(1000L, j.getLong("timestamp"))
        assertEquals(1, j.getInt("version")); assertEquals(12, j.getInt("latencyMs"))
    }
    @Test fun eventNamesCannotCarryArbitraryPayloads() {
        assertThrows(IllegalArgumentException::class.java) { Action.Event(Screen.DAY, "Authorization: secret") }
    }
    @Test fun loggerFailureDoesNotStopAction() {
        var reduced = false
        Store(listOf(Middleware { _, _ -> error("disk") })) { reduced = true }
            .dispatch(Action.Event(Screen.DAY, "food.add"))
        assertTrue(reduced)
    }
}
