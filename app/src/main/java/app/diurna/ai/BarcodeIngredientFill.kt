package app.diurna.ai

import app.diurna.data.OffIngredients
import app.diurna.data.OffSnapshot
import app.diurna.data.fdc.AminoResolver
import app.diurna.data.fdc.IngredientsJson
import app.diurna.domain.AminoAcids
import app.diurna.domain.FoodIngredient
import kotlinx.coroutines.CancellationException

/**
 * Resolves barcode-product ingredients → FDC aminos: prefer structured OFF percents,
 * else AI guess via [FoodEstimator.guessBarcodeIngredients]. Never invents amino mg.
 */
object BarcodeIngredientFill {
    data class Result(
        val aminos: AminoAcids?,
        val ingredientsJson: String?,
        val aminoMatched: Int?,
        val aminoTotal: Int?,
        val source: String?,
    ) {
        val wrote: Boolean get() = ingredientsJson != null || aminos != null
    }

    /**
     * [servingGrams] is the grams for this log line (one serving × servings when backfilling).
     * [macros] are already-stored label macros (hints for AI only).
     */
    suspend fun resolve(
        snap: OffSnapshot,
        servingGrams: Double,
        macros: MacrosHint,
        estimator: FoodEstimator,
        aminoResolver: AminoResolver,
    ): Result {
        if (servingGrams <= 0 || !servingGrams.isFinite()) return Result(null, null, null, null, null)
        val converted = OffIngredients.fromStructured(snap.ingredientsJson, servingGrams)
        val (ingredients, source) = if (converted.usable) {
            converted.ingredients to OffIngredients.SOURCE_OFF
        } else {
            val guessed = guessOrEmpty(snap, servingGrams, macros, estimator)
            if (guessed.isEmpty()) return Result(null, null, null, null, null)
            guessed to OffIngredients.SOURCE_AI
        }
        val resolved = aminoResolver.resolve(ingredients)
        return Result(
            aminos = resolved.aminos,
            ingredientsJson = IngredientsJson.encode(resolved.ingredients, source),
            aminoMatched = resolved.matchedCount,
            aminoTotal = resolved.totalCount,
            source = source,
        )
    }

    private suspend fun guessOrEmpty(
        snap: OffSnapshot,
        servingGrams: Double,
        macros: MacrosHint,
        estimator: FoodEstimator,
    ): List<FoodIngredient> {
        val text = snap.ingredientsTextEn?.takeIf { it.isNotBlank() }
            ?: snap.ingredientsText
        val ctx = BarcodeIngredientsContext(
            productName = snap.productName,
            servingGrams = servingGrams,
            servingSize = snap.servingSize,
            ingredientsText = text,
            kcal = macros.kcal,
            proteinG = macros.proteinG,
            carbsG = macros.carbsG,
            fatG = macros.fatG,
        )
        return try {
            estimator.guessBarcodeIngredients(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoApiKey) {
            emptyList()
        } catch (_: EstimateFailed) {
            emptyList()
        } catch (_: EstimateRetryable) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class MacrosHint(
        val kcal: Int? = null,
        val proteinG: Int? = null,
        val carbsG: Int? = null,
        val fatG: Int? = null,
    )
}
