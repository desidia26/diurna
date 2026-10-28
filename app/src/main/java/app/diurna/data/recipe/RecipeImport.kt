package app.diurna.data.recipe

import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.domain.IngredientUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Parsed recipe ready to open in the recipe editor. Nutrition is left empty — the user
 * confirms / looks up ingredients; we never send the page (or API keys) to an AI host.
 */
data class ImportedRecipe(
    val name: String,
    val yield: Double,
    val description: String?,
    val sourceUrl: String,
    val ingredients: List<ImportedIngredient>,
) {
    fun toMealDraft(): MealEntity = MealEntity(
        name = name,
        kcal = 0,
        proteinG = 0,
        carbsG = 0,
        fatG = 0,
        kind = MealEntity.KIND_RECIPE,
        servingsYield = yield,
        note = listOfNotNull(
            description?.takeIf { it.isNotBlank() },
            "Imported from $sourceUrl".take(120),
        ).joinToString("\n").ifBlank { null },
    )
}

data class ImportedIngredient(
    /** Original recipe line text (cleaned). */
    val raw: String,
    val name: String,
    val amount: Double,
    val unit: IngredientUnit,
) {
    companion object {
        const val SOURCE_IMPORT = "import"
    }
}

fun ImportedIngredient.toEntity(mealId: Long = 0L, position: Int = 0) = RecipeIngredientEntity(
    mealId = mealId,
    position = position,
    name = name.ifBlank { raw },
    amount = amount,
    unit = unit.key,
    grams = if (unit == IngredientUnit.G) amount else null,
    source = ImportedIngredient.SOURCE_IMPORT,
)

fun ImportedRecipe.toIngredientEntities(): List<RecipeIngredientEntity> =
    ingredients.mapIndexed { i, line -> line.toEntity(position = i) }


sealed interface RecipeImportResult {
    data class Ok(val recipe: ImportedRecipe) : RecipeImportResult
    data class Problem(val message: String) : RecipeImportResult
}

/**
 * Fetches a recipe page over [SafeHttp] and extracts a schema.org Recipe from JSON-LD
 * (preferred) or microdata. Untrusted parsing only — no WebView, no JavaScript.
 */
class RecipeImporter(
    private val fetch: suspend (String) -> String = { SafeHttp.get(it) },
) {
    suspend fun importFromUrl(url: String): RecipeImportResult = withContext(Dispatchers.IO) {
        val body = try {
            fetch(url)
        } catch (e: SafeHttpException) {
            return@withContext RecipeImportResult.Problem(e.message ?: "Couldn't fetch that page")
        } catch (_: Exception) {
            return@withContext RecipeImportResult.Problem("Couldn't fetch that page")
        }
        val parsed = parseRecipeHtml(body, sourceUrl = url.trim())
            ?: return@withContext RecipeImportResult.Problem("No recipe found on that page")
        RecipeImportResult.Ok(parsed)
    }
}

/**
 * Extract a Recipe from HTML that may contain JSON-LD `<script type="application/ld+json">`
 * blocks and/or schema.org microdata. Pure string/JSON parsing — never evaluates scripts.
 */
fun parseRecipeHtml(html: String, sourceUrl: String): ImportedRecipe? {
    if (html.isBlank()) return null
    val slice = if (html.length > SafeHttp.MAX_BYTES) html.substring(0, SafeHttp.MAX_BYTES) else html

    extractJsonLdBlocks(slice).forEach { block ->
        findRecipeInJsonLd(block)?.let { return finalize(it, sourceUrl) }
    }
    parseMicrodataRecipe(slice)?.let { return finalize(it, sourceUrl) }
    return null
}

/** Unit-test / offline entry: parse a JSON-LD document string directly. */
fun parseRecipeJsonLd(json: String, sourceUrl: String = "https://example.com/r"): ImportedRecipe? =
    findRecipeInJsonLd(json)?.let { finalize(it, sourceUrl) }

private data class RawRecipe(
    val name: String?,
    val description: String?,
    val yieldRaw: Any?,
    val ingredients: List<String>,
)

private fun finalize(raw: RawRecipe, sourceUrl: String): ImportedRecipe? {
    val name = cleanText(raw.name ?: "", MAX_NAME).ifBlank { return null }
    val ingredients = raw.ingredients
        .map { cleanText(it, MAX_INGREDIENT) }
        .filter { it.isNotBlank() }
        .take(MAX_INGREDIENT_LINES)
        .map { parseIngredientLine(it) }
    if (ingredients.isEmpty()) return null
    val yield = parseYield(raw.yieldRaw) ?: 1.0
    val description = raw.description?.let { cleanText(it, MAX_DESCRIPTION) }?.ifBlank { null }
    return ImportedRecipe(
        name = name,
        yield = yield,
        description = description,
        sourceUrl = cleanText(sourceUrl, MAX_URL_NOTE),
        ingredients = ingredients,
    )
}

