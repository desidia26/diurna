package app.diurna.ui

import app.diurna.data.CapturedFacts
import app.diurna.data.DecisionLog
import app.diurna.data.OnePhotoCapture.Capture
import app.diurna.data.OnePhotoCapture.Row
import app.diurna.domain.IngredientUnit
import app.diurna.domain.PackageMerge
import app.diurna.domain.PackageMerge.Source
import app.diurna.domain.RecipeMath
import kotlin.math.roundToInt

/**
 * What the person typed on the confirm screen to fill gaps: a serving weight and/or values that
 * no source had, either per serving ([perServing]) or per 100 g. Recorded as [Source.MANUAL].
 */
data class ManualFill(
    val servingGrams: Double? = null,
    val values: Map<String, Double> = emptyMap(),
    val perServing: Boolean = false,
)

sealed interface OnePhotoLine {
    data class Ok(val draft: IngredientDraft) : OnePhotoLine
    data class Problem(val message: String) : OnePhotoLine
}

/** One row of the confirm screen: the chosen value per 100 g, its source, and disagreeing sources. */
data class FactRow(val key: String, val per100g: Double, val source: Source, val others: List<PackageMerge.Other>)

/** Needed before a line can be saved: without them the recipe totals would be wrong, not unknown. */
val ONE_PHOTO_REQUIRED = listOf("kcal", "protein_g", "carbs_g", "fat_g")

private fun valid(v: Double?) = v?.takeIf { it.isFinite() && it >= 0 }

fun mergedWith(c: Row, fill: ManualFill): PackageMerge.Merged {
    val values = fill.values.mapNotNull { (k, v) -> valid(v)?.let { k to it } }.toMap()
    val grams = fill.servingGrams?.takeIf { it.isFinite() && it > 0 && it <= 2_000 }
    if (values.isEmpty() && grams == null) return c.merged
    val manual = PackageMerge.Input(
        source = Source.MANUAL,
        per100g = if (fill.perServing) emptyMap() else values,
        perServing = if (fill.perServing) values else emptyMap(),
        servingGrams = grams,
    )
    return PackageMerge.merge(c.inputs + manual)
}

fun missingRequired(c: Row, fill: ManualFill): List<String> {
    val m = mergedWith(c, fill)
    return ONE_PHOTO_REQUIRED.filter { it !in m.per100g }
}

fun servingsAvailable(c: Row, fill: ManualFill): Boolean = mergedWith(c, fill).servingGrams != null

/** Required fields first, then the rest by key. */
fun factRows(c: Row, fill: ManualFill): List<FactRow> {
    val m = mergedWith(c, fill)
    val keys = ONE_PHOTO_REQUIRED.filter { it in m.per100g } + (m.per100g.keys - ONE_PHOTO_REQUIRED.toSet()).sorted()
    return keys.map { k -> m.per100g.getValue(k).let { FactRow(k, it.value, it.source, it.disagreements) } }
}

/**
 * The recipe line for [amount] grams, or servings when [servings] (only with a known serving
 * weight). Refuses rather than saving a line with made-up zeros.
 */
fun onePhotoLine(capture: Capture, c: Row, fill: ManualFill, name: String, amount: Double?, servings: Boolean): OnePhotoLine {
    if (name.isBlank()) return OnePhotoLine.Problem("Enter a name.")
    if (amount == null || !amount.isFinite() || amount <= 0) return OnePhotoLine.Problem(if (servings) "Enter servings." else "Enter grams.")
    val m = mergedWith(c, fill)
    if (fill.perServing && fill.values.isNotEmpty() && m.servingGrams == null) {
        return OnePhotoLine.Problem("Enter the grams in one serving, or switch the typed values to per 100 g.")
    }
    val missing = ONE_PHOTO_REQUIRED.filter { it !in m.per100g }
    if (missing.isNotEmpty()) return OnePhotoLine.Problem("Enter ${missing.joinToString(", ") { factLabel(it) }}.")
    val grams = if (servings) {
        val g = m.servingGrams?.value ?: return OnePhotoLine.Problem("The serving weight isn't known; enter grams instead.")
        g * amount
    } else amount
    val v = PackageMerge.forGrams(m, grams)
    fun r(k: String) = v[k]?.roundToInt()
    val nutrition = RecipeMath.IngredientNutrition(
        proteinG = r("protein_g")!!, carbsG = r("carbs_g")!!, fatG = r("fat_g")!!,
        fiberG = r("fiber_g"), sugarsG = r("sugars_g"), addedSugarsG = r("added_sugars_g"), starchG = r("starch_g"),
        satFatG = r("sat_fat_g"), monoFatG = r("mono_fat_g"), polyFatG = r("poly_fat_g"), transFatG = r("trans_fat_g"),
        cholesterolMg = r("cholesterol_mg"),
    )
    return OnePhotoLine.Ok(IngredientDraft(
        name = name.trim(),
        amount = grams,
        unit = IngredientUnit.G,
        grams = grams,
        nutrition = nutrition,
        kcal = r("kcal")!!,
        source = ONE_PHOTO_SOURCE,
        barcode = c.code?.code ?: c.picked?.barcode,
        captured = CapturedFacts.from(m, labelPending = capture.labelLater || c.reading == null)
            .copy(decisionLogJson = saveLog(capture, c, m, fill, name.trim(), grams, servings, amount).toJson()),
    ))
}

