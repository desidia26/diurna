package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyValuesTest {

    private fun fda() = DailyValues.forDay(DvBasis.FDA, kcalTarget = 2400, sex = Sex.MALE, age = 40)

    @Test fun fdaIsTheLabelTableWhateverTheProfile() {
        val dv = fda()
        assertEquals(28.0, dv.getValue(DvNutrient.FIBER), 0.0)
        assertEquals(20.0, dv.getValue(DvNutrient.SAT_FAT), 0.0)
        assertEquals(50.0, dv.getValue(DvNutrient.ADDED_SUGARS), 0.0)
        assertEquals(2300.0, dv.getValue(DvNutrient.SODIUM), 0.0)
        assertEquals(4700.0, dv.getValue(DvNutrient.POTASSIUM), 0.0)
        assertEquals(1300.0, dv.getValue(DvNutrient.CALCIUM), 0.0)
        assertEquals(18.0, dv.getValue(DvNutrient.IRON), 0.0)
        assertEquals(420.0, dv.getValue(DvNutrient.MAGNESIUM), 0.0)
        assertEquals(11.0, dv.getValue(DvNutrient.ZINC), 0.0)
        assertEquals(900.0, dv.getValue(DvNutrient.VITAMIN_A), 0.0)
        assertEquals(90.0, dv.getValue(DvNutrient.VITAMIN_C), 0.0)
        assertEquals(20.0, dv.getValue(DvNutrient.VITAMIN_D), 0.0)
        assertEquals(2.4, dv.getValue(DvNutrient.VITAMIN_B12), 0.0)
        assertEquals(400.0, dv.getValue(DvNutrient.FOLATE), 0.0)
        assertEquals(DvNutrient.entries.toSet(), dv.keys)
    }

    @Test fun everyNutrientMapsToOneSource() {
        // Fiber, sat fat and added sugars come from entry columns; the rest from nutrient rows.
        DvNutrient.entries.forEach { n ->
            val fromColumn = n in setOf(DvNutrient.FIBER, DvNutrient.SAT_FAT, DvNutrient.ADDED_SUGARS)
            assertEquals(n.name, fromColumn, n.micro == null)
        }
        assertEquals(NutrientId.entries.toSet(), DvNutrient.entries.mapNotNull { it.micro }.toSet())
    }

    @Test fun limitsAreSodiumSatFatAndAddedSugarsOnly() {
        assertEquals(
            setOf(DvNutrient.SODIUM, DvNutrient.SAT_FAT, DvNutrient.ADDED_SUGARS),
            DvNutrient.entries.filter { it.kind == DvKind.LIMIT }.toSet(),
        )
    }

    @Test fun scaledMovesOnlyCalorieLinkedValues() {
        val dv = DailyValues.forDay(DvBasis.SCALED, kcalTarget = 2400, sex = null, age = null)
        assertEquals(33.6, dv.getValue(DvNutrient.FIBER), 1e-9)
        assertEquals(24.0, dv.getValue(DvNutrient.SAT_FAT), 1e-9)
        assertEquals(60.0, dv.getValue(DvNutrient.ADDED_SUGARS), 1e-9)
        assertEquals(2300.0, dv.getValue(DvNutrient.SODIUM), 0.0)
        assertEquals(18.0, dv.getValue(DvNutrient.IRON), 0.0)
    }

    @Test fun scaledWithoutATargetIsFda() {
        assertEquals(fda(), DailyValues.forDay(DvBasis.SCALED, kcalTarget = null, sex = Sex.MALE, age = 40))
        assertEquals(fda(), DailyValues.forDay(DvBasis.SCALED, kcalTarget = 0, sex = Sex.MALE, age = 40))
    }

    @Test fun ageSexForAMan() {
        val dv = DailyValues.forDay(DvBasis.AGE_SEX, kcalTarget = 2400, sex = Sex.MALE, age = 40)
        assertEquals(38.0, dv.getValue(DvNutrient.FIBER), 0.0)
        assertEquals(3400.0, dv.getValue(DvNutrient.POTASSIUM), 0.0)
        assertEquals(1000.0, dv.getValue(DvNutrient.CALCIUM), 0.0)
        assertEquals(8.0, dv.getValue(DvNutrient.IRON), 0.0)
        assertEquals(420.0, dv.getValue(DvNutrient.MAGNESIUM), 0.0)
        assertEquals(11.0, dv.getValue(DvNutrient.ZINC), 0.0)
        assertEquals(900.0, dv.getValue(DvNutrient.VITAMIN_A), 0.0)
        assertEquals(90.0, dv.getValue(DvNutrient.VITAMIN_C), 0.0)
        assertEquals(15.0, dv.getValue(DvNutrient.VITAMIN_D), 0.0)
        // Limits don't depend on age or sex.
        assertEquals(2300.0, dv.getValue(DvNutrient.SODIUM), 0.0)
        assertEquals(20.0, dv.getValue(DvNutrient.SAT_FAT), 0.0)
        assertEquals(50.0, dv.getValue(DvNutrient.ADDED_SUGARS), 0.0)
    }

    @Test fun ageSexForAWomanByBand() {
        val young = DailyValues.forDay(DvBasis.AGE_SEX, null, Sex.FEMALE, 25)
        assertEquals(25.0, young.getValue(DvNutrient.FIBER), 0.0)
        assertEquals(18.0, young.getValue(DvNutrient.IRON), 0.0)
        assertEquals(310.0, young.getValue(DvNutrient.MAGNESIUM), 0.0)
        assertEquals(1000.0, young.getValue(DvNutrient.CALCIUM), 0.0)
        assertEquals(700.0, young.getValue(DvNutrient.VITAMIN_A), 0.0)
        assertEquals(75.0, young.getValue(DvNutrient.VITAMIN_C), 0.0)
        assertEquals(8.0, young.getValue(DvNutrient.ZINC), 0.0)
        assertEquals(2600.0, young.getValue(DvNutrient.POTASSIUM), 0.0)

        val older = DailyValues.forDay(DvBasis.AGE_SEX, null, Sex.FEMALE, 55)
        assertEquals(21.0, older.getValue(DvNutrient.FIBER), 0.0)
        assertEquals(8.0, older.getValue(DvNutrient.IRON), 0.0)
        assertEquals(320.0, older.getValue(DvNutrient.MAGNESIUM), 0.0)
        assertEquals(1200.0, older.getValue(DvNutrient.CALCIUM), 0.0)
        assertEquals(15.0, older.getValue(DvNutrient.VITAMIN_D), 0.0)

        assertEquals(20.0, DailyValues.forDay(DvBasis.AGE_SEX, null, Sex.FEMALE, 75).getValue(DvNutrient.VITAMIN_D), 0.0)
    }

    @Test fun bandEdges() {
        fun mg(age: Int) = DailyValues.forDay(DvBasis.AGE_SEX, null, Sex.MALE, age).getValue(DvNutrient.MAGNESIUM)
        fun fiber(age: Int) = DailyValues.forDay(DvBasis.AGE_SEX, null, Sex.MALE, age).getValue(DvNutrient.FIBER)
        fun calcium(age: Int) = DailyValues.forDay(DvBasis.AGE_SEX, null, Sex.MALE, age).getValue(DvNutrient.CALCIUM)
        assertEquals(400.0, mg(19), 0.0)
        assertEquals(400.0, mg(30), 0.0)
        assertEquals(420.0, mg(31), 0.0)
        assertEquals(38.0, fiber(50), 0.0)
        assertEquals(30.0, fiber(51), 0.0)
        assertEquals(1000.0, calcium(70), 0.0)
        assertEquals(1200.0, calcium(71), 0.0)
    }

    @Test fun ageSexWithoutAProfileOrUnder19IsFda() {
        assertEquals(fda(), DailyValues.forDay(DvBasis.AGE_SEX, 2400, sex = null, age = 40))
        assertEquals(fda(), DailyValues.forDay(DvBasis.AGE_SEX, 2400, sex = Sex.MALE, age = null))
        assertEquals(fda(), DailyValues.forDay(DvBasis.AGE_SEX, 2400, sex = Sex.MALE, age = 17))
    }

    @Test fun basisWireRoundTripsAndUnknownIsNull() {
        DvBasis.entries.forEach { assertEquals(it, DvBasis.fromWire(it.wire)) }
        assertNull(DvBasis.fromWire("nonsense"))
    }
}
