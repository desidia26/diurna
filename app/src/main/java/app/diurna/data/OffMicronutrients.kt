package app.diurna.data

import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutrientId
import app.diurna.domain.NutrientRegistry
import org.json.JSONObject

/**
 * Micronutrients from an Open Food Facts `nutriments` object for one basis ([suffix] `_100g`
 * or `_serving`). Pure parse — callers choose the suffix and any later gram scaling.
 *
 * Minerals: plain keys (`sodium_100g`, …) are grams → mg ×1000; `*-mg` keys are already mg and
 * preferred. Salt (NaCl grams) → sodium only when sodium is absent, via
 * [NutrientRegistry.SALT_TO_SODIUM]. Vitamins: prefer RAE / µg / DFE keys; vitamin A IU alone
 * never becomes RAE; vitamin D IU converts with [NutrientRegistry.VITAMIN_D_IU_TO_UG].
 */
fun parseOffMicronutrients(nutriments: JSONObject, suffix: String): MicronutrientProfile? {
    fun num(key: String): Double? =
        nutriments.opt(key)?.toString()?.toDoubleOrNull()?.let { NutrientRegistry.sanitize(it) }

    val pairs = ArrayList<Pair<NutrientId, Double>>()

    fun firstNum(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { num(it) }

    fun mineralMg(id: NutrientId, vararg stems: String) {
        // Prefer explicit mg keys, then grams ×1000.
        for (stem in stems) {
            if (!stem.endsWith("-mg")) continue
            num("$stem$suffix")?.let { pairs += id to it; return }
        }
        for (stem in stems) {
            if (stem.endsWith("-mg")) continue
            val g = num("$stem$suffix") ?: continue
            val mg = NutrientRegistry.sanitize(g * 1000.0) ?: continue
            pairs += id to mg
            return
        }
    }

    mineralMg(NutrientId.SODIUM, "sodium-mg", "sodium")
    if (pairs.none { it.first == NutrientId.SODIUM }) {
        // salt_* is NaCl grams; sodium mass fraction ≈ 0.4.
        num("salt$suffix")?.let { saltG ->
            NutrientRegistry.sanitize(saltG * NutrientRegistry.SALT_TO_SODIUM * 1000.0)?.let {
                pairs += NutrientId.SODIUM to it
            }
        }
    }
    mineralMg(NutrientId.POTASSIUM, "potassium-mg", "potassium")
    mineralMg(NutrientId.CALCIUM, "calcium-mg", "calcium")
    mineralMg(NutrientId.IRON, "iron-mg", "iron")
    mineralMg(NutrientId.MAGNESIUM, "magnesium-mg", "magnesium")
    mineralMg(NutrientId.ZINC, "zinc-mg", "zinc")

    // Vitamin A: RAE/µg keys only — never vitamin-a-iu.
    firstNum("vitamin-a$suffix", "vitamin-a-rae$suffix")?.let {
        pairs += NutrientId.VITAMIN_A_RAE to it
    }

    firstNum("vitamin-c$suffix", "vitamin-c-mg$suffix")?.let {
        pairs += NutrientId.VITAMIN_C to it
    }

    val vitD = firstNum("vitamin-d$suffix", "vitamin-d-µg$suffix", "vitamin-d-ug$suffix")
    when {
        vitD != null -> pairs += NutrientId.VITAMIN_D to vitD
        else -> num("vitamin-d-iu$suffix")?.let { iu ->
            NutrientRegistry.sanitize(iu * NutrientRegistry.VITAMIN_D_IU_TO_UG)?.let {
                pairs += NutrientId.VITAMIN_D to it
            }
        }
    }

    firstNum("vitamin-b12$suffix", "vitamin-b12-µg$suffix", "vitamin-b12-ug$suffix")?.let {
        pairs += NutrientId.VITAMIN_B12 to it
    }

    // DFE only — plain folate / folic-acid ignored.
    firstNum("folate-equivalent$suffix", "vitamin-b9-equivalent$suffix")?.let {
        pairs += NutrientId.FOLATE_DFE to it
    }

    return MicronutrientProfile.fromPairs(pairs).takeIf { it.hasAny }
}
