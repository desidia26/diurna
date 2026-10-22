package app.diurna.domain

import java.time.LocalDate
import java.time.Period
import kotlin.math.max
import kotlin.math.roundToInt

enum class Sex { MALE, FEMALE }

enum class Activity(val label: String, val factor: Double) {
    SEDENTARY("Sedentary", 1.2),
    LIGHT("Light", 1.375),
    MODERATE("Moderate", 1.55),
    VERY("Very active", 1.725),
}

/** Calories relative to maintenance, and the protein default that suits each goal. */
enum class Goal(val label: String, val kcalDelta: Int, val proteinPerLb: Double) {
    CUT("Cut", -500, 1.0),
    RECOMP("Recomp", -100, 1.1),
    MAINTAIN("Maintain", 0, 0.8),
    LEAN_BULK("Lean bulk", 250, 0.8),
    CUSTOM("Custom", 0, 1.0),
}

data class Profile(
    val sex: Sex,
    val birthDate: LocalDate,
    val heightIn: Double,
    val activity: Activity,
    val goal: Goal,
    /** Only for [Goal.CUSTOM]; other goals use their own delta. */
    val customKcalDelta: Int = 0,
    /** Overrides the goal's protein default when set. */
    val proteinPerLb: Double? = null,
    val fatPercent: Int = 25,
)

data class Targets(val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int)

/** A day's estimated intake so far. Calories use each entry's mid-range; pending entries aren't counted. */
data class NutritionTotals(
    val kcal: Int = 0,
    val proteinG: Int = 0,
    val carbsG: Int = 0,
    val fatG: Int = 0,
    val estimated: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
)

/**
 * Mifflin–St Jeor for resting burn, times an activity factor for maintenance, plus the goal's
 * adjustment. Protein comes from body weight, fat from a share of calories, carbs from what's left.
 */
object TargetMath {
    private const val KG_PER_LB = 0.45359237
    private const val CM_PER_IN = 2.54

    fun bmr(p: Profile, weightLb: Double, on: LocalDate): Double {
        val age = Period.between(p.birthDate, on).years
        val base = 10 * weightLb * KG_PER_LB + 6.25 * p.heightIn * CM_PER_IN - 5 * age
        return base + if (p.sex == Sex.MALE) 5 else -161
    }

    fun maintenance(p: Profile, weightLb: Double, on: LocalDate): Double = bmr(p, weightLb, on) * p.activity.factor

    fun targets(p: Profile, weightLb: Double, on: LocalDate): Targets {
        val delta = if (p.goal == Goal.CUSTOM) p.customKcalDelta else p.goal.kcalDelta
        val kcal = roundTo10(maintenance(p, weightLb, on) + delta)
        val protein = (weightLb * (p.proteinPerLb ?: p.goal.proteinPerLb)).roundToInt()
        val fat = (kcal * p.fatPercent / 100.0 / 9).roundToInt()
        val carbs = max(0, ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt())
        return Targets(kcal, protein, carbs, fat)
    }

    private fun roundTo10(x: Double): Int = (x / 10).roundToInt() * 10
}