// ---- JSON-LD ----------------------------------------------------------------

private val JSON_LD_SCRIPT = Regex(
    """<script[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

fun extractJsonLdBlocks(html: String): List<String> =
    JSON_LD_SCRIPT.findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

private fun findRecipeInJsonLd(json: String): RawRecipe? {
    val root = try {
        val cleaned = json.trim()
            .removePrefix("\uFEFF")
            .removePrefix("<!--").removeSuffix("-->").trim()
        when {
            cleaned.startsWith("[") -> JSONArray(cleaned)
            cleaned.startsWith("{") -> JSONObject(cleaned)
            else -> return null
        }
    } catch (_: JSONException) {
        return null
    }
    return findRecipeNode(root)
}

private fun findRecipeNode(node: Any?, depth: Int = 0): RawRecipe? {
    if (node == null || depth > 8) return null
    when (node) {
        is JSONObject -> {
            if (isRecipeType(node.opt("@type"))) {
                return rawFromJsonObject(node)
            }
            node.opt("@graph")?.let { findRecipeNode(it, depth + 1) }?.let { return it }
            listOf("mainEntity", "mainEntityOfPage", "about").forEach { key ->
                node.opt(key)?.let { findRecipeNode(it, depth + 1) }?.let { return it }
            }
            val keys = node.keys()
            var seen = 0
            while (keys.hasNext() && seen < 40) {
                val k = keys.next()
                seen++
                if (k == "@context") continue
                findRecipeNode(node.opt(k), depth + 1)?.let { return it }
            }
        }
        is JSONArray -> {
            for (i in 0 until minOf(node.length(), 40)) {
                findRecipeNode(node.opt(i), depth + 1)?.let { return it }
            }
        }
    }
    return null
}

private fun isRecipeType(type: Any?): Boolean {
    fun one(s: String): Boolean {
        val t = s.trim().lowercase(Locale.US)
        return t == "recipe" || t.endsWith("/recipe") || t.endsWith("#recipe")
    }
    return when (type) {
        is String -> one(type)
        is JSONArray -> (0 until type.length()).any { one(type.optString(it)) }
        else -> false
    }
}

private fun rawFromJsonObject(o: JSONObject): RawRecipe {
    val name = o.optString("name").ifBlank { null }
    val description = o.optString("description").ifBlank { null }
    val yieldRaw = o.opt("recipeYield") ?: o.opt("yield")
    val ingredients = mutableListOf<String>()
    when (val ri = o.opt("recipeIngredient") ?: o.opt("ingredients")) {
        is JSONArray -> for (i in 0 until minOf(ri.length(), MAX_INGREDIENT_LINES * 2)) {
            when (val item = ri.opt(i)) {
                is String -> ingredients.add(item)
                is JSONObject -> {
                    val n = item.optString("name").ifBlank { item.optString("text") }
                    if (n.isNotBlank()) ingredients.add(n)
                }
            }
        }
        is String -> ingredients.addAll(ri.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
    }
    return RawRecipe(name, description, yieldRaw, ingredients)
}

// ---- Microdata (best-effort, no HTML parser dep) ----------------------------

/**
 * Lightweight microdata scan for `itemtype` … Recipe and `itemprop` name / recipeIngredient /
 * recipeYield. Not a full HTML parser — good enough for common static markup; JSON-LD is preferred.
 */
private fun parseMicrodataRecipe(html: String): RawRecipe? {
    val typeMatch = Regex(
        """itemtype\s*=\s*["']https?://schema\.org/Recipe["']""",
        RegexOption.IGNORE_CASE,
    ).find(html) ?: return null
    val window = html.substring(typeMatch.range.first, minOf(html.length, typeMatch.range.first + 80_000))

    fun props(prop: String): List<String> {
        val out = mutableListOf<String>()
        val re = Regex(
            """itemprop\s*=\s*["']$prop["'][^>]*?(?:content\s*=\s*["']([^"']*)["'][^>]*)?>([^<]*)""",
            RegexOption.IGNORE_CASE,
        )
        re.findAll(window).forEach { m ->
            val v = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        val meta = Regex(
            """<meta[^>]*itemprop\s*=\s*["']$prop["'][^>]*content\s*=\s*["']([^"']*)["'][^>]*/?>""",
            RegexOption.IGNORE_CASE,
        )
        meta.findAll(window).forEach { m ->
            val v = m.groupValues[1].trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    val name = props("name").firstOrNull()
    val description = props("description").firstOrNull()
    val yieldRaw = props("recipeYield").firstOrNull()
    val ingredients = props("recipeIngredient")
    if (name.isNullOrBlank() || ingredients.isEmpty()) return null
    return RawRecipe(name, description, yieldRaw, ingredients)
}

// ---- Ingredient / yield heuristics ------------------------------------------

private val AMOUNT_RE = Regex(
    """^\s*(\d+\s+\d+/\d+|\d+/\d+|\d+[.,]\d+|\d+)\s*""",
)
private val UNIT_WORDS = linkedMapOf(
    "tablespoons" to IngredientUnit.TBSP,
    "tablespoon" to IngredientUnit.TBSP,
    "teaspoons" to IngredientUnit.TSP,
    "teaspoon" to IngredientUnit.TSP,
    "tbsp." to IngredientUnit.TBSP,
    "tbsp" to IngredientUnit.TBSP,
    "tbs" to IngredientUnit.TBSP,
    "tsp." to IngredientUnit.TSP,
    "tsp" to IngredientUnit.TSP,
    "cups" to IngredientUnit.CUP,
    "cup" to IngredientUnit.CUP,
    "grams" to IngredientUnit.G,
    "gram" to IngredientUnit.G,
    "g" to IngredientUnit.G,
    "milliliters" to IngredientUnit.ML,
    "millilitres" to IngredientUnit.ML,
    "milliliter" to IngredientUnit.ML,
    "millilitre" to IngredientUnit.ML,
    "ml" to IngredientUnit.ML,
)

/**
 * Parse a free-text ingredient line into amount/unit/name. Falls back to amount=1, unit=each
 * with the whole line as the name when nothing matches.
 */
fun parseIngredientLine(raw: String): ImportedIngredient {
    val line = raw.trim()
    val amountMatch = AMOUNT_RE.find(line)
    val amount: Double
    var rest: String
    if (amountMatch != null) {
        amount = parseAmountToken(amountMatch.groupValues[1]) ?: 1.0
        rest = line.substring(amountMatch.range.last + 1).trim()
    } else {
        amount = 1.0
        rest = line
    }
    var unit = IngredientUnit.EACH
    val lower = rest.lowercase(Locale.US)
    for ((word, u) in UNIT_WORDS) {
        if (lower == word || lower.startsWith("$word ") || lower.startsWith("$word.")) {
            unit = u
            rest = rest.substring(word.length).trim().trimStart('.', ',', ' ')
            break
        }
    }
    if (rest.lowercase(Locale.US).startsWith("of ")) rest = rest.substring(3).trim()
    val name = cleanText(rest.ifBlank { line }, MAX_INGREDIENT_NAME).ifBlank { line.take(MAX_INGREDIENT_NAME) }
    val safeAmount = amount.takeIf { it.isFinite() && it > 0 && it <= MAX_AMOUNT } ?: 1.0
    return ImportedIngredient(raw = cleanText(line, MAX_INGREDIENT), name = name, amount = safeAmount, unit = unit)
}

private fun parseAmountToken(tok: String): Double? {
    val t = tok.trim().replace(',', '.')
    val mixed = Regex("""^(\d+)\s+(\d+)/(\d+)$""").matchEntire(t)
    if (mixed != null) {
        val whole = mixed.groupValues[1].toDoubleOrNull() ?: return null
        val num = mixed.groupValues[2].toDoubleOrNull() ?: return null
        val den = mixed.groupValues[3].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return whole + num / den
    }
    val frac = Regex("""^(\d+)/(\d+)$""").matchEntire(t)
    if (frac != null) {
        val num = frac.groupValues[1].toDoubleOrNull() ?: return null
        val den = frac.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return num / den
    }
    return t.toDoubleOrNull()
}

fun parseYield(raw: Any?): Double? {
    when (raw) {
        null -> return null
        is Number -> {
            val d = raw.toDouble()
            return d.takeIf { it.isFinite() && it > 0 && it <= MAX_YIELD }
        }
        is String -> {
            val s = raw.trim()
            s.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= MAX_YIELD }?.let { return it }
            val m = Regex("""(\d+[.,]?\d*)""").find(s) ?: return null
            val d = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return d.takeIf { it.isFinite() && it > 0 && it <= MAX_YIELD }
        }
        is JSONArray -> {
            for (i in 0 until raw.length()) {
                parseYield(raw.opt(i))?.let { return it }
            }
            return null
        }
        else -> return null
    }
}

// ---- Text hygiene ------------------------------------------------------------

private const val MAX_NAME = 120
private const val MAX_DESCRIPTION = 280
private const val MAX_INGREDIENT = 200
private const val MAX_INGREDIENT_NAME = 120
private const val MAX_INGREDIENT_LINES = 80
private const val MAX_URL_NOTE = 200
private const val MAX_AMOUNT = 10_000.0
private const val MAX_YIELD = 500.0

/**
 * Single-line printable text, length-capped. Control / format characters stripped —
 * same posture as Open Food Facts parsing: page content is untrusted.
 */
fun cleanText(s: String, max: Int): String =
    s.map { if (it.isISOControl()) ' ' else it }
        .filter { Character.getType(it) != Character.FORMAT.toInt() }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(max)
        .trim()
