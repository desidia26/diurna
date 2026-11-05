package app.diurna.eval

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import java.io.File

/** One expected numeric band, inclusive. */
data class IntRangeExpect(val min: Int, val max: Int) {
    fun contains(v: Int): Boolean = v in min..max
}

/** Inclusive double band for density / grams-per-each. */
data class DoubleRangeExpect(val min: Double, val max: Double) {
    fun contains(v: Double): Boolean = v in min..max
}

/**
 * What a case checks. [outcome] is one of:
 * - estimate / reading: a successful structured reply in expected bands
 * - not_food: note mentions not food (or similar) and macros near zero
 * - not_food_or_zeros: not_food, or all-zero macros/kcal
 * - reject: parser or model fails (cardio non-screen, label non-panel, etc.)
 * - ingredients: barcode_ingredients prompt returned a usable list
 */
data class CaseExpect(
    val outcome: String,
    val kcalMid: Int? = null,
    val kcalTolPct: Double? = null,
    val proteinG: IntRangeExpect? = null,
    val carbsG: IntRangeExpect? = null,
    val fatG: IntRangeExpect? = null,
    val macrosConsistentWithKcal: Boolean = false,
    val noteMaxLen: Int? = null,
    val distanceM: IntRangeExpect? = null,
    val durationS: IntRangeExpect? = null,
    val kcal: IntRangeExpect? = null,
    val avgHr: IntRangeExpect? = null,
    /** barcode_ingredients: minimum ingredient count when outcome is ingredients. */
    val minIngredients: Int? = null,
    /** barcode_ingredients: each listed substring must appear in some ingredient name. */
    val nameContainsAny: List<String> = emptyList(),
    /** Food detailed path: at least one sub-macro field present (nullable ints). */
    val requireDetail: Boolean = false,
    /** Food ingredients path: non-empty ingredient list. */
    val requireIngredients: Boolean = false,
    /** Density prompt: expected g/ml band. */
    val gPerMl: DoubleRangeExpect? = null,
    /** Density prompt: expected grams-per-each band. */
    val gramsPerEach: DoubleRangeExpect? = null,
)

data class CaseInput(
    val text: String? = null,
    val image: String? = null,
    val productName: String? = null,
    val brand: String? = null,
    val servingGrams: Double? = null,
    val servingSize: String? = null,
    val ingredientsText: String? = null,
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val carbsG: Int? = null,
    val fatG: Int? = null,
    /** When true, call EstimatePrompt.system/schema(detailed=true). */
    val detailed: Boolean = false,
    /** When true, also request ingredients (implies detailed in the app). */
    val ingredients: Boolean = false,
    /** Density prompt: "volume" or "each". */
    val unitKind: String? = null,
)

data class EvalCase(
    val id: String,
    /** "food" | "cardio" | "label" | "density" | "barcode_ingredients" */
    val prompt: String,
    val tags: List<String> = emptyList(),
    val input: CaseInput,
    val expect: CaseExpect,
)

data class CaseSet(
    val version: Int,
    val description: String,
    val passThreshold: Double,
    val cases: List<EvalCase>,
)

object EvalCases {
    private val mapper = ObjectMapper()

    fun load(casesDir: File, setName: String): CaseSet {
        val file = File(casesDir, "$setName.json")
        require(file.isFile) { "Case set not found: ${file.absolutePath}" }
        return loadFile(file, casesDir)
    }

    fun loadFile(file: File, casesDir: File = file.parentFile ?: error("case file has no parent")): CaseSet {
        val root = mapper.readTree(file)
        val included = mutableListOf<EvalCase>()
        root.path("include").takeIf { it.isTextual }?.asText()?.let { name ->
            included += loadFile(File(casesDir, name), casesDir).cases
        }
        included += readCases(root.path("cases"))
        included += readCases(root.path("extraCases"))
        // De-dupe by id, later entries win (extras override).
        val byId = linkedMapOf<String, EvalCase>()
        included.forEach { byId[it.id] = it }
        return CaseSet(
            version = root.path("version").asInt(1),
            description = root.path("description").asText(""),
            passThreshold = root.path("passThreshold").asDouble(0.7),
            cases = byId.values.toList(),
        )
    }

