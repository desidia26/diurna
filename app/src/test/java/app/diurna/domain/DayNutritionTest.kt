package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayNutritionTest {

    private val dv = DailyValues.forDay(DvBasis.FDA, null, null, null)

    private fun entry(id: Long, time: String, label: String, vararg amounts: Pair<DvNutrient, Double>) =
        DayNutrition.Entry(id, time, label, amounts.toMap())

    @Test fun noEntriesNoDetail() {
        assertNull(DayNutrition.of(emptyList(), dv))
    }

    @Test fun sumsKnownAndCountsCoveragePerNutrient() {
        val day = DayNutrition.of(
            listOf(
                entry(1, "07:10", "Oats", DvNutrient.FIBER to 9.0, DvNutrient.VITAMIN_D to 1.5),
                entry(2, "12:45", "Bowl", DvNutrient.FIBER to 14.0),
                entry(3, "21:00", "Beer"),
            ),
            dv,
        )!!
        val fiber = day.row(DvNutrient.FIBER)
        assertEquals(23.0, fiber.amount!!, 0.0)
        assertEquals(2, fiber.known)
        assertEquals(3, fiber.total)
        assertTrue(fiber.partial)
        assertEquals(82, fiber.pct)

        val d = day.row(DvNutrient.VITAMIN_D)
        assertEquals(1, d.known)
        assertEquals(8, d.pct)

        val iron = day.row(DvNutrient.IRON)
        assertNull(iron.amount)
        assertNull(iron.pct)
        assertEquals(0, iron.known)
        assertEquals(3, day.total)
    }

    @Test fun aMeasuredZeroIsKnown() {
        val day = DayNutrition.of(listOf(entry(1, "08:00", "Water", DvNutrient.SODIUM to 0.0)), dv)!!
        assertEquals(0.0, day.row(DvNutrient.SODIUM).amount!!, 0.0)
        assertEquals(0, day.row(DvNutrient.SODIUM).pct)
        assertEquals(1, day.row(DvNutrient.SODIUM).known)
    }

    @Test fun sourcesAreTheTopThreeByAmount() {
        val day = DayNutrition.of(
            listOf(
                entry(1, "07:10", "Oats", DvNutrient.SODIUM to 120.0),
                entry(2, "12:45", "Bowl", DvNutrient.SODIUM to 1480.0),
                entry(3, "15:20", "Bar", DvNutrient.SODIUM to 310.0),
                entry(4, "19:00", "Steak", DvNutrient.SODIUM to 860.0),
                entry(5, "21:00", "Beer"),
            ),
            dv,
        )!!
        assertEquals(listOf(2L, 4L, 3L), day.row(DvNutrient.SODIUM).sources.map { it.entryId })
        assertEquals("12:45", day.row(DvNutrient.SODIUM).sources.first().time)
        assertEquals(1480.0, day.row(DvNutrient.SODIUM).sources.first().amount, 0.0)
    }

    @Test fun goalsLowestFirstLimitsHighestFirstUnknownLast() {
        val day = DayNutrition.of(
            listOf(
                entry(
                    1, "12:00", "Lunch",
                    DvNutrient.FIBER to 28.0, // 100%
                    DvNutrient.VITAMIN_D to 4.0, // 20%
                    DvNutrient.POTASSIUM to 2350.0, // 50%
                    DvNutrient.SODIUM to 3450.0, // 150%
                    DvNutrient.SAT_FAT to 10.0, // 50%
                ),
            ),
            dv,
        )!!
        assertEquals(
            listOf(DvNutrient.VITAMIN_D, DvNutrient.POTASSIUM, DvNutrient.FIBER),
            day.goals.take(3).map { it.nutrient },
        )
        assertTrue(day.goals.drop(3).all { it.amount == null })
        assertEquals(DvNutrient.entries.count { it.kind == DvKind.GOAL }, day.goals.size)
        assertEquals(listOf(DvNutrient.SODIUM, DvNutrient.SAT_FAT, DvNutrient.ADDED_SUGARS), day.limits.map { it.nutrient })
    }

    @Test fun summaryNamesWhatIsMetAndOver() {
        val day = DayNutrition.of(
            listOf(
                entry(
                    1, "12:00", "Lunch",
                    DvNutrient.FIBER to 30.0,
                    DvNutrient.VITAMIN_B12 to 3.0,
                    DvNutrient.VITAMIN_D to 4.0,
                    DvNutrient.SODIUM to 3120.0,
                    DvNutrient.SAT_FAT to 21.0,
                    DvNutrient.ADDED_SUGARS to 10.0,
                ),
            ),
            dv,
        )!!
        assertEquals("2 of 11 met · sodium, sat fat over", day.summary)
        // Only goals still short are "lowest"; met ones never are.
        assertEquals("Lowest: Vitamin D 20%", day.goalsPeek)
        assertEquals("Sodium 136% · Saturated fat 105% · Added sugars 20%", day.limitsPeek)
    }

    @Test fun summaryWithNothingOverOrNothingKnown() {
        val under = DayNutrition.of(listOf(entry(1, "12:00", "Lunch", DvNutrient.FIBER to 30.0, DvNutrient.SODIUM to 100.0)), dv)!!
        assertEquals("1 of 11 met · limits under", under.summary)
        assertEquals("Every known one met", under.goalsPeek)

        val blank = DayNutrition.of(listOf(entry(1, "12:00", "Coffee")), dv)!!
        assertNull(blank.summary)
        assertEquals("No data yet", blank.goalsPeek)
        assertEquals("No data yet", blank.limitsPeek)
    }

    @Test fun entryAmountsFromColumnsAndNutrientRows() {
        val amounts = DayNutrition.amounts(
            fiberG = 14, satFatG = 6, addedSugarsG = null,
            micros = MicronutrientProfile.of(NutrientId.SODIUM to 1480.0, NutrientId.VITAMIN_D to 0.1),
        )
        assertEquals(
            mapOf(
                DvNutrient.FIBER to 14.0,
                DvNutrient.SAT_FAT to 6.0,
                DvNutrient.SODIUM to 1480.0,
                DvNutrient.VITAMIN_D to 0.1,
            ),
            amounts,
        )
    }
}
