package app.diurna.domain

/**
 * Copy and ids for the ringing foreground notification / full-screen intent.
 * Kept here so the service builds Android objects from values that unit tests can assert.
 */
object AlarmNotice {
    const val CHANNEL_ID = "alarm"
    const val CHANNEL_NAME = "Alarm"
    const val NOTIFICATION_ID = 1
    const val TITLE = "Time to get up"
    const val TEXT = "Scan your porch code to stop the alarm"
    const val STOP_ACTION = "app.diurna.STOP_ALARM"

    val maxRingMs: Long get() = AlarmRules.RING_MINUTES * 60_000L

    /** Parse the extras the scheduler/receiver put on the start Intent. */
    fun ringDate(dateExtra: String?): java.time.LocalDate? =
        dateExtra?.let(java.time.LocalDate::parse)

    fun isStopAction(action: String?): Boolean = action == STOP_ACTION
}
