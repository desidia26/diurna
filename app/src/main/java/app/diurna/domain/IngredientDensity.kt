package app.diurna.domain

import java.util.Locale

/**
 * Unit kind for density / grams-per-each lookups. Volume lines need g/ml; `each` needs g/item.
 * Mass (`g`) needs neither.
 */
enum class DensityUnitKind(val key: String) {
    VOLUME("volume"),
    EACH("each");

    companion object {
        fun of(unit: IngredientUnit): DensityUnitKind? = when {
            unit.needsDensity -> VOLUME
            unit.needsGramsPerEach -> EACH
            else -> null
        }

        fun ofKey(key: String?): DensityUnitKind? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** One household measure from USDA FoodData Central `foodPortions`. */
data class FdcPortion(
    val gramWeight: Double,
    val amount: Double,
    val unitName: String,
    val modifier: String,
    val description: String,
) {
    val ml: Double? get() = IngredientDensity.volumeMl(unitName, amount)
}

/**
 * Parse / derive culinary densities from FDC foodPortions, and normalize ingredient names for
 * cache keys. Pure Kotlin — no Android / network.
 */
object IngredientDensity {
    /** Cache / lookup key: normalized name + unit kind. */
    fun cacheKey(name: String, kind: DensityUnitKind): String =
        "${normalizeName(name)}|${kind.key}"

    /**
     * Lowercase, strip most punctuation, collapse whitespace. Keeps prep words (chopped, sliced…)
     * so "onion, chopped" and "chopped onion" can share a key after token sort of common forms.
     */
    fun normalizeName(raw: String): String {
        var s = raw.trim().lowercase(Locale.US)
        if (s.isEmpty()) return ""
        // Recipe-blog noise: asterisks/footnotes, parenthetical asides ("(or a mix…)"),
        // trailing "to taste" / "as needed". Prep words outside parens stay (chopped, sliced).
        s = s.replace(PAREN_ASIDE, " ")
        s = s.replace(TO_TASTE, " ")
        s = s.replace(NON_ALNUM, " ")
        s = s.replace(WHITESPACE, " ").trim()
        return s
    }

    /** Prep / form words used to prefer matching FDC portions. */
    fun prepModifiers(name: String): Set<String> {
        val tokens = normalizeName(name).split(' ').filter { it.isNotEmpty() }.toSet()
        return PREP_WORDS.intersect(tokens)
    }

    /**
     * Derive g/ml from volume portions (cup / tbsp / tsp / fl oz / ml). Prefers cup, then tbsp,
     * tsp, fl oz, ml. When [preferModifiers] is non-empty, portions whose modifier/description
     * contain any of those words win over others of the same unit.
     */
    fun densityFromPortions(
        portions: List<FdcPortion>,
        preferModifiers: Set<String> = emptySet(),
    ): Double? {
        val volume = portions.mapNotNull { p ->
            val ml = p.ml ?: return@mapNotNull null
            if (ml <= 0 || p.gramWeight <= 0) return@mapNotNull null
            val gPerMl = p.gramWeight / ml
            if (!gPerMl.isFinite() || gPerMl <= 0 || gPerMl > MAX_G_PER_ML) return@mapNotNull null
            Ranked(gPerMl, volumeRank(p.unitName), modifierScore(p, preferModifiers), p)
        }
        if (volume.isEmpty()) return null
        val best = volume.minWith(compareBy<Ranked> { -it.modScore }.thenBy { it.unitRank })
        return roundDensity(best.value)
    }

    /**
     * Grams for one "each" / medium / large / whole item from count-style portions.
     */
    fun gramsPerEachFromPortions(
        portions: List<FdcPortion>,
        preferModifiers: Set<String> = emptySet(),
    ): Double? {
        val counts = portions.mapNotNull { p ->
            val per = countGrams(p) ?: return@mapNotNull null
            if (!per.isFinite() || per <= 0 || per > MAX_G_EACH) return@mapNotNull null
            Ranked(per, countRank(p), modifierScore(p, preferModifiers), p)
        }
        if (counts.isEmpty()) return null
        val best = counts.minWith(compareBy<Ranked> { -it.modScore }.thenBy { it.unitRank })
        return roundGrams(best.value)
    }

    /** Parse the `foodPortions` array from an FDC food JSON object (as org.json-compatible maps). */
    fun parsePortionsFromJson(foodPortions: org.json.JSONArray?): List<FdcPortion> {
        if (foodPortions == null || foodPortions.length() == 0) return emptyList()
        val out = ArrayList<FdcPortion>(foodPortions.length())
        for (i in 0 until foodPortions.length()) {
            val o = foodPortions.optJSONObject(i) ?: continue
            val grams = o.optDouble("gramWeight", Double.NaN)
            if (!grams.isFinite() || grams <= 0) continue
            val amount = o.optDouble("value", Double.NaN).let { v ->
                if (v.isFinite() && v > 0) v else 1.0
            }
            val unitObj = o.optJSONObject("measureUnit")
            val unitName = sequenceOf(
                unitObj?.optString("abbreviation").orEmpty(),
                unitObj?.optString("name").orEmpty(),
                o.optString("portionDescription").orEmpty(),
            ).map { it.trim().lowercase(Locale.US) }.firstOrNull { it.isNotEmpty() && it != "undetermined" }
                ?: ""
            val modifier = o.optString("modifier").orEmpty().trim().lowercase(Locale.US)
            val description = o.optString("portionDescription").orEmpty().trim().lowercase(Locale.US)
            out += FdcPortion(grams, amount, unitName, modifier, description)
        }
        return out
    }

    /** Match a hand-list [IngredientDensities] option by normalized token overlap. */
    fun matchBuiltinDensity(name: String): DensityOption? {
        val tokens = normalizeName(name).split(' ').filter { it.length >= 3 }.toSet()
        if (tokens.isEmpty()) return null
        return IngredientDensities.OPTIONS.firstOrNull { opt ->
            val idTok = opt.id.replace('_', ' ')
            val labelTok = opt.label.substringBefore("(").lowercase(Locale.US)
            val hay = normalizeName("$idTok $labelTok").split(' ').filter { it.length >= 3 }.toSet()
            hay.any { it in tokens }
        }
    }

    /**
     * True when [fdcDescription] shares a meaningful token (≥3 letters) with [ingredientName].
     * Also matches compound forms ("soy milk" ↔ "Soymilk") via concatenation / substring.
     * Used before filling nutrition from the same FDC food that supplied density.
     */
    fun isGoodFdcNameMatch(ingredientName: String, fdcDescription: String): Boolean {
        val want = normalizeName(ingredientName).split(' ').filter { it.length >= 3 }
        if (want.isEmpty()) return false
        val hay = normalizeName(fdcDescription).split(' ').filter { it.length >= 3 }
        if (hay.isEmpty()) return false
        val wantSet = want.toSet()
        val haySet = hay.toSet()
        if (wantSet.any { it in haySet }) return true
        // "soy milk" ↔ "soymilk": joined tokens appear in / contain the other side.
        val wantJoined = want.joinToString("")
        val hayJoined = hay.joinToString("")
        if (wantJoined.length >= 4 && (
            hay.any { wantJoined in it || (it.length >= 4 && it in wantJoined) } ||
                wantJoined in hayJoined || hayJoined in wantJoined
            )
        ) {
            return true
        }
        // Token contained in the other side's token ("milk" in "soymilk")
        for (w in want) {
            if (w.length >= 4 && hay.any { h -> w in h || (h.length >= 4 && h in w) }) return true
        }
        return false
    }

    // ---- internals ----

    private data class Ranked(val value: Double, val unitRank: Int, val modScore: Int, val portion: FdcPortion)

    fun volumeMl(unitName: String, amount: Double): Double? {
        val u = unitName.lowercase(Locale.US)
        val per = when {
            u == "cup" || u.startsWith("cup ") || " cup" in u || u == "cups" -> IngredientUnits.ML_PER_CUP
            u == "tbsp" || u == "tbs" || u == "tablespoon" || u.startsWith("tablespoon") -> IngredientUnits.ML_PER_TBSP
            u == "tsp" || u == "teaspoon" || u.startsWith("teaspoon") -> IngredientUnits.ML_PER_TSP
            u == "fl oz" || u == "floz" || u.contains("fl oz") || u.contains("fluid ounce") -> ML_PER_FL_OZ
            u == "ml" || u == "milliliter" || u == "millilitre" -> 1.0
            // portionDescription like "1 cup, chopped"
            u.contains("cup") -> IngredientUnits.ML_PER_CUP
            u.contains("tablespoon") || u.contains("tbsp") -> IngredientUnits.ML_PER_TBSP
            u.contains("teaspoon") || Regex("""\btsp\b""").containsMatchIn(u) -> IngredientUnits.ML_PER_TSP
            else -> return null
        }
        // When unit comes from portionDescription ("1 cup"), amount is often already in the text;
        // FDC still sets value=1 for "1 cup". Use amount as multiplier.
        return amount * per
    }

    private fun volumeRank(unitName: String): Int {
        val u = unitName.lowercase(Locale.US)
        return when {
            u.contains("cup") -> 0
            u.contains("tablespoon") || u.contains("tbsp") || u == "tbs" -> 1
            u.contains("teaspoon") || Regex("""\btsp\b""").containsMatchIn(u) -> 2
            u.contains("fl oz") || u.contains("fluid") -> 3
            u.contains("ml") || u.contains("millil") -> 4
            else -> 9
        }
    }

    private fun countGrams(p: FdcPortion): Double? {
        val text = "${p.unitName} ${p.modifier} ${p.description}"
        val isCount = COUNT_HINTS.any { it in text } ||
            p.unitName in setOf("each", "fruit", "egg", "unit", "item", "piece", "whole") ||
            (p.unitName.isEmpty() || p.unitName == "undetermined") && COUNT_HINTS.any { it in p.description }
        if (!isCount) {
            // "1 medium" in description with undetermined unit
            if (!COUNT_SIZE.any { it in text }) return null
        }
        val amount = p.amount.takeIf { it > 0 } ?: 1.0
        return p.gramWeight / amount
    }

    private fun countRank(p: FdcPortion): Int {
        val text = "${p.modifier} ${p.description} ${p.unitName}"
        return when {
            "medium" in text -> 0
            "each" in text || "whole" in text || "unit" in text -> 1
            "large" in text -> 2
            "small" in text -> 3
            "extra large" in text || "extra-large" in text -> 4
            else -> 5
        }
    }

    private fun modifierScore(p: FdcPortion, prefer: Set<String>): Int {
        if (prefer.isEmpty()) return 0
        val text = "${p.modifier} ${p.description}"
        return prefer.count { it in text }
    }

    private fun roundDensity(v: Double): Double = (v * 1000.0).let { Math.round(it) / 1000.0 }
    private fun roundGrams(v: Double): Double = (v * 10.0).let { Math.round(it) / 10.0 }

    private val PREP_WORDS = setOf(
        "chopped", "sliced", "diced", "minced", "grated", "shredded", "ground",
        "packed", "melted", "cooked", "raw", "boiled", "drained", "mashed",
        "peeled", "seeded", "unsalted", "salted", "dry", "dried", "fresh",
        "canned", "frozen", "roasted", "toasted", "sifted",
    )

    private val COUNT_HINTS = listOf(
        "medium", "large", "small", "each", "whole", "unit", "fruit", "egg",
        "piece", "items", "item", "extra large", "extra-large",
    )
    private val COUNT_SIZE = listOf("medium", "large", "small", "extra large", "extra-large")

    private const val ML_PER_FL_OZ = 30.0 // US labeling floz ≈ 30 ml
    private const val MAX_G_PER_ML = 3.0 // denser than honey is suspicious for culinary volumes
    private const val MAX_G_EACH = 5_000.0

    /** Any parenthetical aside — blog notes like "(or a mix of …)" / "(optional)". */
    private val PAREN_ASIDE = Regex("""\([^)]*\)""")
    private val TO_TASTE = Regex("""\b(to taste|as needed|as desired)\b""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^a-z0-9+]+""")
    private val WHITESPACE = Regex("""\s+""")

    /** Sources recorded on the density cache / applied lines. */
    object Source {
        const val USDA = "usda"
        const val AI = "ai"
        const val BUILTIN = "builtin"
        const val MANUAL = "manual"
        /** Cached negative / not-yet-available; subject to retry_after. */
        const val MISS = "miss"
        const val PENDING = "pending"
    }
}

/** Result of looking up density or grams-per-each for one ingredient. */
data class DensityLookup(
    val gPerMl: Double? = null,
    val gramsPerEach: Double? = null,
    val source: String,
    val fdcId: Int? = null,
    val basis: String? = null,
    val description: String? = null,
) {
    val isEstimate: Boolean get() = source == IngredientDensity.Source.AI
    val hasValue: Boolean get() = (gPerMl != null && gPerMl > 0) || (gramsPerEach != null && gramsPerEach > 0)
}
