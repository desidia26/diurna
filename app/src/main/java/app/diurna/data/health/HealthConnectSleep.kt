package app.diurna.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.diurna.data.SleepRead
import app.diurna.data.SleepSource
import app.diurna.domain.HealthInventory.Kind
import app.diurna.domain.SleepRules
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Sleep from Health Connect. Needs read access to sleep; heart rate, HRV, breathing rate and SpO2
 * are read when allowed and skipped when not. Lists are capped, since the data is untrusted.
 */
class HealthConnectSleep(private val context: Context) : SleepSource {
    override val key = "health_connect"
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun read(from: Instant, to: Instant): SleepRead {
        if (HealthConnect.availability(context) != HealthConnect.Availability.AVAILABLE) throw HealthConnectMissing()
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthConnect.permission(Kind.SLEEP) !in granted) throw SecurityException("Sleep isn't allowed in Health Connect")
        val filter = TimeRangeFilter.between(from, to)
        fun allowed(kind: Kind) = HealthConnect.permission(kind) in granted

        val sessions = readAll(SleepSessionRecord::class, filter, MAX_SESSIONS).map { r ->
            SleepRules.Session(
                id = r.metadata.id.take(MAX_ID),
                origin = origin(r),
                start = r.startTime,
                end = r.endTime,
                startOffset = r.startZoneOffset,
                endOffset = r.endZoneOffset,
                stages = r.stages.take(SleepRules.MAX_STAGES).map { SleepRules.Stage(it.startTime, it.endTime, it.stage) },
            )
        }
        // Samples only inside each session, from the app that wrote it: a watch's all-day heart
        // rate would otherwise fill the caps long before last night.
        val hr = mutableListOf<SleepRules.Sample>()
        val hrv = mutableListOf<SleepRules.Sample>()
        val breath = mutableListOf<SleepRules.Sample>()
        val spo2 = mutableListOf<SleepRules.Sample>()
        for (s in sessions) {
            val window = TimeRangeFilter.between(s.start, s.end)
            val from = setOf(DataOrigin(s.origin))
            if (allowed(Kind.HEART_RATE)) readAll(HeartRateRecord::class, window, MAX_PER_NIGHT, from)
                .flatMapTo(hr) { r -> r.samples.map { SleepRules.Sample(origin(r), it.time, it.beatsPerMinute.toDouble()) } }
            if (allowed(Kind.HRV)) readAll(HeartRateVariabilityRmssdRecord::class, window, MAX_PER_NIGHT, from)
                .mapTo(hrv) { SleepRules.Sample(origin(it), it.time, it.heartRateVariabilityMillis) }
            if (allowed(Kind.BREATHING)) readAll(RespiratoryRateRecord::class, window, MAX_PER_NIGHT, from)
                .mapTo(breath) { SleepRules.Sample(origin(it), it.time, it.rate) }
            if (allowed(Kind.SPO2)) readAll(OxygenSaturationRecord::class, window, MAX_PER_NIGHT, from)
                .mapTo(spo2) { SleepRules.Sample(origin(it), it.time, it.percentage.value) }
        }
        return SleepRead(sessions, hr, hrv, breath, spo2)
    }

    /** Whether reads from the hourly job will work: the phone supports it and it's allowed. */
    suspend fun backgroundReads(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in client.permissionController.getGrantedPermissions()

    private fun origin(r: Record) = r.metadata.dataOrigin.packageName.take(MAX_ID)

    private suspend fun <T : Record> readAll(
        type: KClass<T>, filter: TimeRangeFilter, cap: Int, origins: Set<DataOrigin> = emptySet(),
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, filter, dataOriginFilter = origins, pageSize = PAGE, pageToken = token))
            out += page.records
            token = page.pageToken
        } while (token != null && out.size < cap)
        return out.take(cap)
    }

    private companion object {
        const val PAGE = 1000
        const val MAX_ID = 200
        // 30 days of sessions, naps included, with plenty of room.
        const val MAX_SESSIONS = 300
        // A night of samples every few seconds; Oura writes one every 5 minutes.
        const val MAX_PER_NIGHT = 10_000
    }
}

class HealthConnectMissing : Exception("Health Connect isn't available on this phone")

/** Permissions sleep asks for together: sleep (needed), the rest optional, and background reads when supported. */
suspend fun sleepPermissions(context: Context): Set<String> {
    val client = HealthConnectClient.getOrCreate(context)
    val background = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    return HealthConnect.readPermissions + if (background) setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else emptySet()
}
