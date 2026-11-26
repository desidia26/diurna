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
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.diurna.domain.HealthInventory
import app.diurna.domain.HealthInventory.Kind
import app.diurna.domain.HealthInventory.Seen
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Health Connect access, read-only. Shared by sleep (#117) and cardio import (#77).
 * Records are untrusted: only their type, source app, times and counts are kept here.
 */
object HealthConnect {
    enum class Availability { AVAILABLE, NEEDS_UPDATE, MISSING }

    fun availability(context: Context): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_UPDATE
        else -> Availability.MISSING
    }

    val recordTypes: Map<Kind, KClass<out Record>> = mapOf(
        Kind.SLEEP to SleepSessionRecord::class,
        Kind.HEART_RATE to HeartRateRecord::class,
        Kind.HRV to HeartRateVariabilityRmssdRecord::class,
        Kind.BREATHING to RespiratoryRateRecord::class,
        Kind.SPO2 to OxygenSaturationRecord::class,
        Kind.RESTING_HR to RestingHeartRateRecord::class,
    )

    /** Must match the READ_* permissions in the manifest. */
    val readPermissions: Set<String> = recordTypes.values.map { HealthPermission.getReadPermission(it) }.toSet()

    fun permission(kind: Kind): String = HealthPermission.getReadPermission(recordTypes.getValue(kind))
}

/** Whether this phone's Health Connect supports a feature sleep may need later. */
enum class FeatureState { AVAILABLE, UNAVAILABLE }

data class HealthSnapshot(
    val readAt: Instant,
    val rows: List<HealthInventory.Row>,
    /** Allowed and read, but nothing there. */
    val empty: List<Kind>,
    val noPermission: List<Kind>,
    val failed: List<Kind>,
    val backgroundReads: FeatureState,
    val history: FeatureState,
)

/** Reads the last 7 days of each sleep-related type, for Setup → Data → Health Connect. */
class HealthInventoryReader(private val context: Context) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun read(now: Instant = Instant.now()): HealthSnapshot {
        val granted = granted()
        val filter = TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now)
        val seen = mutableListOf<Seen>()
        val capped = mutableSetOf<Kind>()
        val empty = mutableListOf<Kind>()
        val noPermission = mutableListOf<Kind>()
        val failed = mutableListOf<Kind>()
        for ((kind, type) in HealthConnect.recordTypes) {
            if (HealthConnect.permission(kind) !in granted) { noPermission += kind; continue }
            try {
                val got = readAll(kind, type, filter)
                if (got.size >= MAX_PER_KIND) capped += kind
                if (got.isEmpty()) empty += kind
                seen += got
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                noPermission += kind
            } catch (_: Exception) {
                failed += kind
            }
        }
        return HealthSnapshot(
            readAt = now,
            rows = HealthInventory.rows(seen, capped),
            empty = empty,
            noPermission = noPermission,
            failed = failed,
            backgroundReads = feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
            history = feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY),
        )
    }

    private fun feature(f: Int): FeatureState =
        if (client.features.getFeatureStatus(f) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) FeatureState.AVAILABLE
        else FeatureState.UNAVAILABLE

    private suspend fun readAll(kind: Kind, type: KClass<out Record>, filter: TimeRangeFilter): List<Seen> {
        val out = mutableListOf<Seen>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, filter, pageSize = PAGE, pageToken = token))
            page.records.forEach { r -> seen(kind, r)?.let { out += it } }
            token = page.pageToken
        } while (token != null && out.size < MAX_PER_KIND)
        return out.take(MAX_PER_KIND)
    }

    private fun seen(kind: Kind, r: Record): Seen? {
        val origin = r.metadata.dataOrigin.packageName.take(200)
        return when (r) {
            is SleepSessionRecord -> Seen(kind, origin, r.endTime, stages = r.stages.take(MAX_STAGES).map { it.stage })
            is HeartRateRecord -> Seen(kind, origin, r.endTime, samples = r.samples.size)
            is HeartRateVariabilityRmssdRecord -> Seen(kind, origin, r.time)
            is RespiratoryRateRecord -> Seen(kind, origin, r.time)
            is OxygenSaturationRecord -> Seen(kind, origin, r.time)
            is RestingHeartRateRecord -> Seen(kind, origin, r.time)
            else -> null
        }
    }

    private companion object {
        const val PAGE = 1000
        // A week of 5-minute HRV is about 2,000; anything far past that isn't worth counting exactly.
        const val MAX_PER_KIND = 5000
        const val MAX_STAGES = 500
    }
}
