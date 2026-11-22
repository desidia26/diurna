package app.diurna.domain

import kotlin.math.roundToInt

/**
 * A day's intake against its Daily Values, for the Day screen's nutrition drill-down. Built from
 * estimated entries only (as [NutritionTotals] is); an entry with no value for a nutrient leaves
 * it unknown, so each row says how many entries it's known for and its total is a floor.
 */
data class DayNutrition(
    val goals: List<Row>,
    val limits: List<Row>,
    /** Entries considered. */
    val total: Int,
) {
    data class Entry(val entryId: Long, val time: String, val label: String, val amounts: Map<DvNutrient, Double>)

    data class Source(val entryId: Long, val time: String, val label: String, val amount: Double)

    data class Row(
        val nutrient: DvNutrient,
        /** Null when no entry has a value. A real 0 stays 0. */
        val amount: Double?,
        val dv: Double,
        val known: Int,
        val total: Int,
        /** Largest first, at most [MAX_SOURCES]. */
        val sources: List<Source>,
    ) {
        val pct: Int? get() = amount?.let { if (dv > 0) (it / dv * 100).roundToInt() else null }
        val reached: Boolean get() = (pct ?: 0) >= 100
        val partial: Boolean get() = known in 1 until total
    }

    fun row(n: DvNutrient): Row = (goals + limits).first { it.nutrient == n }

    private val anyKnown: Boolean get() = (goals + limits).any { it.amount != null }

    /** One line for the collapsed card: "8 of 11 met · sodium, sat fat over". Null when nothing is known. */
    val summary: String? get() {
        if (!anyKnown) return null
        val met = goals.count { it.reached }
        val over = limits.filter { it.reached }
        val tail = if (over.isEmpty()) "limits under" else over.joinToString(", ") { it.nutrient.short } + " over"
        return "$met of ${goals.size} met · $tail"
    }

    val goalsPeek: String get() {
        if (goals.none { it.amount != null }) return NO_DATA
        val short = goals.filter { it.amount != null && !it.reached }.take(2)
        if (short.isEmpty()) return "Every known one met"
        return "Lowest: " + short.joinToString(", ") { "${it.nutrient.label} ${it.pct}%" }
    }

    val limitsPeek: String get() {
        val known = limits.filter { it.amount != null }
        if (known.isEmpty()) return NO_DATA
        return known.joinToString(" · ") { "${it.nutrient.label} ${it.pct}%" }
    }

    companion object {
        const val MAX_SOURCES = 3
        private const val NO_DATA = "No data yet"

        fun of(entries: List<Entry>, dv: Map<DvNutrient, Double>): DayNutrition? {
            if (entries.isEmpty()) return null
            val rows = DvNutrient.entries.map { n ->
                val withValue = entries.mapNotNull { e -> e.amounts[n]?.let { e to it } }
                Row(
                    nutrient = n,
                    amount = if (withValue.isEmpty()) null else withValue.sumOf { it.second },
                    dv = dv[n] ?: n.fda,
                    known = withValue.size,
                    total = entries.size,
                    sources = withValue.filter { it.second > 0 }.sortedByDescending { it.second }.take(MAX_SOURCES)
                        .map { (e, v) -> Source(e.entryId, e.time, e.label, v) },
                )
            }
            // What needs attention leads: the furthest-short goals, the furthest-over limits; unknown last.
            val goals = rows.filter { it.nutrient.kind == DvKind.GOAL }
                .sortedWith(compareBy<Row> { it.pct == null }.thenBy { it.amount?.div(it.dv) ?: 0.0 })
            val limits = rows.filter { it.nutrient.kind == DvKind.LIMIT }
                .sortedWith(compareBy<Row> { it.pct == null }.thenByDescending { it.amount?.div(it.dv) ?: 0.0 })
            return DayNutrition(goals, limits, entries.size)
        }

        /** One entry's amounts: fiber, sat fat and added sugars from its columns, the rest from its nutrient rows. */
        fun amounts(fiberG: Int?, satFatG: Int?, addedSugarsG: Int?, micros: MicronutrientProfile): Map<DvNutrient, Double> =
            buildMap {
                fiberG?.let { put(DvNutrient.FIBER, it.toDouble()) }
                satFatG?.let { put(DvNutrient.SAT_FAT, it.toDouble()) }
                addedSugarsG?.let { put(DvNutrient.ADDED_SUGARS, it.toDouble()) }
                DvNutrient.entries.forEach { n -> n.micro?.let { id -> micros[id]?.let { put(n, it) } } }
            }
    }
}
