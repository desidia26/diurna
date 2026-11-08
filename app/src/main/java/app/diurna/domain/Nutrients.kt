package app.diurna.domain

/**
 * First-wave micronutrients for daily totals (no personalized targets yet).
 *
 * Amounts are always in [NutrientId.unit]. Null / absent means unknown — never treat as zero
 * when aggregating coverage. A stored 0.0 is a real measured zero (e.g. water's sodium).
 *
 * Vitamin A is RAE only: IU→RAE depends on retinol vs carotenoids, so IU alone is ignored.
 * Folate is DFE only: food folate + folic acid need the DFE formula, so total/folic-acid alone
 * is ignored. Vitamin D IU→µg uses the fixed factor [NutrientRegistry.VITAMIN_D_IU_TO_UG].
 * Salt→sodium uses [NutrientRegistry.SALT_TO_SODIUM] only when sodium itself is absent.
 */
enum class NutrientUnit {
    MG,
    /** Micrograms (µg). */
    UG,
}

enum class NutrientId(val id: String, val displayName: String, val unit: NutrientUnit) {
    SODIUM("sodium", "Sodium", NutrientUnit.MG),
    POTASSIUM("potassium", "Potassium", NutrientUnit.MG),
    CALCIUM("calcium", "Calcium", NutrientUnit.MG),
    IRON("iron", "Iron", NutrientUnit.MG),
    MAGNESIUM("magnesium", "Magnesium", NutrientUnit.MG),
    ZINC("zinc", "Zinc", NutrientUnit.MG),
    VITAMIN_A_RAE("vitamin_a_rae", "Vitamin A", NutrientUnit.UG),
    VITAMIN_C("vitamin_c", "Vitamin C", NutrientUnit.MG),
    VITAMIN_D("vitamin_d", "Vitamin D", NutrientUnit.UG),
    VITAMIN_B12("vitamin_b12", "Vitamin B12", NutrientUnit.UG),
    FOLATE_DFE("folate_dfe", "Folate", NutrientUnit.UG),
    ;