/** The capture's log plus this line's final decisions: each field's winner and why, edits, the save. */
fun saveLog(capture: Capture, row: Row, m: PackageMerge.Merged, fill: ManualFill, name: String, grams: Double, servings: Boolean, amount: Double): DecisionLog {
    var l = capture.log
    val p = row.product
    if (fill.servingGrams != null) l = l.add(DecisionLog.EDIT, "Person typed the serving weight: ${fmtNum(fill.servingGrams)} g", p)
    fill.values.forEach { (k, v) -> l = l.add(DecisionLog.EDIT, "Person typed ${factLabel(k)}: ${fmtNum(v)} ${factUnit(k)} per ${if (fill.perServing) "serving" else "100 g"}", p) }
    row.picked?.let { l = l.add(DecisionLog.GUESS, "Values from a confirmed name guess: ${it.title} (${it.source.wire})", p) }
    m.servingGrams?.let { l = l.add(DecisionLog.FIELD, "serving weight ${fmtNum(it.value)} g from ${sourceLabel(it.source)} (${DecisionLog.PRECEDENCE})", p) }
    val keys = ONE_PHOTO_REQUIRED.filter { it in m.per100g } + (m.per100g.keys - ONE_PHOTO_REQUIRED.toSet()).sorted()
    keys.forEach { k ->
        val f = m.per100g.getValue(k)
        l = l.add(DecisionLog.FIELD, "${factLabel(k)} ${fmtNum(f.value)} ${factUnit(k)} per 100 g from ${sourceLabel(f.source)} (${DecisionLog.PRECEDENCE})", p)
        if (f.disagreements.isNotEmpty()) l = l.add(DecisionLog.DISAGREEMENT,
            "${factLabel(k)}: " + f.disagreements.joinToString("; ") { "${sourceLabel(it.source)} said ${fmtNum(it.value)}" } + " (more than 10% apart; ${sourceLabel(f.source)} kept)", p)
    }
    m.flags.forEach { l = l.add(DecisionLog.DISAGREEMENT, "Flag: ${it.name.lowercase()}", p) }
    m.ingredients?.let { (_, src) -> l = l.add(DecisionLog.FIELD, "ingredients from ${sourceLabel(src)}", p) }
    return l.add(DecisionLog.SAVE, "Saved “$name”: ${fmtNum(grams)} g" + if (servings) " (${fmtNum(amount)} serving(s))" else "", p)
}

fun fmtNum(v: Double): String = when {
    v >= 100 -> "%.0f".format(v)
    v >= 10 -> "%.1f".format(v).removeSuffix(".0")
    else -> "%.2f".format(v).trimEnd('0').removeSuffix(".")
}

const val ONE_PHOTO_SOURCE = "package"

fun factLabel(key: String): String = when (key) {
    "kcal" -> "calories"
    "protein_g" -> "protein"
    "carbs_g" -> "carbs"
    "fat_g" -> "fat"
    "fiber_g" -> "fiber"
    "sugars_g" -> "sugars"
    "added_sugars_g" -> "added sugars"
    "sat_fat_g" -> "saturated fat"
    "trans_fat_g" -> "trans fat"
    "cholesterol_mg" -> "cholesterol"
    "sodium_mg" -> "sodium"
    else -> key.substringBeforeLast('_').replace('_', ' ')
}

fun factUnit(key: String): String = when {
    key == "kcal" -> "kcal"
    else -> key.substringAfterLast('_')
}

/** Source chips for a row, in precedence order; a confirmed name guess is marked "Guess". */
fun sourceChips(row: Row): List<String> =
    row.inputs.sortedBy { it.source.ordinal }.map { if (it === row.picked?.input) "Guess · ${sourceLabel(it.source)}" else sourceLabel(it.source) }.distinct()

fun sourceLabel(s: Source): String = when (s) {
    Source.MANUAL -> "You"
    Source.LABEL -> "Label"
    Source.FDC_BRANDED -> "USDA"
    Source.OFF -> "OFF"
}
