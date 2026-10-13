package app.daybook.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.daybook.MainActivity
import app.daybook.data.DayRepository
import app.daybook.data.SettingsStore
import app.daybook.domain.AlarmRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

/** What the scheduler last did, for the Setup screen. */
sealed interface ArmedState {
    data class Armed(val next: AlarmRules.NextAlarm) : ArmedState
    data object NoneUpcoming : ArmedState
    data object NeedsPorchCode : ArmedState
    data object NotAllowed : ArmedState
}

/** Keeps exactly one real alarm armed: the next one. */
class AlarmScheduler(
    private val context: Context,
    private val days: DayRepository,
    private val settings: SettingsStore,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ArmedState>(ArmedState.NoneUpcoming)
    val state: StateFlow<ArmedState> = _state

    suspend fun reschedule() = mutex.withLock {
        val real = operation(date = null, test = false)
        if (!settings.isConfirmed()) {
            alarmManager.cancel(real)
            _state.value = ArmedState.NeedsPorchCode
            return@withLock
        }
        val now = LocalDateTime.now()
        val inputs = (0L..8L).associate { i ->
            val d = now.toLocalDate().plusDays(i)
            d to days.alarmInputs(d)
        }
        val next = AlarmRules.nextAlarm(now) { inputs.getValue(it) }
        if (next == null) {
            alarmManager.cancel(real)
            _state.value = ArmedState.NoneUpcoming
            return@withLock
        }
        days.markScheduled(next.date, next.time.toString())
        _state.value =
            if (arm(next.at, operation(next.date.toString(), test = false))) ArmedState.Armed(next)
            else ArmedState.NotAllowed
    }

    /**
     * Rings like a real alarm in [seconds], but records nothing and can be stopped without a scan.
     * Returns when it will ring, or null if the system refused to set it.
     */
    fun scheduleTest(seconds: Long = 60): LocalDateTime? {
        val at = LocalDateTime.now().plusSeconds(seconds)
        return if (arm(at, operation(date = null, test = true))) at else null
    }

    private fun arm(at: LocalDateTime, operation: PendingIntent): Boolean {
        val trigger = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), operation)
            true
        } catch (e: SecurityException) {
            Log.w("Daybook", "Exact alarms not permitted", e)
            false
        }
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
