package app.diurna.eval

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import java.io.File

/** One expected numeric band, inclusive. */
data class IntRangeExpect(val min: Int, val max: Int) {
    fun contains(v: Int): Boolean = v in min..max
}

/**
 * What a case checks. [outcome] is one of:
 * - estimate / reading: a successful structured reply in expected bands
 * - not_food: note mentions not food (or similar) and macros near zero
 * - not_food_or_zeros: not_food, or all-zero macros/kcal
 * - reject: parser or model fails (cardio non-screen, etc.)
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
)

data class CaseInput(val text: String? = null, val image: String? = null)

data class EvalCase(
    val id: String,
    val prompt: String, // "food" | "cardio"
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
            EvalCase(
                id = c.path("id").asText(),
                prompt = c.path("prompt").asText(),
                tags = c.path("tags").takeIf { it.isArray }?.map { it.asText() }.orEmpty(),
                input = CaseInput(
                    text = c.path("input").path("text").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    image = c.path("input").path("image").takeIf { it.isTextual }?.asText(),
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
                ),
            )
        }
    }

    private fun intRange(node: JsonNode): IntRangeExpect? {
        if (!node.isObject) return null
        return IntRangeExpect(node.path("min").asInt(), node.path("max").asInt())
    }
}
