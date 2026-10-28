package app.diurna.eval

import app.diurna.ai.CardioReading
import app.diurna.ai.FoodEstimate
import app.diurna.ai.NutritionLabelReading
import app.diurna.domain.CardioPiece
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalScoringTest {
    private fun foodCase(outcome: String, mid: Int? = 600, tol: Double? = 40.0) = EvalCase(
        id = "t",
        prompt = "food",
        input = CaseInput("x"),
        expect = CaseExpect(
            outcome = outcome,
            kcalMid = mid,
            kcalTolPct = tol,
            proteinG = IntRangeExpect(20, 60),
            carbsG = IntRangeExpect(40, 100),
            fatG = IntRangeExpect(10, 40),
            macrosConsistentWithKcal = true,
        ),
    )

    @Test fun foodEstimateInBandPasses() {
        val s = EvalScoring.scoreFood(
            foodCase("estimate"),
            FoodEstimate(550, 750, 45, 70, 25, "1 bowl"),
        )
        assertTrue(s.checks.joinToString { "${it.name}=${it.passed}" }, s.passed)
    }

    @Test fun foodEstimateWayOffFails() {
        val s = EvalScoring.scoreFood(
            foodCase("estimate", mid = 600, tol = 20.0),
            FoodEstimate(50, 80, 1, 2, 1, "tiny"),
        )
        assertFalse(s.passed)
        assertTrue(s.failedChecks.any { it.name == "kcal_band" })
    }

    @Test fun notFoodOutcome() {
        val s = EvalScoring.scoreFood(
            foodCase("not_food", mid = null, tol = null).let {
                it.copy(expect = it.expect.copy(proteinG = null, carbsG = null, fatG = null, macrosConsistentWithKcal = false))
            },
            FoodEstimate(0, 0, 0, 0, 0, "not food"),
        )
        assertTrue(s.passed)
    }

    @Test fun notFoodOrZerosAcceptsZerosWithoutNote() {
        val c = EvalCase("t", "food", input = CaseInput(""), expect = CaseExpect(outcome = "not_food_or_zeros"))
        assertTrue(EvalScoring.scoreFood(c, FoodEstimate(0, 0, 0, 0, 0, "")).passed)
    }

    @Test fun macroKcalConsistency() {
        assertTrue(EvalScoring.macroKcal(50, 50, 20) == 200 + 200 + 180)
    }

    @Test fun cardioReadingInBand() {
        val c = EvalCase(
            "c", "cardio", input = CaseInput(image = "x.jpg"),
            expect = CaseExpect(
                outcome = "reading",
                distanceM = IntRangeExpect(1900, 2100),
                durationS = IntRangeExpect(440, 470),
                kcal = IntRangeExpect(120, 170),
            ),
        )
        val s = EvalScoring.scoreCardio(
            c,
            CardioReading(CardioPiece(distanceM = 2000, durationS = 452.0, kcal = 142), "15 min"),
        )
        assertTrue(s.passed)
    }

    @Test fun cardioRejectWhenReadingReturnedFails() {
        val c = EvalCase("c", "cardio", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reject"))
        assertFalse(EvalScoring.scoreCardio(c, CardioReading(CardioPiece(distanceM = 1, durationS = 1.0), "x")).passed)
    }

    @Test fun cardioRejectOnFailurePasses() {
        val c = EvalCase("c", "cardio", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reject"))
        assertTrue(EvalScoring.scoreCardioFailure(c, "No distance or time found (not a workout screen)").passed)
    }

    @Test fun parseHealthIgnoresBadBands() {
        val s = EvalScoring.scoreFood(
            foodCase("estimate", mid = 600, tol = 10.0),
            FoodEstimate(50, 80, 1, 2, 1, "tiny"),
            ScoringMode.PARSE_HEALTH,
        )
        assertTrue(s.passed)
        assertTrue(s.infoMisses.any { it.name == "kcal_band" })
        assertTrue(s.failedChecks.isEmpty())
    }

    @Test fun parseHealthNotFoodIsInformational() {
        val c = EvalCase("t", "food", input = CaseInput("walk"), expect = CaseExpect(outcome = "not_food_or_zeros"))
        val s = EvalScoring.scoreFood(
            c,
            FoodEstimate(800, 1200, 10, 20, 30, "a snack"),
            ScoringMode.PARSE_HEALTH,
        )
        assertTrue(s.passed)
        assertTrue(s.infoMisses.any { it.name == "not_food_or_zeros" })
        assertTrue(s.checks.any { it.name == "parsed" && it.passed })
    }

    @Test fun labelReadingInBand() {
        val c = EvalCase(
            "l", "label", input = CaseInput(image = "x.jpg"),
            expect = CaseExpect(
                outcome = "reading",
                kcal = IntRangeExpect(120, 160),
                proteinG = IntRangeExpect(3, 8),
                carbsG = IntRangeExpect(24, 34),
                fatG = IntRangeExpect(0, 5),
            ),
        )
        val s = EvalScoring.scoreLabel(
            c,
            NutritionLabelReading(
                productName = "SYNTHETIC OATS",
                serving = "1 cup (40g)",
                kcal = 140, proteinG = 5, carbsG = 29, fatG = 2,
            ),
        )
        assertTrue(s.passed)
    }

    @Test fun labelRejectOnFailurePasses() {
        val c = EvalCase("l", "label", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reject"))
        assertTrue(EvalScoring.scoreLabelFailure(c, "No calories found (not a nutrition label). Enter it by hand.").passed)
    }

    @Test fun foodRequireDetailAndIngredients() {
        val c = EvalCase(
            "d", "food",
            input = CaseInput("oats", detailed = true, ingredients = true),
            expect = CaseExpect(
                outcome = "estimate",
                kcalMid = 300,
                kcalTolPct = 80.0,
                requireDetail = true,
                requireIngredients = true,
            ),
        )
        val ok = EvalScoring.scoreFood(
            c,
            FoodEstimate(
                250, 350, 10, 50, 8, "bowl",
                fiberG = 5, sugarsG = 12,
                ingredients = listOf(app.diurna.domain.FoodIngredient("oats, cooked", 80.0)),
            ),
        )
        assertTrue(ok.passed)
        val missing = EvalScoring.scoreFood(
            c,
            FoodEstimate(250, 350, 10, 50, 8, "bowl"),
        )
        assertFalse(missing.passed)
        assertTrue(missing.failedChecks.any { it.name == "has_detail" })
        assertTrue(missing.failedChecks.any { it.name == "has_ingredients" })
    }
}
