package app.diurna.domain

/**
 * What the scheduler should report (and whether to cancel the previous PendingIntent)
 * after reading porch confirmation and the next occurrence. Kept free of Android.
 */
sealed interface ArmedState {
    data class Armed(val next: AlarmRules.NextAlarm) : ArmedState
    data object NoneUpcoming : ArmedState
    data object NeedsPorchCode : ArmedState
    data object NotAllowed : ArmedState
}

object AlarmScheduleDecision {
    /**
     * [armSucceeded] is false when the system refused exact alarms ([SecurityException]).
     * Callers cancel the previous real operation when the result is [ArmedState.NeedsPorchCode]
     * or [ArmedState.NoneUpcoming].
     */
    fun afterReschedule(
        porchConfirmed: Boolean,
        next: AlarmRules.NextAlarm?,
        armSucceeded: Boolean,
    ): ArmedState = when {
        !porchConfirmed -> ArmedState.NeedsPorchCode
        next == null -> ArmedState.NoneUpcoming
        armSucceeded -> ArmedState.Armed(next)
        else -> ArmedState.NotAllowed
    }

    fun shouldCancelExisting(state: ArmedState): Boolean =
        state is ArmedState.NeedsPorchCode || state is ArmedState.NoneUpcoming
}
