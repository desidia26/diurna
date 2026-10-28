package app.diurna.domain

import kotlin.math.roundToInt

/**
 * Adult indispensable amino-acid average requirements (mg per kg body weight per day)
 * from WHO/FAO/UNU Technical Report Series 935 (2007), Table 23
 * ("Summary of the adult indispensable amino acid requirements").
 *
 * Source: WHO Technical Report Series 935, Protein and amino acid requirements in human
 * nutrition (2007), https://iris.who.int/handle/10665/43411 — Table 23 (p. 150 in the PDF).
 *
 * Pair members are not fully interchangeable: cysteine can spare methionine and tyrosine can
 * spare phenylalanine, but Met and Phe remain indispensable. Requirements are therefore stated
 * primarily as Met+Cys and Phe+Tyr totals (with individual Met/Cys/Phe/Tyr values for detail).
 *
 * Table 23 lists Met 10 and Cys 4 (integers). The consultation’s oxidative-loss derivation
 * (10.4 Met + 4.1 Cys ≈ 14.5, rounded to 15) appears in the surrounding text; we use the
 * published Table 23 integers for reference lines.
 *
 * These are adult average-requirement reference lines, not personalized targets.
 */
object WhoAminoRefs {
    const val HIS_MG_PER_KG = 10
    const val ILE_MG_PER_KG = 20
    const val LEU_MG_PER_KG = 39
    const val LYS_MG_PER_KG = 30
    const val MET_MG_PER_KG = 10
    const val CYS_MG_PER_KG = 4
    const val MET_CYS_MG_PER_KG = 15
    const val PHE_TYR_MG_PER_KG = 25
    const val THR_MG_PER_KG = 15
    const val TRP_MG_PER_KG = 4
    const val VAL_MG_PER_KG = 26

    /** Same conversion Targets uses for BMR (lb → kg). */
    private const val KG_PER_LB = 0.45359237

    /**
     * Daily reference milligrams for [weightLb] (latest logged body weight).
     * Null when weight is missing or non-positive — callers hide the lines.
     */
    fun scaleLb(weightLb: Double?): Scaled? {
        if (weightLb == null || weightLb <= 0.0) return null
        return scaleKg(weightLb * KG_PER_LB)
    }

    fun scaleKg(weightKg: Double?): Scaled? {
        if (weightKg == null || weightKg <= 0.0) return null
        fun s(mgPerKg: Int) = (mgPerKg * weightKg).roundToInt().coerceAtLeast(0)
        return Scaled(
            hisMg = s(HIS_MG_PER_KG),
            ileMg = s(ILE_MG_PER_KG),
            leuMg = s(LEU_MG_PER_KG),
            lysMg = s(LYS_MG_PER_KG),
            metMg = s(MET_MG_PER_KG),
            cysMg = s(CYS_MG_PER_KG),
            metCysMg = s(MET_CYS_MG_PER_KG),
            pheTyrMg = s(PHE_TYR_MG_PER_KG),
            thrMg = s(THR_MG_PER_KG),
            trpMg = s(TRP_MG_PER_KG),
            valMg = s(VAL_MG_PER_KG),
        )
    }

    /** Absolute mg/day references for one body weight. */
    data class Scaled(
        val hisMg: Int,
        val ileMg: Int,
        val leuMg: Int,
        val lysMg: Int,
        val metMg: Int,
        val cysMg: Int,
        val metCysMg: Int,
        val pheTyrMg: Int,
        val thrMg: Int,
        val trpMg: Int,
        val valMg: Int,
    ) {
        fun forPrimaryLabel(label: String): Int? = when (label) {
            "His" -> hisMg
            "Ile" -> ileMg
            "Leu" -> leuMg
            "Lys" -> lysMg
            "Met+Cys" -> metCysMg
            "Phe+Tyr" -> pheTyrMg
            "Thr" -> thrMg
            "Trp" -> trpMg
            "Val" -> valMg
            else -> null
        }

        fun forDetailLabel(label: String): Int? = when (label) {
            "Met" -> metMg
            "Cys" -> cysMg
            "Phe" -> null // Table 23 has no separate Phe; pair only
            "Tyr" -> null
            else -> forPrimaryLabel(label)
        }
    }
}
