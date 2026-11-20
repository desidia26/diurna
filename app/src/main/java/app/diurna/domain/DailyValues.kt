package app.diurna.domain

/** What a day's % Daily Value is measured against. Changing it re-reads every day; it isn't frozen. */
enum class DvBasis(val wire: String) {
    /** US label values on a 2,000 kcal diet. */
    FDA("fda"),
    /** Label values, with the calorie-linked ones scaled to the day's kcal target. */
    SCALED("scaled"),
    /** US recommended amounts (RDA, or AI where there is none) for the profile's sex and age. */
    AGE_SEX("age_sex"),
    ;

    companion object {
        fun fromWire(s: String?): DvBasis? = entries.firstOrNull { it.wire == s }
    }
}

/** Goals are amounts to reach; limits are ceilings, so past 100% is a warning, not a win. */
enum class DvKind { GOAL, LIMIT }

/**
 * Nutrients with a Daily Value. [micro] names the nutrient_value row it comes from; null means a
 * food_entry column (fiber, saturated fat, added sugars). Cholesterol has no limit in current US
 * guidelines, so it stays an amount in the breakdown rather than a row here.
 */
enum class DvNutrient(
    val label: String,
    /** Lower-case name for running text ("sodium, sat fat over"). */
    val short: String,
    val unit: String,
    val kind: DvKind,
    val fda: Double,
    val scalesWithKcal: Boolean = false,
    val micro: NutrientId? = null,
) {
    FIBER("Fiber", "fiber", "g", DvKind.GOAL, 28.0, scalesWithKcal = true),
    POTASSIUM("Potassium", "potassium", "mg", DvKind.GOAL, 4700.0, micro = NutrientId.POTASSIUM),
    CALCIUM("Calcium", "calcium", "mg", DvKind.GOAL, 1300.0, micro = NutrientId.CALCIUM),
    IRON("Iron", "iron", "mg", DvKind.GOAL, 18.0, micro = NutrientId.IRON),
    MAGNESIUM("Magnesium", "magnesium", "mg", DvKind.GOAL, 420.0, micro = NutrientId.MAGNESIUM),
    ZINC("Zinc", "zinc", "mg", DvKind.GOAL, 11.0, micro = NutrientId.ZINC),
    VITAMIN_A("Vitamin A", "vitamin A", "µg", DvKind.GOAL, 900.0, micro = NutrientId.VITAMIN_A_RAE),
    VITAMIN_C("Vitamin C", "vitamin C", "mg", DvKind.GOAL, 90.0, micro = NutrientId.VITAMIN_C),
    VITAMIN_D("Vitamin D", "vitamin D", "µg", DvKind.GOAL, 20.0, micro = NutrientId.VITAMIN_D),
    VITAMIN_B12("Vitamin B12", "B12", "µg", DvKind.GOAL, 2.4, micro = NutrientId.VITAMIN_B12),
    FOLATE("Folate", "folate", "µg", DvKind.GOAL, 400.0, micro = NutrientId.FOLATE_DFE),
    SODIUM("Sodium", "sodium", "mg", DvKind.LIMIT, 2300.0, micro = NutrientId.SODIUM),
    SAT_FAT("Saturated fat", "sat fat", "g", DvKind.LIMIT, 20.0, scalesWithKcal = true),
    ADDED_SUGARS("Added sugars", "added sugars", "g", DvKind.LIMIT, 50.0, scalesWithKcal = true),
}

object DailyValues {
    private const val FDA_KCAL = 2000.0

    /**
     * Daily Values for one day. [kcalTarget] is that day's target (frozen once finalized);
     * [age] is the profile's age on that day. Missing inputs fall back to the FDA table, as do
     * ages under 19, whose recommended amounts this table doesn't carry.
     */
    fun forDay(basis: DvBasis, kcalTarget: Int?, sex: Sex?, age: Int?): Map<DvNutrient, Double> {
        val fda = DvNutrient.entries.associateWith { it.fda }
        return when (basis) {
            DvBasis.FDA -> fda
            DvBasis.SCALED -> {
                if (kcalTarget == null || kcalTarget <= 0) return fda
                val f = kcalTarget / FDA_KCAL
                fda.mapValues { (n, v) -> if (n.scalesWithKcal) v * f else v }
            }
            DvBasis.AGE_SEX -> {
                if (sex == null || age == null || age < 19) return fda
                fda.mapValues { (n, v) -> if (n.kind == DvKind.LIMIT) v else recommended(n, sex, age) }
            }
        }
    }

    /** Adult DRI values (National Academies), 19+; potassium is the 2019 AI. */
    private fun recommended(n: DvNutrient, sex: Sex, age: Int): Double {
        val male = sex == Sex.MALE
        return when (n) {
            DvNutrient.FIBER -> when {
                male -> if (age <= 50) 38.0 else 30.0
                else -> if (age <= 50) 25.0 else 21.0
            }
            DvNutrient.POTASSIUM -> if (male) 3400.0 else 2600.0
            DvNutrient.CALCIUM -> when {
                male -> if (age <= 70) 1000.0 else 1200.0
                else -> if (age <= 50) 1000.0 else 1200.0
            }
            DvNutrient.IRON -> if (!male && age <= 50) 18.0 else 8.0
            DvNutrient.MAGNESIUM -> when {
                male -> if (age <= 30) 400.0 else 420.0
                else -> if (age <= 30) 310.0 else 320.0
            }
            DvNutrient.ZINC -> if (male) 11.0 else 8.0
            DvNutrient.VITAMIN_A -> if (male) 900.0 else 700.0
            DvNutrient.VITAMIN_C -> if (male) 90.0 else 75.0
            DvNutrient.VITAMIN_D -> if (age <= 70) 15.0 else 20.0
            DvNutrient.VITAMIN_B12 -> 2.4
            DvNutrient.FOLATE -> 400.0
            DvNutrient.SODIUM, DvNutrient.SAT_FAT, DvNutrient.ADDED_SUGARS -> n.fda
        }
    }
}
