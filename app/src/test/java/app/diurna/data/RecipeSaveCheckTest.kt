package app.diurna.data

import app.diurna.ai.DensityEstimator
import app.diurna.ai.NoApiKey
import app.diurna.data.RecipeSaveCheck.Need
import app.diurna.data.density.IngredientDensityResolver
import app.diurna.data.fdc.FdcClient
import app.diurna.data.fdc.FdcTransport
import app.diurna.data.recipe.parseRecipeJsonLd
import app.diurna.data.recipe.toIngredientEntities
import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report r1120: the alfredo recipe imported from a link "wouldn't let me save". */
class RecipeSaveCheckTest {
    // The page's schema.org Recipe, trimmed to what the importer reads.
    private val alfredo = """{"@context":"https://schema.org","@type":"Recipe","name":"Homemade Alfredo Sauce",
        "recipeYield":[5],"recipeIngredient":["2 Tablespoons unsalted butter","2 cloves garlic (minced)",
        "1 cup heavy cream","1 1/2 cups Parmesan cheese (good quality freshly grated (see note below))",
        "1/8 teaspoon salt ((optional))","1/8 teaspoon black pepper ((optional))"],
        "nutrition":{"@type":"NutritionInformation","calories":"1481.06 calories","servingSize":"5"}}"""

    private fun imported(): List<RecipeIngredientEntity> {
        val r = parseRecipeJsonLd(alfredo, "https://snappygourmet.com/homemade-alfredo-sauce-recipe/")!!
        assertEquals(5.0, r.yield, 0.0)
        return r.toIngredientEntities()
    }

    private class MemCache : IngredientDensityCacheDao {
        private val map = linkedMapOf<String, IngredientDensityCacheEntity>()
        override suspend fun get(key: String) = map[key]
        override suspend fun upsert(row: IngredientDensityCacheEntity) { map[row.queryKey] = row }
        override suspend fun delete(key: String) { map.remove(key) }
    }

    private fun resolver(ai: DensityEstimator?) = IngredientDensityResolver(
        FdcClient({ null }, FdcTransport { _, _ -> null }), MemCache(), ai = ai,
    )

    @Test fun everyImportedLineNeedsAWeightBeforeLookup() {
        val lines = imported()
        assertEquals(listOf("tbsp", "each", "cup", "cup", "tsp", "tsp"), lines.map { it.unit })
        assertEquals(listOf(2.0, 2.0, 1.0, 1.5, 0.125, 0.125), lines.map { it.amount })
        val b = RecipeSaveCheck.blockers(lines)
        assertEquals(6, b.size)
        assertEquals(Need.GRAMS_PER_EACH, b[1].need)
        assertTrue(b.filter { it.index != 1 }.all { it.need == Need.DENSITY })
    }

    @Test fun withoutUsdaOrAiOnlyButterAndSaltResolveAndTheRestAreNamed() = runBlocking {
        // No USDA key and no AI (or AI unreachable): the built-in list knows butter and salt only.
        val r = resolver(DensityEstimator { _, _ -> throw NoApiKey("none") })
        val lines = imported().map { r.enrichLine(it) }
        val b = RecipeSaveCheck.blockers(lines)
        assertEquals(listOf("cloves garlic (minced)", "heavy cream", "Parmesan cheese (good quality freshly grated (see note below))", "black pepper ((optional))"),
            b.map { it.name })
        val msg = RecipeSaveCheck.message(b)!!
        assertTrue(msg, msg.contains("\u201cheavy cream\u201d") && msg.contains("Weight") && msg.contains("grams"))
    }

    @Test fun typedWeightsUnblockSaveAndKeepUnitsConvertible() {
        val lines = imported().map { l ->
            when (l.unit) {
                "each" -> RecipeSaveCheck.withWeight(l, 10.0) // 2 cloves
                "cup" -> RecipeSaveCheck.withWeight(l, 240.0 * l.amount * 0.5)
                else -> RecipeSaveCheck.withWeight(l, 15.0 * l.amount * 0.9)
            }
        }
        assertTrue(RecipeSaveCheck.blockers(lines).isEmpty())
        assertNull(RecipeSaveCheck.message(RecipeSaveCheck.blockers(lines)))
        assertFalse(lines.any { RecipeSaveCheck.needsMass(it) })
        val garlic = lines[1]
        assertEquals(5.0, garlic.gramsPerEach!!, 1e-9)
        assertEquals(IngredientDensity.Source.MANUAL, garlic.densityId)
        val cream = lines[2]
        assertEquals(0.5, cream.gPerMl!!, 1e-9)
    }

    @Test fun withAiEveryLineResolves() = runBlocking {
        val r = resolver(DensityEstimator { _, kind ->
            if (kind == DensityUnitKind.EACH) DensityLookup(gramsPerEach = 4.0, source = IngredientDensity.Source.AI)
            else DensityLookup(gPerMl = 0.8, source = IngredientDensity.Source.AI)
        })
        val lines = imported().map { r.enrichLine(it) }
        assertTrue(RecipeSaveCheck.blockers(lines).isEmpty())
        assertEquals(8.0, lines[1].grams!!, 1e-9)
    }

    @Test fun gramsAndUnknownUnitsNeverBlock() {
        val g = RecipeIngredientEntity(mealId = 0, position = 0, name = "Oats", amount = 40.0, unit = "g", grams = 40.0)
        assertNull(RecipeSaveCheck.need(g))
        assertNull(RecipeSaveCheck.need(g.copy(grams = null))) // grams unit: amount is the weight
        assertEquals(Need.DENSITY, RecipeSaveCheck.need(g.copy(unit = "ml", grams = null)))
    }
}
