package app.diurna.ui

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.diurna.data.FoodEntryEntity
import app.diurna.data.FoodPhotos
import app.diurna.data.totals
import app.diurna.domain.DailyValues
import app.diurna.domain.DvBasis
import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutrientId
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** CI emulator only, like the other device tests. */
class NutritionDetailTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 11, 20)
    private val dv = DailyValues.forDay(DvBasis.FDA, null, null, null)

    @Before fun setup() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
    }

    private fun entry(id: Long, time: String, text: String) = FoodEntryEntity(
        id = id, date = day.toString(), time = time, text = text,
        loggedAt = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        kcalLow = 690, kcalHigh = 780, proteinG = 42, carbsG = 78, fatG = 24, estimatedAt = 1L,
    )

    private val bowl = entry(1, "12:45", "Chicken burrito bowl").copy(
        fiberG = 14, solubleFiberG = 4, insolubleFiberG = 10, sugarsG = 6, addedSugarsG = 1, starchG = 52,
        satFatG = 6, monoFatG = 12, polyFatG = 4, transFatG = 0, cholesterolMg = 110, leuMg = 3300, lysMg = 3500,
        estimateNote = "large bowl, white rice",
    )
    private val bowlMicros = MicronutrientProfile.of(NutrientId.SODIUM to 1480.0, NutrientId.IRON to 4.8)

    @Test fun anOpenEntryShowsGroupedSectionsAndFoldsAminos() {
        val photos = FoodPhotos(ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("nutrition-detail-test"))
        compose.setContent {
            DiurnaTheme {
                FoodRow(
                    bowl, day, online = true, photos = photos, detailed = true, gallery = emptyList(),
                    micros = bowlMicros, dv = dv, expanded = true, onToggle = {},
                    onEdit = {}, onDelete = {}, onServings = {}, onSaveAsMeal = {},
                )
            }
        }
        compose.onNodeWithText("CARBS").assertIsDisplayed()
        compose.onNodeWithText("Soluble · Insoluble").assertIsDisplayed()
        compose.onNodeWithText("FATS").assertIsDisplayed()
        compose.onNodeWithText("1,480 mg").assertIsDisplayed()
        compose.onNodeWithText("Sodium is 64% of a day's limit in this one entry.").assertIsDisplayed()
        compose.onNodeWithText("Assumed: large bowl, white rice").assertIsDisplayed()
        // Tiles replace the one-line macros; aminos stay folded until asked for.
        assertEquals(0, compose.count("690–780 kcal · P 42 · C 78 · F 24"))
        assertEquals(0, compose.count("3300 mg"))
        compose.onNodeWithText("AMINO ACIDS").performClick()
        compose.onNodeWithText("3300 mg").assertIsDisplayed()
    }

    @Test fun anOpenEntryFlagsEveryKeepUnderThatIsAlreadyHalfTheDay() {
        val photos = FoodPhotos(ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("nutrition-detail-heavy-test"))
        val heavy = bowl.copy(satFatG = 12, addedSugarsG = 30, cholesterolMg = 200)
        compose.setContent {
            DiurnaTheme {
                FoodRow(
                    heavy, day, online = true, photos = photos, detailed = true, gallery = emptyList(),
                    micros = bowlMicros, dv = dv, expanded = true, onToggle = {},
                    onEdit = {}, onDelete = {}, onServings = {}, onSaveAsMeal = {},
                )
            }
        }
        compose.onNodeWithText("Sodium is 64% of a day's limit in this one entry.").assertIsDisplayed()
        compose.onNodeWithText("Saturated fat is 60% of a day's limit in this one entry.").assertIsDisplayed()
        compose.onNodeWithText("Added sugars is 60% of a day's limit in this one entry.").assertIsDisplayed()
        compose.onNodeWithText("Cholesterol is 67% of a day's limit in this one entry.").assertIsDisplayed()
    }

    @Test fun theDayDrillsDownOneLevelPerTap() {
        val food = listOf(bowl, entry(2, "21:00", "Beer"))
        val micros = mapOf(1L to bowlMicros)
        var percentSet: Boolean? = null
        compose.setContent {
            DiurnaTheme {
                var percent by remember { mutableStateOf(true) }
                DayNutritionDetail(
                    dayNutrition(food, micros, dv)!!, food.totals(), withMicros = 1,
                    percent = percent, onPercent = { percent = it; percentSet = it }, resetKey = day,
                )
            }
        }
        // Closed: just the summary.
        compose.onNodeWithText("Nutrition detail").assertIsDisplayed()
        assertEquals(0, compose.count("Get enough"))

        compose.onNodeWithText("Nutrition detail").performClick()
        compose.onNodeWithText("Get enough").assertIsDisplayed()
        compose.onNodeWithText("Vitamins and minerals come from 1 of 2 entries, so their totals are a floor.").assertIsDisplayed()
        assertEquals(0, compose.count("Sodium"))

        compose.onNodeWithText("Keep under").performClick()
        compose.onNodeWithText("Sodium").assertIsDisplayed()
        compose.onNodeWithText("64%").assertIsDisplayed()
        compose.onNodeWithText("Cholesterol").assertIsDisplayed()
        compose.onNodeWithText("37%").assertIsDisplayed()
        assertEquals(0, compose.count("Chicken burrito bowl"))

        compose.onNodeWithText("Sodium").performClick()
        compose.onNodeWithText("Chicken burrito bowl").assertIsDisplayed()

        compose.onNodeWithText("Cholesterol").performClick()
        compose.onNodeWithText("Chicken burrito bowl").assertIsDisplayed()
        compose.onNodeWithText("110 mg").assertIsDisplayed()

        compose.onNodeWithText("Amounts").performClick()
        assertEquals(false, percentSet)
        // Amounts lead and the percentage moves to the subtitle; the open source line shows the same amount.
        // Only one nutrient stays open, so after tapping Cholesterol the duplicate amount is 110 mg.
        compose.onNodeWithText("64% DV · 1 of 2 entries").assertIsDisplayed()
        compose.onNodeWithText("37% DV · 1 of 2 entries").assertIsDisplayed()
        assertEquals(1, compose.count("1,480 mg"))
        assertEquals(2, compose.count("110 mg"))
    }

    private fun ComposeContentTestRule.count(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
