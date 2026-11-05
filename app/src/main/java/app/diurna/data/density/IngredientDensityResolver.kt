package app.diurna.data.density

import app.diurna.ai.DensityEstimator
import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.data.IngredientDensityCacheEntity
import app.diurna.data.IngredientDensityCacheDao
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcClient
import app.diurna.data.fdc.FdcNutritionProfile
import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensities
import app.diurna.domain.IngredientDensity
import app.diurna.domain.IngredientUnit
import app.diurna.domain.IngredientUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Resolves density (g/ml) or grams-per-each for an ingredient name.
 * Order: positive cache → USDA FDC foodPortions → builtin OPTIONS → AI → negative cache.
 * Transient failures (no key / network) use a short [retryAfter] so they don't poison the cache.
 */
class IngredientDensityResolver(
    private val fdc: FdcClient,
    private val cache: IngredientDensityCacheDao,
    private val ai: DensityEstimator? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Look up density for [name] + [kind]. [forceRefresh] skips a positive cache hit
     * (still used after manual clear). Never overwrites a line the user marked manual.
     */
    suspend fun resolve(
        name: String,
        kind: DensityUnitKind,
        forceRefresh: Boolean = false,
    ): DensityLookup? = withContext(Dispatchers.IO) {
        val normalized = IngredientDensity.normalizeName(name)
        if (normalized.isEmpty()) return@withContext null

        val key = IngredientDensity.cacheKey(normalized, kind)
        if (!forceRefresh) {
            cache.get(key)?.let { row ->
                val retry = row.retryAfter
                if (retry != null && retry > nowMs()) {
                    // Negative / pending still cooling down.
                    if (row.source == IngredientDensity.Source.MISS ||
                        row.source == IngredientDensity.Source.PENDING
                    ) {
                        return@withContext null
                    }
                }
                if (row.toLookup().hasValue) return@withContext row.toLookup()
            }
        }

        // 1) USDA FDC
        val fromFdc = tryFdc(normalized, name, kind)
        if (fromFdc != null) {
            writePositive(normalized, kind, fromFdc)
            return@withContext fromFdc
        }

        // 2) Builtin hand list (volume only)
        if (kind == DensityUnitKind.VOLUME) {
            IngredientDensity.matchBuiltinDensity(name)?.let { opt ->
                val hit = DensityLookup(
                    gPerMl = opt.gPerMl,
                    source = IngredientDensity.Source.BUILTIN,
                    basis = opt.label,
                    description = opt.id,
                )
                writePositive(normalized, kind, hit)
                return@withContext hit
            }
        }

        // 3) AI fallback
        if (ai != null) {
            try {
                val hit = ai.estimate(name, kind)
                writePositive(normalized, kind, hit)
                return@withContext hit
            } catch (_: NoApiKey) {
                writeNegative(normalized, kind, IngredientDensity.Source.PENDING, PENDING_RETRY_MS)
                return@withContext null
            } catch (_: EstimateRetryable) {
                writeNegative(normalized, kind, IngredientDensity.Source.PENDING, PENDING_RETRY_MS)
                return@withContext null
            } catch (_: EstimateFailed) {
                writeNegative(normalized, kind, IngredientDensity.Source.MISS, MISS_RETRY_MS)
                return@withContext null
            }
        }

        writeNegative(normalized, kind, IngredientDensity.Source.MISS, MISS_RETRY_MS)
        null
    }

    /**
     * Resolve density when needed, persist g/ml, and optionally fill empty nutrition from a
     * matching USDA food. Manual density_id / user-entered nutrition are never overwritten.
     */
    suspend fun enrichLine(line: RecipeIngredientEntity): RecipeIngredientEntity {
        var next = backfillGPerMl(line)
        if (next.needsDensityLookup()) {
            val kind = DensityUnitKind.of(IngredientUnit.of(next.unit)) ?: return next
            val lookup = resolve(next.name, kind) ?: return next
            next = applyToLine(next, lookup)
            next = maybeFillNutrition(next, lookup)
        } else if (
            next.densityId == IngredientDensity.Source.USDA &&
            next.fdcId != null &&
            next.nutritionIsEmpty()
        ) {
            val lookup = DensityLookup(
                gPerMl = next.gPerMl,
                gramsPerEach = next.gramsPerEach,
                source = IngredientDensity.Source.USDA,
                fdcId = next.fdcId,
            )
            next = maybeFillNutrition(next, lookup)
        }
        return next
    }

    /** Apply a successful lookup onto a recipe line. Never overwrites manual density_id. */
    fun applyToLine(line: RecipeIngredientEntity, lookup: DensityLookup): RecipeIngredientEntity {
        if (!lookup.hasValue) return line
        if (line.densityId == IngredientDensity.Source.MANUAL) return line
        val unit = IngredientUnit.of(line.unit)
        val kind = DensityUnitKind.of(unit) ?: return line
        // Manual grams already set with a builtin density id the user picked — leave alone
        // unless grams are still missing (blocked).
        if (line.grams != null && line.densityId != null &&
            line.densityId !in AUTO_SOURCES
        ) {
            return line
        }
        val densityId = when (lookup.source) {
            IngredientDensity.Source.BUILTIN -> lookup.description ?: IngredientDensity.Source.BUILTIN
            else -> lookup.source
        }
        return when (kind) {
            DensityUnitKind.VOLUME -> {
                val g = IngredientUnits.toGrams(line.amount, unit, lookup.gPerMl, null)
                line.copy(
                    grams = g,
                    densityId = densityId,
                    gPerMl = lookup.gPerMl,
                    fdcId = lookup.fdcId ?: line.fdcId,
                )
            }
            DensityUnitKind.EACH -> {
                val g = IngredientUnits.toGrams(line.amount, unit, null, lookup.gramsPerEach)
                line.copy(
                    grams = g,
                    gramsPerEach = lookup.gramsPerEach,
                    densityId = densityId,
                    fdcId = lookup.fdcId ?: line.fdcId,
                )
            }
        }
    }

    /**
     * Fill per-line macros/detail from [profile] when nutrition is empty and the FDC name is a
     * good match. Never overwrites user-entered nutrition; marks [RecipeIngredientEntity.source]
     * as `fdc` when it was blank or an import.
     */
    fun applyFdcNutrition(
        line: RecipeIngredientEntity,
        profile: FdcNutritionProfile,
        fdcDescription: String? = profile.description,
    ): RecipeIngredientEntity {
        if (!line.nutritionIsEmpty()) return line
        if (line.source in USER_NUTRITION_SOURCES) return line
        val desc = fdcDescription ?: profile.description
        if (!IngredientDensity.isGoodFdcNameMatch(line.name, desc)) return line
        val g = line.grams ?: return line
        if (g <= 0) return line
        val nut = profile.forGrams(g)
        val k = (profile.kcalPer100 * g / 100.0).roundToInt().coerceAtLeast(0)
        val source = when (line.source) {
            null, "", "import" -> "fdc"
            else -> line.source
        }
        return line.copy(
            proteinG = nut.proteinG,
            carbsG = nut.carbsG,
            fatG = nut.fatG,
            kcal = k,
            fiberG = nut.fiberG,
            solubleFiberG = nut.solubleFiberG,
            insolubleFiberG = nut.insolubleFiberG,
            sugarsG = nut.sugarsG,
            addedSugarsG = nut.addedSugarsG,
            starchG = nut.starchG,
            satFatG = nut.satFatG,
            monoFatG = nut.monoFatG,
            polyFatG = nut.polyFatG,
            transFatG = nut.transFatG,
            cholesterolMg = nut.cholesterolMg,
            omega3G = nut.omega3G,
            omega6G = nut.omega6G,
            omega9G = nut.omega9G,
            hisMg = nut.aminos.hisMg,
            ileMg = nut.aminos.ileMg,
            leuMg = nut.aminos.leuMg,
            lysMg = nut.aminos.lysMg,
            metMg = nut.aminos.metMg,
            pheMg = nut.aminos.pheMg,
            thrMg = nut.aminos.thrMg,
            trpMg = nut.aminos.trpMg,
            valMg = nut.aminos.valMg,
            cysMg = nut.aminos.cysMg,
            tyrMg = nut.aminos.tyrMg,
            source = source,
            fdcId = profile.fdcId.takeIf { it > 0 } ?: line.fdcId,
        )
    }

    private suspend fun maybeFillNutrition(
        line: RecipeIngredientEntity,
        lookup: DensityLookup,
    ): RecipeIngredientEntity {
        if (lookup.source != IngredientDensity.Source.USDA) return line
        val fdcId = lookup.fdcId ?: line.fdcId ?: return line
        if (!line.nutritionIsEmpty()) return line
        if (line.source in USER_NUTRITION_SOURCES) return line
        val profile = fdc.nutritionProfile(fdcId) ?: return line
        val desc = lookup.description ?: profile.description
        return applyFdcNutrition(line, profile, desc)
    }

    /**
     * Persist g/ml for volume lines that already have grams from a prior lookup but were saved
     * before the column existed (or lost session reconstruction).
     */
    private suspend fun backfillGPerMl(line: RecipeIngredientEntity): RecipeIngredientEntity {
        if (line.gPerMl != null) return line
        if (line.densityId == IngredientDensity.Source.MANUAL) return line
        val unit = IngredientUnit.of(line.unit)
        if (DensityUnitKind.of(unit) != DensityUnitKind.VOLUME) return line
        IngredientDensities.byId(line.densityId)?.gPerMl?.let { return line.copy(gPerMl = it) }
        if (line.grams != null && line.amount > 0 &&
            line.densityId in AUTO_SOURCES
        ) {
            reconstructGPerMl(line)?.let { return line.copy(gPerMl = it) }
            val lookup = resolve(line.name, DensityUnitKind.VOLUME)
            if (lookup?.gPerMl != null) return line.copy(gPerMl = lookup.gPerMl)
        }
        return line
    }

    private suspend fun tryFdc(normalized: String, displayName: String, kind: DensityUnitKind): DensityLookup? {
        if (!fdc.hasApiKey()) return null
        val hits = fdc.search(displayName.trim().ifBlank { normalized }, pageSize = 5)
        if (hits.isEmpty()) return null
        val prefer = IngredientDensity.prepModifiers(displayName)
        for (hit in hits) {
            val food = fdc.foodJson(hit.fdcId) ?: continue
            val portions = IngredientDensity.parsePortionsFromJson(food.optJSONArray("foodPortions"))
            if (portions.isEmpty()) continue
            val lookup = when (kind) {
                DensityUnitKind.VOLUME -> {
                    val d = IngredientDensity.densityFromPortions(portions, prefer) ?: continue
                    DensityLookup(
                        gPerMl = d,
                        source = IngredientDensity.Source.USDA,
                        fdcId = hit.fdcId,
                        basis = "FDC ${hit.fdcId}",
                        description = hit.description,
                    )
                }
                DensityUnitKind.EACH -> {
                    val g = IngredientDensity.gramsPerEachFromPortions(portions, prefer) ?: continue
                    DensityLookup(
                        gramsPerEach = g,
                        source = IngredientDensity.Source.USDA,
                        fdcId = hit.fdcId,
                        basis = "FDC ${hit.fdcId}",
                        description = hit.description,
                    )
                }
            }
            return lookup
        }
        return null
    }

    private suspend fun writePositive(name: String, kind: DensityUnitKind, lookup: DensityLookup) {
        cache.upsert(
            IngredientDensityCacheEntity(
                queryKey = IngredientDensity.cacheKey(name, kind),
                normalizedName = name,
                unitKind = kind.key,
                gPerMl = lookup.gPerMl,
                gramsPerEach = lookup.gramsPerEach,
                source = lookup.source,
                fdcId = lookup.fdcId,
                basis = lookup.basis,
                description = lookup.description,
                cachedAt = nowMs(),
                retryAfter = null,
            ),
        )
    }

    private suspend fun writeNegative(name: String, kind: DensityUnitKind, source: String, retryMs: Long) {
        cache.upsert(
            IngredientDensityCacheEntity(
                queryKey = IngredientDensity.cacheKey(name, kind),
                normalizedName = name,
                unitKind = kind.key,
                source = source,
                cachedAt = nowMs(),
                retryAfter = nowMs() + retryMs,
            ),
        )
    }

    companion object {
        const val PENDING_RETRY_MS = 60L * 60_000L // 1 hour
        const val MISS_RETRY_MS = 7L * 24 * 60 * 60_000L // 7 days
        val AUTO_SOURCES = setOf(
            IngredientDensity.Source.USDA,
            IngredientDensity.Source.AI,
            IngredientDensity.Source.BUILTIN,
        )
        /** Origins where nutrition was explicitly chosen by the user (or already filled from FDC). */
        val USER_NUTRITION_SOURCES = setOf("manual", "barcode", "meal")
    }
}