    companion object {
        fun fromId(id: String): NutrientId? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Source-key mapping for one nutrient. [fdcIds] are preferred first among FDC nutrient numbers
 * that already express the canonical unit (or a documented conversion). [offKeys] are Open Food
 * Facts nutriment stems without the `_100g` / `_serving` suffix; interpretation is in the OFF
 * parser (grams→mg for minerals, IU handling for D, etc.).
 */
data class NutrientDef(
    val id: NutrientId,
    val fdcIds: List<Int>,
    val offKeys: List<String>,
)

object NutrientRegistry {
    /**
     * Mass fraction of sodium in NaCl. OFF `salt_*` is NaCl grams; sodium grams ≈ salt × 0.4.
     * Used only when a direct sodium value is absent.
     */
    const val SALT_TO_SODIUM = 0.4

    /** 1 IU vitamin D = 0.025 µg (form-independent). */
    const val VITAMIN_D_IU_TO_UG = 0.025

    /**
     * Soft cap in canonical units: reject vandalism / unit mistakes. Minerals and vitamin C
     * in mg; A/D/B12/folate in µg. Far above any food per 100 g or serving.
     */
    const val MAX_AMOUNT = 1_000_000.0

    private val defs: Map<NutrientId, NutrientDef> = mapOf(
        NutrientId.SODIUM to NutrientDef(
            NutrientId.SODIUM,
            fdcIds = listOf(1093),
            offKeys = listOf("sodium-mg", "sodium"),
        ),
        NutrientId.POTASSIUM to NutrientDef(
            NutrientId.POTASSIUM,
            fdcIds = listOf(1092),
            offKeys = listOf("potassium-mg", "potassium"),
        ),
        NutrientId.CALCIUM to NutrientDef(
            NutrientId.CALCIUM,
            fdcIds = listOf(1087),
            offKeys = listOf("calcium-mg", "calcium"),
        ),
        NutrientId.IRON to NutrientDef(
            NutrientId.IRON,
            fdcIds = listOf(1089),
            offKeys = listOf("iron-mg", "iron"),
        ),
        NutrientId.MAGNESIUM to NutrientDef(
            NutrientId.MAGNESIUM,
            fdcIds = listOf(1090),
            offKeys = listOf("magnesium-mg", "magnesium"),
        ),
        NutrientId.ZINC to NutrientDef(
            NutrientId.ZINC,
            fdcIds = listOf(1095),
            offKeys = listOf("zinc-mg", "zinc"),
        ),
        // 1106 = Vitamin A, RAE (µg). 1104 IU deliberately omitted.
        NutrientId.VITAMIN_A_RAE to NutrientDef(
            NutrientId.VITAMIN_A_RAE,
            fdcIds = listOf(1106),
            offKeys = listOf("vitamin-a", "vitamin-a-rae"),
        ),
        NutrientId.VITAMIN_C to NutrientDef(
            NutrientId.VITAMIN_C,
            fdcIds = listOf(1162),
            offKeys = listOf("vitamin-c", "vitamin-c-mg"),
        ),
        // 1114 = Vitamin D (D2 + D3) µg. 1110 IU is a fallback with VITAMIN_D_IU_TO_UG.
        NutrientId.VITAMIN_D to NutrientDef(
            NutrientId.VITAMIN_D,
            fdcIds = listOf(1114),
            offKeys = listOf("vitamin-d", "vitamin-d-µg", "vitamin-d-ug"),
        ),
        NutrientId.VITAMIN_B12 to NutrientDef(
            NutrientId.VITAMIN_B12,
            fdcIds = listOf(1178),
            offKeys = listOf("vitamin-b12", "vitamin-b12-µg", "vitamin-b12-ug"),
        ),
        // 1177 = Folate, DFE. 1176 total / 1186 folic acid deliberately omitted.
        NutrientId.FOLATE_DFE to NutrientDef(
            NutrientId.FOLATE_DFE,
            fdcIds = listOf(1177),
            offKeys = listOf("folate-equivalent", "vitamin-b9-equivalent"),
        ),
    )

    fun def(id: NutrientId): NutrientDef = defs.getValue(id)

    fun all(): List<NutrientDef> = NutrientId.entries.map { defs.getValue(it) }

    /** Finite and in [0, MAX_AMOUNT]; else null. */
    fun sanitize(amount: Double): Double? =
        amount.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_AMOUNT }
}

/**
 * Known micronutrient amounts in canonical units. Absent key = unknown (not zero).
 */
data class MicronutrientProfile(
    private val values: Map<NutrientId, Double> = emptyMap(),
) {
    operator fun get(id: NutrientId): Double? = values[id]

    val hasAny: Boolean get() = values.isNotEmpty()

    fun asMap(): Map<NutrientId, Double> = values

    fun scale(factor: Double): MicronutrientProfile {
        if (!factor.isFinite() || factor < 0.0) return MicronutrientProfile()
        if (values.isEmpty()) return this
        val out = LinkedHashMap<NutrientId, Double>(values.size)
        for ((id, v) in values) {
            val scaled = NutrientRegistry.sanitize(v * factor) ?: continue
            out[id] = scaled
        }
        return MicronutrientProfile(out)
    }

    companion object {
        fun of(vararg pairs: Pair<NutrientId, Double>): MicronutrientProfile =
            fromPairs(pairs.asList())

        fun fromPairs(pairs: Iterable<Pair<NutrientId, Double>>): MicronutrientProfile {
            val out = LinkedHashMap<NutrientId, Double>()
            for ((id, raw) in pairs) {
                val v = NutrientRegistry.sanitize(raw) ?: continue
                out[id] = v
            }
            return MicronutrientProfile(out)
        }

        /** Sums known fields; a nutrient stays absent if every input left it unknown. */
        fun sum(items: Iterable<MicronutrientProfile>): MicronutrientProfile {
            val out = LinkedHashMap<NutrientId, Double>()
            for (id in NutrientId.entries) {
                var any = false
                var s = 0.0
                for (p in items) {
                    val v = p[id] ?: continue
                    any = true
                    s += v
                }
                if (any) {
                    val sanitized = NutrientRegistry.sanitize(s) ?: continue
                    out[id] = sanitized
                }
            }
            return MicronutrientProfile(out)
        }
    }
}

/**
 * Day- or recipe-level sum with how many items contributed any micronutrient value.
 * Completeness is per item (like amino matched/total), not per nutrient.
 */
data class MicronutrientAggregate(
    val profile: MicronutrientProfile,
    val contributing: Int,
    val total: Int,
) {
    val partial: Boolean get() = contributing > 0 && contributing < total

    val completenessLabel: String get() = "from $contributing of $total items"

    companion object {
        fun of(items: List<MicronutrientProfile>): MicronutrientAggregate {
            val contributing = items.count { it.hasAny }
            return MicronutrientAggregate(
                profile = MicronutrientProfile.sum(items),
                contributing = contributing,
                total = items.size,
            )
        }
    }
}
