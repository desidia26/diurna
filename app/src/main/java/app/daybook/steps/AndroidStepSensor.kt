package app.daybook.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.daybook.data.CounterSensor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * The hardware step counter: a low-power chip that keeps counting while the app isn't running and
 * reports its total since the last reboot. Reading it means listening just long enough for one value.
 */
class AndroidStepSensor(private val context: Context) : CounterSensor {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    fun present(): Boolean = sensor != null

    fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    override suspend fun read(): Long? {
        val s = sensor ?: return null
        if (!permitted()) return null
        val value = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0].toLong())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }
        if (value == null) Log.w("DaybookSteps", "step sensor gave no reading within 5s")
        return value
    }

    companion object {
        /** When the phone last started, so a reboot (which resets the sensor) can be recognised. */
        fun bootedAt(): LocalDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime()), ZoneId.systemDefault(),
        )
    }
}
