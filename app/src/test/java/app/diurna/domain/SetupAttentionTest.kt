package app.diurna.domain

import app.diurna.domain.SetupAttention.Group
import app.diurna.domain.SetupAttention.Item
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class SetupAttentionTest {
    private val now = LocalDateTime.of(2026, 11, 24, 9, 0)

    @Test fun allSetNeedsNothing() {
        assertEquals(emptyList<Item>(), SetupAttention.items(porchConfirmed = true, missingPermissions = emptyList(), lastBackup = now.minusHours(5), now = now))
    }

    @Test fun alarmBlockersComeFirstThenBackups() {
        val items = SetupAttention.items(
            porchConfirmed = false,
            missingPermissions = listOf("Exact alarms", "Camera"),
            lastBackup = now.minusDays(9),
            now = now,
        )
        assertEquals(
            listOf(Item.PorchCode, Item.Permission("Exact alarms"), Item.Permission("Camera"), Item.StaleBackup(now.minusDays(9))),
            items,
        )
        assertEquals(listOf(Group.ALARM, Group.ALARM, Group.ALARM, Group.DATA), items.map { it.group })
    }

    @Test fun aBackupGoesStaleAfterThreeDaysOfDailyBackupsMissing() {
        assertEquals(false, SetupAttention.backupStale(now.minusDays(3), now))
        assertEquals(true, SetupAttention.backupStale(now.minusDays(3).minusMinutes(1), now))
    }

    @Test fun noBackupYetIsNotFlagged() {
        // A fresh install has none until the first daily run; that isn't something to fix.
        assertEquals(false, SetupAttention.backupStale(null, now))
    }

    @Test fun countsPerGroupForTheIndexBadges() {
        val items = listOf(Item.PorchCode, Item.Permission("Camera"), Item.StaleBackup(now.minusDays(5)))
        assertEquals(2, SetupAttention.count(items, Group.ALARM))
        assertEquals(1, SetupAttention.count(items, Group.DATA))
        assertEquals(0, SetupAttention.count(items, Group.FOOD))
    }
}
