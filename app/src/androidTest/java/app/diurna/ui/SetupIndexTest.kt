package app.diurna.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** CI emulator only, like the other device tests. */
class SetupIndexTest {
    @get:Rule val compose = createComposeRule()

    @Before fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
    }

    @Test fun nothingToFixShowsNoAttentionBox() {
        compose.setContent { DiurnaTheme { Column { NeedsAttentionCard(emptyList()) } } }
        compose.onNodeWithText("Needs attention").assertDoesNotExist()
    }

    @Test fun eachFixRunsItsOwnAction() {
        val ran = mutableListOf<String>()
        compose.setContent {
            DiurnaTheme {
                NeedsAttentionCard(listOf(
                    AttentionRow("Exact alarms", "Lets the alarm fire at the exact minute.", "Fix") { ran += "exact" },
                    AttentionRow("No backup since Nov 15", "Daily backups have stopped. Try one now.", "Back up") { ran += "backup" },
                ))
            }
        }
        compose.onNodeWithText("Needs attention").assertIsDisplayed()
        compose.onNodeWithText("Back up").performClick()
        compose.onNodeWithText("Fix").performClick()
        assertEquals(listOf("backup", "exact"), ran)
    }

    @Test fun groupRowsOpenTheirPageAndShowWhatNeedsFixing() {
        val opened = mutableListOf<String>()
        compose.setContent {
            DiurnaTheme {
                LinkList(listOf(
                    LinkRow("Alarm", "Armed · next Tue 06:30", toFixChip(1)) { opened += "alarm" },
                    LinkRow("Food", "2,400 kcal · 42 meals", toFixChip(0)) { opened += "food" },
                ))
            }
        }
        compose.onNodeWithText("1 to fix").assertIsDisplayed()
        compose.onNodeWithText("0 to fix").assertDoesNotExist()
        compose.onNodeWithText("Food").performClick()
        compose.onNodeWithText("Armed · next Tue 06:30").performClick()
        assertEquals(listOf("food", "alarm"), opened)
    }
}
