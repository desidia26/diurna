package app.diurna.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.diurna.MainActivity
import app.diurna.data.DayRepository
import app.diurna.data.SettingsStore
import app.diurna.domain.AlarmRules
import app.diurna.domain.AlarmScheduleDecision
import app.diurna.domain.AlarmTiming
import app.diurna.domain.ArmedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

/** Keeps exactly one real alarm armed: the next one. */
class AlarmScheduler(
    private val context: Context,
    private val days: DayRepository,
    private val settings: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ArmedState>(ArmedState.NoneUpcoming)
    val state: StateFlow<ArmedState> = _state

    suspend fun reschedule() = mutex.withLock {
        val real = operation(date = null, test = false)
        val confirmed = settings.isConfirmed()
        val now = clock()
        val next = if (confirmed) {
            val inputs = (0L until AlarmRules.LOOKAHEAD_DAYS).associate { i ->
                val d = now.toLocalDate().plusDays(i)
                d to days.alarmInputs(d)
            }
            AlarmRules.nextAlarm(now) { inputs.getValue(it) }
        } else null
        if (next != null) days.markScheduled(next.date, next.time.toString())
        val armedOk = next != null && arm(next.at, operation(next.date.toString(), test = false))
        val decided = AlarmScheduleDecision.afterReschedule(confirmed, next, armedOk)
        if (AlarmScheduleDecision.shouldCancelExisting(decided)) alarmManager.cancel(real)
        _state.value = decided
    }

    /**
     * Rings like a real alarm in [seconds], but records nothing and can be stopped without a scan.
     * Returns when it will ring, or null if the system refused to set it.
     */
    fun scheduleTest(seconds: Long = 60): LocalDateTime? {
        val at = clock().plusSeconds(seconds)
        return if (arm(at, operation(date = null, test = true))) at else null
    }

    private fun arm(at: LocalDateTime, operation: PendingIntent): Boolean {
        val trigger = AlarmTiming.triggerEpochMilli(at, zone())
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ok = tryArmExact {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), operation)
        }
        if (!ok) Log.w("Diurna", "Exact alarms not permitted")
        return ok
    }

    private fun operation(date: String?, test: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmService.EXTRA_DATE, date)
            .putExtra(AlarmService.EXTRA_TEST, test)
        return PendingIntent.getBroadcast(
            context, if (test) REQUEST_TEST else REQUEST_REAL, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val REQUEST_REAL = 1
        const val REQUEST_TEST = 2
    }
}

/**
 * Exact-alarm APIs throw [SecurityException] when the permission is missing.
 * Isolated so unit tests can cover the refusal path without AlarmManager.
 */
fun tryArmExact(set: () -> Unit): Boolean = try {
    set()
    true
} catch (_: SecurityException) {
    false
}