private fun IngredientDensityCacheEntity.toLookup() = DensityLookup(
    gPerMl = gPerMl,
    gramsPerEach = gramsPerEach,
    source = source,
    fdcId = fdcId,
    basis = basis,
    description = description,
)

/** True when a line still needs density or grams-per-each to resolve mass. */
fun RecipeIngredientEntity.needsDensityLookup(): Boolean {
    if (densityId == IngredientDensity.Source.MANUAL) return false
    if (grams != null) return false
    val unit = IngredientUnit.of(unit)
    return DensityUnitKind.of(unit) != null
}

/** Macros and detail all unset — safe to fill from USDA without clobbering the user. */
fun RecipeIngredientEntity.nutritionIsEmpty(): Boolean {
    if (proteinG != 0 || carbsG != 0 || fatG != 0 || kcal != 0) return false
    if (fiberG != null || solubleFiberG != null || insolubleFiberG != null) return false
    if (sugarsG != null || addedSugarsG != null || starchG != null) return false
    if (satFatG != null || monoFatG != null || polyFatG != null || transFatG != null) return false
    if (cholesterolMg != null || omega3G != null || omega6G != null || omega9G != null) return false
    if (hisMg != null || ileMg != null || leuMg != null || lysMg != null || metMg != null) return false
    if (pheMg != null || thrMg != null || trpMg != null || valMg != null || cysMg != null || tyrMg != null) {
        return false
    }
    return true
}

