package app.diurna.domain

import java.time.LocalDateTime

/**
 * What Setup's "Needs attention" box lists: only things that stop the alarm or put data at risk.
 * Optional extras (AI keys, USDA, step counting) show as status on their group's row instead,
 * so someone who chose not to use them isn't nagged.
 */
object SetupAttention {
    enum class Group { ALARM, FOOD, TRAINING, CHECK_IN, DATA }

    sealed interface Item {
        val group: Group

        data object PorchCode : Item { override val group = Group.ALARM }
        /** [label] matches the permission's row on the Alarm page. */
        data class Permission(val label: String) : Item { override val group = Group.ALARM }
        data class StaleBackup(val last: LocalDateTime) : Item { override val group = Group.DATA }
    }

    /** Backups run daily, so three days without one means they've stopped. */
    const val STALE_BACKUP_DAYS = 3L

    fun backupStale(last: LocalDateTime?, now: LocalDateTime): Boolean =
        last != null && last.isBefore(now.minusDays(STALE_BACKUP_DAYS))

    fun items(porchConfirmed: Boolean, missingPermissions: List<String>, lastBackup: LocalDateTime?, now: LocalDateTime): List<Item> =
        buildList {
            if (!porchConfirmed) add(Item.PorchCode)
            missingPermissions.forEach { add(Item.Permission(it)) }
            if (lastBackup != null && backupStale(lastBackup, now)) add(Item.StaleBackup(lastBackup))
        }

    fun count(items: List<Item>, group: Group): Int = items.count { it.group == group }
}
