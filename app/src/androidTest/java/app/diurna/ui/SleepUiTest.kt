package app.diurna.ui

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import app.diurna.data.SleepPeriodEntity
import app.diurna.domain.SleepMetrics
import app.diurna.domain.SleepRules
import app.diurna.domain.SleepView
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** CI emulator only, like the other device tests. Made-up nights. */
class SleepUiTest {
    @get:Rule val compose = createComposeRule()
    private val thu = LocalDate.of(2026, 11, 26)
    private val night = SleepPeriodEntity(
        source = "health_connect", origin = SleepRules.OURA, sourceId = "n1", date = thu.toString(), type = "main",
        bedStart = "2026-11-25 22:10", wokeAt = "2026-11-26 06:15", bedEnd = "2026-11-26 06:50",
        timeInBedS = 8 * 3600 + 5 * 60, totalSleepS = 7 * 3600, awakeS = 65 * 60,
        lightS = 4 * 3600, deepS = 3600, remS = 2 * 3600, latencyS = 600, efficiency = 0.87, awakenings = 1,
        avgHr = 58.0, lowestHr = 49.0, avgHrv = 85.0,
        stages = "[[0,600,1],[600,9000,4],[9000,12600,5],[12600,29100,6],[29100,31200,1]]",
        hrSamples = "[[0,62],[300,60],[1200,52],[1500,49]]", hrvSamples = "[[0,70],[300,90]]",
        fetchedAt = 0,
    )

    @Before fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
    }

    @Test fun morningRowShowsTheNightAndOpensDetail() {
        var opened = 0
        val ui = DayViewModel.SleepUi(SleepView.Day(night, emptyList()), SleepRules.OURA, thu)
        compose.setContent { DiurnaTheme { SleepRow(ui, thu) { opened++ } } }
        compose.onNodeWithText("7h 00m asleep").assertIsDisplayed()
        compose.onNodeWithText("22:10 → woke 06:15 · up 06:50 · Oura").assertIsDisplayed()
        compose.onNodeWithText("7h 00m asleep").performClick()
        assertEquals(1, opened)
    }

    @Test fun beforeOuraSyncsTheRowSaysSo() {
        var opened = 0
        val ui = DayViewModel.SleepUi(SleepView.Day(null, emptyList()), SleepRules.OURA, thu)
        compose.setContent { DiurnaTheme { SleepRow(ui, thu) { opened++ } } }
        compose.onNodeWithText("Not synced yet. Oura sends last night after its app syncs.").assertIsDisplayed().performClick()
        assertEquals(0, opened)
    }

    @Test fun detailShowsTotalsAndHidesWhatWasntSent() {
        val ui = DayViewModel.SleepUi(SleepView.Day(night.copy(avgHrv = null, hrvSamples = null), emptyList()), SleepRules.OURA, thu)
        compose.setContent { DiurnaTheme { SleepScreen(ui, thu, PaddingValues()) {} } }
        compose.onNodeWithText("7h 00m asleep · 8h 05m in bed").assertIsDisplayed()
        compose.onNodeWithText("87%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sleep stages through the night").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Heart rate overnight"))
        compose.onNodeWithText("49").assertIsDisplayed()
        compose.onNodeWithText("HRV overnight").assertDoesNotExist()
    }

    @Test fun metricsCardSwitchesTabs() {
        val card = SleepMetrics.card(listOf(night), SleepRules.OURA, thu.minusDays(6), thu, emptyMap(), emptySet(), daily = true)!!
        compose.setContent { DiurnaTheme { SleepCardView(card, thu.minusDays(6), thu) } }
        compose.onNodeWithText("7h 00m").assertIsDisplayed()
        compose.onNodeWithText("Bed & wake").performClick()
        compose.onNodeWithContentDescription("Bedtime and wake time each night, with alarm and porch scan").assertIsDisplayed()
        compose.onNodeWithText("HR & HRV").performClick()
        compose.onNodeWithText("Lowest heart rate overnight").assertIsDisplayed()
    }
}
