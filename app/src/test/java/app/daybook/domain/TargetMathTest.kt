package app.daybook.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TargetMathTest {
    private val day = LocalDate.of(2026, 10, 13)
    // 30 years old on [day], 5'10", moderately active.
    private val base = Profile(Sex.MALE, LocalDate.of(1996, 1, 15), 70.0, Activity.MODERATE, Goal.CUT)

    @Test fun mifflinStJeorMatchesHandCalculation() {
        // 10×81.65 + 6.25×177.8 − 5×30 + 5 = 1782.7
        assertEquals(1782.7, TargetMath.bmr(base, 180.0, day), 0.1)
        assertEquals(1782.7 - 166, TargetMath.bmr(base.copy(sex = Sex.FEMALE), 180.0, day), 0.1)
    }

    @Test fun cutExample() {
        // maintenance 2763 − 500 → 2260; protein 1.0 g/lb; fat 25%; carbs the rest.
        assertEquals(Targets(kcal = 2260, proteinG = 180, carbsG = 243, fatG = 63), TargetMath.targets(base, 180.0, day))
    }

    @Test fun goalsShiftCaloriesAndProtein() {
        val recomp = TargetMath.targets(base.copy(goal = Goal.RECOMP), 180.0, day)
        assertEquals(2660, recomp.kcal)
        assertEquals(198, recomp.proteinG) // 1.1 g/lb
        assertEquals(2760, TargetMath.targets(base.copy(goal = Goal.MAINTAIN), 180.0, day).kcal)
        assertEquals(3010, TargetMath.targets(base.copy(goal = Goal.LEAN_BULK), 180.0, day).kcal)
        assertEquals(2563 / 10 * 10, TargetMath.targets(base.copy(goal = Goal.CUSTOM, customKcalDelta = -200), 180.0, day).kcal)
    }

    @Test fun overridesWin() {
        val t = TargetMath.targets(base.copy(proteinPerLb = 0.8, fatPercent = 30), 180.0, day)
        assertEquals(144, t.proteinG)
        assertEquals(75, t.fatG) // 2260 × 30% / 9
    }

    @Test fun ageCountsWholeYearsOnTheDay() {
        val dayBeforeBirthday = LocalDate.of(2027, 1, 14)
        val onBirthday = LocalDate.of(2027, 1, 15)
        assertEquals(5.0, TargetMath.bmr(base, 180.0, dayBeforeBirthday) - TargetMath.bmr(base, 180.0, onBirthday), 0.001)
    }

    @Test fun carbsNeverGoNegative() {
        val tiny = TargetMath.targets(base.copy(goal = Goal.CUSTOM, customKcalDelta = -2500, proteinPerLb = 1.5), 180.0, day)
        assertEquals(0, tiny.carbsG)
    }
}

class NutritionTextTest {
    private val targets = Targets(2260, 180, 243, 63)

    @Test fun withTargets() {
        val t = NutritionTotals(kcal = 1640, proteinG = 120, carbsG = 150, fatG = 45, estimated = 3)
        assertEquals("1,640 / 2,260 kcal · P 120/180 · C 150/243 · F 45/63", DayText.nutrition(t, targets))
    }

    @Test fun withoutTargetsAndWithPending() {
        val t = NutritionTotals(kcal = 900, proteinG = 40, carbsG = 100, fatG = 30, estimated = 2, pending = 1)
        assertEquals("~900 kcal · P 40 · C 100 · F 30 · 1 estimating", DayText.nutrition(t, null))
    }

    @Test fun nothingEstimatedYet() {
        assertEquals(null, DayText.nutrition(NutritionTotals(pending = 2), targets))
    }
}
