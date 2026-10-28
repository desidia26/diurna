package app.diurna.eval

import app.diurna.ai.CardioReading
import app.diurna.ai.FoodEstimate
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
}