/** Infer g/ml from already-resolved grams and the line's volume unit. */
fun reconstructGPerMl(line: RecipeIngredientEntity): Double? {
    val g = line.grams ?: return null
    if (line.amount <= 0 || g <= 0) return null
    val unit = IngredientUnit.of(line.unit)
    val ml = when (unit) {
        IngredientUnit.ML -> line.amount
        IngredientUnit.CUP -> line.amount * IngredientUnits.ML_PER_CUP
        IngredientUnit.TBSP -> line.amount * IngredientUnits.ML_PER_TBSP
        IngredientUnit.TSP -> line.amount * IngredientUnits.ML_PER_TSP
        else -> return null
    }
    if (ml <= 0) return null
    val d = g / ml
    return if (d.isFinite() && d > 0) d else null
}

/** True when a saved line may still need g/ml backfill or empty USDA nutrition fill. */
fun RecipeIngredientEntity.needsEnrichment(): Boolean {
    if (densityId == IngredientDensity.Source.MANUAL) return false
    if (gPerMl == null && grams != null &&
        DensityUnitKind.of(IngredientUnit.of(unit)) == DensityUnitKind.VOLUME &&
        densityId != null
    ) {
        return true
    }
    return densityId == IngredientDensity.Source.USDA &&
        fdcId != null &&
        nutritionIsEmpty() &&
        source !in IngredientDensityResolver.USER_NUTRITION_SOURCES
}
