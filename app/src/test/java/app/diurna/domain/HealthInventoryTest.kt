package app.diurna.domain

import app.diurna.domain.HealthInventory.Kind
import app.diurna.domain.HealthInventory.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HealthInventoryTest {
    private val oura = "com.ouraring.oura"
    private val watch = "com.google.android.apps.fitness"
    private fun at(s: String) = Instant.parse(s)

    @Test fun groupsByKindAndSourceWithCountAndNewest() {
        val rows = HealthInventory.rows(listOf(
            Seen(Kind.HRV, oura, at("2026-11-25T11:00:00Z")),
            Seen(Kind.HRV, oura, at("2026-11-26T11:40:00Z")),
            Seen(Kind.HRV, oura, at("2026-11-26T03:00:00Z")),
            Seen(Kind.HEART_RATE, watch, at("2026-11-26T08:00:00Z"), samples = 40),
        ))
        assertEquals(2, rows.size)
        val hrv = rows.single { it.kind == Kind.HRV }
        assertEquals(3, hrv.count)
        assertEquals(at("2026-11-26T11:40:00Z"), hrv.newest)
        assertEquals(oura, hrv.origin)
    }

    @Test fun ordersByKindThenMostRecordsFirst() {
        val rows = HealthInventory.rows(listOf(
            Seen(Kind.HEART_RATE, oura, at("2026-11-26T08:00:00Z")),
            Seen(Kind.SLEEP, watch, at("2026-11-26T07:00:00Z")),
            Seen(Kind.SLEEP, oura, at("2026-11-25T07:00:00Z")),
            Seen(Kind.SLEEP, oura, at("2026-11-26T07:00:00Z")),
        ))
        assertEquals(listOf(Kind.SLEEP to oura, Kind.SLEEP to watch, Kind.HEART_RATE to oura), rows.map { it.kind to it.origin })
    }

    @Test fun sleepRowsSayHowManyStagesAndWhichTypes() {
        // Health Connect stage codes: 1 awake, 4 light, 5 deep, 6 REM.
        val rows = HealthInventory.rows(listOf(
            Seen(Kind.SLEEP, oura, at("2026-11-25T12:00:00Z"), stages = listOf(1, 4, 5, 6, 4, 1)),
            Seen(Kind.SLEEP, oura, at("2026-11-26T12:00:00Z"), stages = listOf(6, 4, 5, 1, 4, 5, 6, 4, 1)),
        ))
        val r = rows.single()
        assertEquals(6..9, r.stagesPerRecord)
        assertEquals("awake, light, deep, REM", r.stageTypes.joinToString(", ") { HealthInventory.stageName(it) })
        assertEquals("6–9 stages each · awake, light, deep, REM", HealthInventory.detail(r))
    }

    @Test fun sleepWithoutStagesSaysSo() {
        val r = HealthInventory.rows(listOf(Seen(Kind.SLEEP, watch, at("2026-11-26T12:00:00Z")))).single()
        assertEquals("no stages", HealthInventory.detail(r))
    }

    @Test fun seriesRowsSaySamplesPerRecord() {
        val r = HealthInventory.rows(listOf(
            Seen(Kind.HEART_RATE, oura, at("2026-11-25T12:00:00Z"), samples = 100),
            Seen(Kind.HEART_RATE, oura, at("2026-11-26T12:00:00Z"), samples = 100),
        )).single()
        assertEquals("100 samples each", HealthInventory.detail(r))
    }

    @Test fun singleValueRowsHaveNoDetail() {
        val r = HealthInventory.rows(listOf(Seen(Kind.HRV, oura, at("2026-11-26T12:00:00Z")))).single()
        assertEquals("", HealthInventory.detail(r))
    }

    @Test fun cappedKindsAreMarked() {
        val rows = HealthInventory.rows(listOf(Seen(Kind.HRV, oura, at("2026-11-26T12:00:00Z"))), capped = setOf(Kind.HRV))
        assertTrue(rows.single().capped)
        assertFalse(HealthInventory.rows(listOf(Seen(Kind.HRV, oura, at("2026-11-26T12:00:00Z")))).single().capped)
    }

    @Test fun knownSourcesGetNames() {
        assertEquals("Oura", HealthInventory.sourceName(oura))
        assertEquals("This phone", HealthInventory.sourceName("com.android.healthconnect.phone.d73ed1933a49c351"))
        assertEquals("com.example.ring", HealthInventory.sourceName("com.example.ring"))
    }

    @Test fun textHasCountsAndTimesOnly() {
        val rows = HealthInventory.rows(listOf(
            Seen(Kind.SLEEP, oura, at("2026-11-26T11:50:00Z"), stages = listOf(1, 4, 5, 6)),
            Seen(Kind.HRV, oura, at("2026-11-26T11:40:00Z")),
        ), capped = setOf(Kind.HRV))
        val text = HealthInventory.text(
            rows,
            empty = listOf(Kind.BREATHING),
            noPermission = listOf(Kind.SPO2),
            zone = ZoneOffset.ofHours(-5),
        )
        assertEquals(
            """
            Health Connect, last 7 days
            Sleep session · Oura · 1 · newest 2026-11-26 06:50 · 4 stages each · awake, light, deep, REM
            HRV (RMSSD) · Oura · 1+ · newest 2026-11-26 06:40
            None: Breathing rate
            No permission: SpO2
            """.trimIndent(),
            text,
        )
    }
}
