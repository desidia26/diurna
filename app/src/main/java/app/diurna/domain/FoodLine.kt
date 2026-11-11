package app.diurna.domain

/** How one food entry's calories read on its collapsed line, and the day's remainder. */
object FoodLine {
    sealed interface Kcal {
        /** A saved meal or scan: exact numbers, shown plain. */
        data class Exact(val text: String) : Kcal
        /** An AI estimate: the midpoint, with "~" so it never reads as exact. */
        data class Estimate(val text: String) : Kcal
        data object Failed : Kcal
        data object Pending : Kcal
    }

    fun kcal(isMeal: Boolean, low: Int?, high: Int?, estimated: Boolean, failed: Boolean): Kcal = when {
        isMeal && low != null -> Kcal.Exact("%,d".format(low))
        estimated && low != null && high != null -> Kcal.Estimate("~%,d".format((low + high) / 2))
        failed -> Kcal.Failed
        else -> Kcal.Pending
    }

    fun kcalLeft(eaten: Int, target: Int): String = when {
        eaten < target -> "%,d left".format(target - eaten)
        eaten > target -> "%,d over".format(eaten - target)
        else -> "on target"
    }
}
