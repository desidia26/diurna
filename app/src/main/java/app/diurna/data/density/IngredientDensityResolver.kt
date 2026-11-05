package app.diurna.data.density

import app.diurna.ai.DensityEstimator
import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.data.IngredientDensityCacheEntity
import app.diurna.data.IngredientDensityCacheDao
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcClient
import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensity
import app.diurna.domain.IngredientUnit
import app.diurna.domain.IngredientUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    if (grams != null) return false
    val unit = IngredientUnit.of(unit)
    return DensityUnitKind.of(unit) != null
}
