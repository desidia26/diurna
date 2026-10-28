package app.diurna.domain

/**
 * Essential amino acids plus cysteine and tyrosine (for met+cys / phe+tyr protein-quality pairs).
 * Values are milligrams for the entry or day; null = unknown (never treated as 0 for coverage).
 */
data class AminoAcids(
    val hisMg: Int? = null,
    val ileMg: Int? = null,
    val leuMg: Int? = null,
    val lysMg: Int? = null,
    val metMg: Int? = null,
    val pheMg: Int? = null,
    val thrMg: Int? = null,
    val trpMg: Int? = null,
    val valMg: Int? = null,
    val cysMg: Int? = null,
    val tyrMg: Int? = null,
) {
    val hasAny: Boolean
        get() = hisMg != null || ileMg != null || leuMg != null || lysMg != null ||
            metMg != null || pheMg != null || thrMg != null || trpMg != null ||
            valMg != null || cysMg != null || tyrMg != null

    /** met+cys when either is known; null when both unknown. */
    val metCysMg: Int? get() = sumKnown(metMg, cysMg)

    /** phe+tyr when either is known; null when both unknown. */
    val pheTyrMg: Int? get() = sumKnown(pheMg, tyrMg)

    fun scale(factor: Double): AminoAcids = AminoAcids(
        hisMg = scale(hisMg, factor), ileMg = scale(ileMg, factor), leuMg = scale(leuMg, factor),
        lysMg = scale(lysMg, factor), metMg = scale(metMg, factor), pheMg = scale(pheMg, factor),
        thrMg = scale(thrMg, factor), trpMg = scale(trpMg, factor), valMg = scale(valMg, factor),
        cysMg = scale(cysMg, factor), tyrMg = scale(tyrMg, factor),
    )

    companion object {
        /** All individual fields (entry detail / expanded pair members). */
        val LABELS = listOf(
            "His" to AminoAcids::hisMg,
            "Ile" to AminoAcids::ileMg,
            "Leu" to AminoAcids::leuMg,
            "Lys" to AminoAcids::lysMg,
            "Met" to AminoAcids::metMg,
            "Phe" to AminoAcids::pheMg,
            "Thr" to AminoAcids::thrMg,
            "Trp" to AminoAcids::trpMg,
            "Val" to AminoAcids::valMg,
            "Cys" to AminoAcids::cysMg,
            "Tyr" to AminoAcids::tyrMg,
        )

        /**
         * Primary Metrics bars: other EAAs individually; sulfur and aromatic as WHO pairs.
         * Met/Cys/Phe/Tyr appear separately in the expanded detail under the pairs.
         */
        val PRIMARY_BARS: List<Pair<String, (AminoAcids) -> Int?>> = listOf(
            "His" to AminoAcids::hisMg,
            "Ile" to AminoAcids::ileMg,
            "Leu" to AminoAcids::leuMg,
            "Lys" to AminoAcids::lysMg,
            "Met+Cys" to AminoAcids::metCysMg,
            "Phe+Tyr" to AminoAcids::pheTyrMg,
            "Thr" to AminoAcids::thrMg,
            "Trp" to AminoAcids::trpMg,
            "Val" to AminoAcids::valMg,
        )

        val PAIR_DETAIL: List<Pair<String, (AminoAcids) -> Int?>> = listOf(
            "Met" to AminoAcids::metMg,
            "Cys" to AminoAcids::cysMg,
            "Phe" to AminoAcids::pheMg,
            "Tyr" to AminoAcids::tyrMg,
        )

        private fun scale(v: Int?, factor: Double): Int? = v?.let { (it * factor).toInt().coerceAtLeast(0) }

        private fun sumKnown(a: Int?, b: Int?): Int? = when {
            a == null && b == null -> null
            else -> (a ?: 0) + (b ?: 0)
        }

        /** Sums known fields across days/entries; a field stays null if every input left it unknown. */
        fun sum(items: Iterable<AminoAcids>): AminoAcids {
            fun field(pick: (AminoAcids) -> Int?): Int? {
                var any = false
                var s = 0
                for (a in items) {
                    val v = pick(a) ?: continue
                    any = true
                    s += v
                }
                return if (any) s else null
            }
            return AminoAcids(
                hisMg = field { it.hisMg }, ileMg = field { it.ileMg }, leuMg = field { it.leuMg },
                lysMg = field { it.lysMg }, metMg = field { it.metMg }, pheMg = field { it.pheMg },
                thrMg = field { it.thrMg }, trpMg = field { it.trpMg }, valMg = field { it.valMg },
                cysMg = field { it.cysMg }, tyrMg = field { it.tyrMg },
            )
        }

        /** Mean of known values per field over [items] that have that field; null when none. */
        fun average(items: List<AminoAcids>): AminoAcids? {
            if (items.isEmpty() || items.none { it.hasAny }) return null
            fun field(pick: (AminoAcids) -> Int?): Int? {
                val known = items.mapNotNull(pick)
                return if (known.isEmpty()) null else known.average().toInt()
            }
            return AminoAcids(
                hisMg = field { it.hisMg }, ileMg = field { it.ileMg }, leuMg = field { it.leuMg },
                lysMg = field { it.lysMg }, metMg = field { it.metMg }, pheMg = field { it.pheMg },
                thrMg = field { it.thrMg }, trpMg = field { it.trpMg }, valMg = field { it.valMg },
                cysMg = field { it.cysMg }, tyrMg = field { it.tyrMg },
            )
        }
    }
}

/** One ingredient the model named for FDC lookup; [matchedDescription]/[fdcId] filled after USDA match. */
data class FoodIngredient(
    val name: String,
    val grams: Double,
    val fdcId: Int? = null,
    val matchedDescription: String? = null,
)
