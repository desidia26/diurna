package app.diurna.ui

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.diurna.domain.DailyMetrics
import app.diurna.domain.DailyMetrics.LogEntry
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** CI emulator only, like the other device tests. */
class LogMetricRowTest {
    @get:Rule val compose = createComposeRule()
    private val headache = DailyMetrics.Def(6, "Headache", DailyMetrics.Kind.LOG)
    private val entries = listOf(LogEntry(1, "09:12", "behind the eyes"), LogEntry(2, "14:40"))

    @Before fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
    }

    @Test fun todayLogsInOneTapAndEntriesOpenTheEditor() {
        var logged = 0
        val opened = mutableListOf<LogEntry?>()
        compose.setContent {
            DiurnaTheme { LogMetricRow(headache, entries, today = true, onLogNow = { logged++ }, onEdit = { opened += it }) }
        }
        compose.onNodeWithText("2 today").assertIsDisplayed()
        compose.onNodeWithText("behind the eyes").assertIsDisplayed()
        compose.onNodeWithText("no note").performClick()
        compose.onNodeWithText("Log now").performClick()
        assertEquals(1, logged)
        assertEquals(listOf<LogEntry?>(entries[1]), opened)
    }

    @Test fun anotherDayAddsThroughTheEditor() {
        var logged = 0
        val opened = mutableListOf<LogEntry?>()
        compose.setContent {
            DiurnaTheme { LogMetricRow(headache, emptyList(), today = false, onLogNow = { logged++ }, onEdit = { opened += it }) }
        }
        compose.onNodeWithText("none").assertIsDisplayed()
        compose.onNodeWithText("Add entry").performClick()
        assertEquals("no 'now' on a past day", 0, logged)
        assertEquals(listOf<LogEntry?>(null), opened)
    }

    @Test fun aNewPastEntryCantSaveWithoutATime() {
        compose.setContent {
            DiurnaTheme { EntrySheet(EntryEdit(headache, null), LocalDate.of(2026, 11, 20), onDismiss = {}, onSave = { _, _ -> }, onDelete = {}) }
        }
        compose.onNodeWithText("Pick a time").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test fun editingKeepsTimeAndNoteAndCanDelete() {
        var saved: Pair<String, String?>? = null
        var deleted: Long? = null
        compose.setContent {
            DiurnaTheme {
                EntrySheet(
                    EntryEdit(headache, entries[0]), LocalDate.of(2026, 11, 20),
                    onDismiss = {}, onSave = { t, n -> saved = t to n }, onDelete = { deleted = it },
                )
            }
        }
        compose.onNodeWithText("09:12").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("09:12" to "behind the eyes", saved)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1L, deleted)
    }
}
