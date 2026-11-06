package app.diurna.domain

/**
 * System broadcasts that mean "alarms may be gone or shifted; re-arm".
 * String values match [android.content.Intent] action constants so receivers stay thin.
 */
object AlarmReschedule {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_TIME_CHANGED = "android.intent.action.TIME_SET"
    const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    private val ACTIONS = setOf(
        ACTION_BOOT_COMPLETED,
        ACTION_TIME_CHANGED,
        ACTION_TIMEZONE_CHANGED,
        ACTION_MY_PACKAGE_REPLACED,
    )

    fun shouldReschedule(action: String?): Boolean = action != null && action in ACTIONS
}