    /** Filter to cases tagged [tag] (e.g. smoke), or all when null. */
    fun filter(set: CaseSet, tag: String?): CaseSet {
        if (tag.isNullOrBlank()) return set
        return set.copy(cases = set.cases.filter { tag in it.tags })
    }

    private fun readCases(node: JsonNode): List<EvalCase> {
        if (!node.isArray) return emptyList()
        return (node as ArrayNode).map { c ->
            val expect = c.path("expect")
            val input = c.path("input")
            EvalCase(
                id = c.path("id").asText(),
                prompt = c.path("prompt").asText(),
                tags = c.path("tags").takeIf { it.isArray }?.map { it.asText() }.orEmpty(),
                input = CaseInput(
                    text = input.path("text").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    image = input.path("image").takeIf { it.isTextual }?.asText(),
                    productName = input.path("productName").takeIf { it.isTextual }?.asText(),
                    brand = input.path("brand").takeIf { it.isTextual }?.asText(),
                    servingGrams = input.path("servingGrams").takeIf { it.isNumber }?.asDouble(),
                    servingSize = input.path("servingSize").takeIf { it.isTextual }?.asText(),
                    ingredientsText = input.path("ingredientsText").takeIf { it.isTextual }?.asText(),
                    kcal = input.path("kcal").takeIf { it.isNumber }?.asInt(),
                    proteinG = input.path("proteinG").takeIf { it.isNumber }?.asInt(),
                    carbsG = input.path("carbsG").takeIf { it.isNumber }?.asInt(),
                    fatG = input.path("fatG").takeIf { it.isNumber }?.asInt(),
                    detailed = input.path("detailed").asBoolean(false),
                    ingredients = input.path("ingredients").asBoolean(false),
                    unitKind = input.path("unitKind").takeIf { it.isTextual }?.asText(),
                ),
                expect = CaseExpect(
                    outcome = expect.path("outcome").asText("estimate"),
                    kcalMid = expect.path("kcalMid").takeIf { it.isNumber }?.asInt(),
                    kcalTolPct = expect.path("kcalTolPct").takeIf { it.isNumber }?.asDouble(),
                    proteinG = intRange(expect.path("proteinG")),
                    carbsG = intRange(expect.path("carbsG")),
                    fatG = intRange(expect.path("fatG")),
                    macrosConsistentWithKcal = expect.path("macrosConsistentWithKcal").asBoolean(false),
                    noteMaxLen = expect.path("noteMaxLen").takeIf { it.isNumber }?.asInt(),
                    distanceM = intRange(expect.path("distanceM")),
                    durationS = intRange(expect.path("durationS")),
                    kcal = intRange(expect.path("kcal")),
                    avgHr = intRange(expect.path("avgHr")),
                    minIngredients = expect.path("minIngredients").takeIf { it.isNumber }?.asInt(),
                    nameContainsAny = expect.path("nameContainsAny").takeIf { it.isArray }?.map { it.asText() }.orEmpty(),
                    requireDetail = expect.path("requireDetail").asBoolean(false),
                    requireIngredients = expect.path("requireIngredients").asBoolean(false),
                    gPerMl = doubleRange(expect.path("gPerMl")),
                    gramsPerEach = doubleRange(expect.path("gramsPerEach")),
                ),
            )
        }
    }

    private fun intRange(node: JsonNode): IntRangeExpect? {
        if (!node.isObject) return null
        return IntRangeExpect(node.path("min").asInt(), node.path("max").asInt())
    }

    private fun doubleRange(node: JsonNode): DoubleRangeExpect? {
        if (!node.isObject) return null
        return DoubleRangeExpect(node.path("min").asDouble(), node.path("max").asDouble())
    }
}
