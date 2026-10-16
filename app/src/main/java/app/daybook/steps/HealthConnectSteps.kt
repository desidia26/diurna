package app.daybook.steps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.daybook.data.StepSource
import java.time.LocalDate

/**
 * Reads daily step totals from Health Connect, which the phone already fills from its own step
 * sensor. Health Connect handles reboots and de-duplicates sources, so a day's total is one
 * aggregate query over local midnight to midnight.
 */
class HealthConnectSteps(private val context: Context) : StepSource {
    enum class Status { UNAVAILABLE, NEEDS_PERMISSION, NEEDS_BACKGROUND, READY }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Background reads only exist on newer Health Connect versions; without them the lock screen can't refresh steps. */
    fun backgroundSupported(): Boolean = available() &&
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
        HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    /** What to ask for: always steps, plus background reading where supported. */
    fun permissionsToRequest(): Set<String> =
        if (backgroundSupported()) setOf(READ_STEPS, READ_IN_BACKGROUND) else setOf(READ_STEPS)

    suspend fun status(): Status {
        if (!available()) return Status.UNAVAILABLE
        val granted = client.permissionController.getGrantedPermissions()
        return when {
            READ_STEPS !in granted -> Status.NEEDS_PERMISSION
            backgroundSupported() && READ_IN_BACKGROUND !in granted -> Status.NEEDS_BACKGROUND
            else -> Status.READY
        }
    }

    override suspend fun stepsOn(date: LocalDate): Long? {
        if (!available()) return null
        return try {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay()),
                )
            )
            result[StepsRecord.COUNT_TOTAL]
        } catch (e: SecurityException) {
            null // permission missing, or a background read without the background permission
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        const val READ_IN_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    }
}
