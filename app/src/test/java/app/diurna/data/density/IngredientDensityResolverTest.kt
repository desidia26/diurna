package app.diurna.data.density

import app.diurna.ai.DensityEstimator
import app.diurna.ai.NutritionEstimator
import app.diurna.ai.EstimateFailed
import app.diurna.ai.NoApiKey
import app.diurna.data.IngredientDensityCacheEntity
import app.diurna.data.IngredientDensityCacheDao
import app.diurna.data.IngredientNutritionCacheEntity
import app.diurna.data.IngredientNutritionCacheDao
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcClient
import app.diurna.data.fdc.FdcNutritionProfile
import app.diurna.data.fdc.FdcTransport
import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensity
import app.diurna.domain.RecipeMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemCache : IngredientDensityCacheDao {
    private val map = linkedMapOf<String, IngredientDensityCacheEntity>()
    override suspend fun get(key: String) = map[key]
    override suspend fun upsert(row: IngredientDensityCacheEntity) { map[row.queryKey] = row }
    override suspend fun delete(key: String) { map.remove(key) }
    fun size() = map.size
}

private class MemNutritionCache : IngredientNutritionCacheDao {
    private val map = linkedMapOf<String, IngredientNutritionCacheEntity>()
    override suspend fun get(name: String) = map[name]
    override suspend fun upsert(row: IngredientNutritionCacheEntity) { map[row.normalizedName] = row }
    override suspend fun delete(name: String) { map.remove(name) }
    fun size() = map.size
}

class IngredientDensityResolverTest {
    private val flourFood = """
        {"fdcId":1,"description":"Flour, wheat, all-purpose",
         "foodPortions":[{"gramWeight":125,"value":1,"measureUnit":{"name":"cup","abbreviation":"cup"},
           "modifier":"","portionDescription":"1 cup"}],
         "foodNutrients":[
           {"nutrient":{"id":1008},"amount":364},
           {"nutrient":{"id":1003},"amount":10.3},
           {"nutrient":{"id":1005},"amount":76.3},
           {"nutrient":{"id":1004},"amount":1.0},
           {"nutrient":{"id":1079},"amount":2.7}
         ]}
    """.trimIndent()

    @Test fun fdcHitCachedAcrossRecipes() = runBlocking {
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("/foods/search") ->
                    """{"foods":[{"fdcId":1,"description":"Flour, wheat, all-purpose"}]}"""
                path.contains("/food/1") -> flourFood
                else -> null
            }
        }
        val cache = MemCache()
        val resolver = IngredientDensityResolver(FdcClient({ "k" }, transport), cache, ai = null)
        val a = resolver.resolve("all-purpose flour", DensityUnitKind.VOLUME)!!
        assertEquals(IngredientDensity.Source.USDA, a.source)
        assertEquals(1, cache.size())
        val b = resolver.resolve("All-Purpose Flour", DensityUnitKind.VOLUME)!!
        assertEquals(a.gPerMl!!, b.gPerMl!!, 0.0)
        assertEquals(1, cache.size())
    }

    @Test fun builtinFallbackWhenNoFdc() = runBlocking {
        val cache = MemCache()
        val resolver = IngredientDensityResolver(
            FdcClient({ null }, FdcTransport { _, _ -> error("no") }),
            cache,
            ai = null,
        )
        val hit = resolver.resolve("olive oil", DensityUnitKind.VOLUME)!!
        assertEquals(IngredientDensity.Source.BUILTIN, hit.source)
        assertEquals(0.92, hit.gPerMl!!, 0.0)
    }

    @Test fun aiFallbackWhenFdcMisses() = runBlocking {
        val cache = MemCache()
        val ai = DensityEstimator { _, kind ->
            DensityLookup(gPerMl = 0.4, source = IngredientDensity.Source.AI, basis = "guess")
                .takeIf { kind == DensityUnitKind.VOLUME }
                ?: throw EstimateFailed("no")
        }
        val transport = FdcTransport { path, _ ->
            if (path.contains("/foods/search")) """{"foods":[]}""" else null
        }
        val resolver = IngredientDensityResolver(FdcClient({ "k" }, transport), cache, ai)
        val hit = resolver.resolve("chia seeds", DensityUnitKind.VOLUME)!!
        assertEquals(IngredientDensity.Source.AI, hit.source)
        assertEquals(0.4, hit.gPerMl!!, 0.0)
    }

    @Test fun missingAiKeyDoesNotPoisonPermanently() = runBlocking {
        var now = 1_000_000L
        val cache = MemCache()
        val ai = DensityEstimator { _, _ -> throw NoApiKey("no key") }
        val resolver = IngredientDensityResolver(
            FdcClient({ null }, FdcTransport { _, _ -> null }),
            cache,
            ai,
            nowMs = { now },
        )
        assertNull(resolver.resolve("mystery goo", DensityUnitKind.VOLUME))
        val row = cache.get(IngredientDensity.cacheKey("mystery goo", DensityUnitKind.VOLUME))!!
        assertEquals(IngredientDensity.Source.PENDING, row.source)
        assertNotNull(row.retryAfter)
        assertNull(resolver.resolve("mystery goo", DensityUnitKind.VOLUME))
        now = row.retryAfter!! + 1
        assertNull(resolver.resolve("mystery goo", DensityUnitKind.VOLUME))
    }

    @Test fun applyToLineSetsGramsGPerMlAndNeverOverwritesManual() {
        val resolver = IngredientDensityResolver(
            FdcClient({ null }, FdcTransport { _, _ -> null }),
            MemCache(),
        )
        val lookup = DensityLookup(gPerMl = 1.0, source = IngredientDensity.Source.USDA, fdcId = 9)
        val blocked = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "water", amount = 2.0, unit = "cup", grams = null,
        )
        val applied = resolver.applyToLine(blocked, lookup)
        assertEquals(480.0, applied.grams!!, 0.0)
        assertEquals("usda", applied.densityId)
        assertEquals(1.0, applied.gPerMl!!, 0.0)

        val manual = blocked.copy(densityId = IngredientDensity.Source.MANUAL, grams = 100.0, gPerMl = 0.5)
        assertEquals(manual, resolver.applyToLine(manual, lookup))
    }

    @Test fun lookupsNeverOverwriteHandTypedGramsOrGramsPerEach() = runBlocking {
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("/foods/search") ->
                    """{"foods":[{"fdcId":1,"description":"Flour, wheat, all-purpose"}]}"""
                path.contains("/food/1") -> flourFood
                else -> null
            }
        }
        val resolver = IngredientDensityResolver(FdcClient({ "k" }, transport), MemCache())
        val handGrams = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "flour", amount = 100.0, unit = "g",
            grams = 100.0, densityId = IngredientDensity.Source.MANUAL, source = "manual",
            proteinG = 8, carbsG = 70, fatG = 1, kcal = 340,
        )
        assertFalse(handGrams.needsDensityLookup())
        assertEquals(handGrams, resolver.enrichLine(handGrams))

        val handEach = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "egg", amount = 2.0, unit = "each",
            grams = 100.0, gramsPerEach = 50.0, densityId = IngredientDensity.Source.MANUAL,
            source = "manual",
        )
        val lookup = DensityLookup(gramsPerEach = 60.0, source = IngredientDensity.Source.USDA, fdcId = 2)
        assertEquals(handEach, resolver.applyToLine(handEach, lookup))
        assertFalse(handEach.needsDensityLookup())
    }

    @Test fun enrichLineFillsEmptyNutritionFromMatchingFdcFood() = runBlocking {
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("/foods/search") ->
                    """{"foods":[{"fdcId":1,"description":"Flour, wheat, all-purpose"}]}"""
                path.contains("/food/1") -> flourFood
                else -> null
            }
        }
        val resolver = IngredientDensityResolver(FdcClient({ "k" }, transport), MemCache())
        val blocked = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "all-purpose flour", amount = 1.0, unit = "cup",
            grams = null, source = "import",
        )
        val filled = resolver.enrichLine(blocked)
        assertEquals("usda", filled.densityId)
        assertNotNull(filled.grams)
        assertNotNull(filled.gPerMl)
        assertTrue(filled.proteinG > 0)
        assertTrue(filled.carbsG > 0)
        assertEquals("fdc", filled.source)
        assertEquals(1, filled.fdcId)
    }

    @Test fun enrichLineNeverOverwritesUserNutrition() = runBlocking {
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("/foods/search") ->
                    """{"foods":[{"fdcId":1,"description":"Flour, wheat, all-purpose"}]}"""
                path.contains("/food/1") -> flourFood
                else -> null
            }
        }
        val resolver = IngredientDensityResolver(FdcClient({ "k" }, transport), MemCache())
        val user = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "flour", amount = 1.0, unit = "cup",
            grams = null, source = "manual", proteinG = 12, carbsG = 80, fatG = 2, kcal = 400,
        )
        // Still needs density (grams null) but nutrition is user-owned
        val filled = resolver.enrichLine(user)
        assertNotNull(filled.grams)
        assertEquals(12, filled.proteinG)
        assertEquals(80, filled.carbsG)
        assertEquals(2, filled.fatG)
        assertEquals(400, filled.kcal)
        assertEquals("manual", filled.source)
    }

    @Test fun backfillPersistsGPerMlFromResolvedGrams() = runBlocking {
        val resolver = IngredientDensityResolver(
            FdcClient({ null }, FdcTransport { _, _ -> null }),
            MemCache(),
        )
        val legacy = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "water", amount = 1.0, unit = "cup",
            grams = 240.0, densityId = IngredientDensity.Source.USDA, gPerMl = null, fdcId = 9,
        )
        val next = resolver.enrichLine(legacy)
        assertEquals(1.0, next.gPerMl!!, 1e-9)
    }

    @Test fun needsDensityLookup() {
        assertTrue(
            RecipeIngredientEntity(mealId = 1, position = 0, name = "x", amount = 1.0, unit = "cup")
                .needsDensityLookup(),
        )
        assertTrue(
            !RecipeIngredientEntity(mealId = 1, position = 0, name = "x", amount = 1.0, unit = "g", grams = 1.0)
                .needsDensityLookup(),
        )
        assertTrue(
            !RecipeIngredientEntity(
                mealId = 1, position = 0, name = "x", amount = 1.0, unit = "cup",
                densityId = IngredientDensity.Source.MANUAL,
            ).needsDensityLookup(),
        )
    }

    @Test fun goodFdcNameMatch() {
        assertTrue(IngredientDensity.isGoodFdcNameMatch("all-purpose flour", "Flour, wheat, all-purpose"))
        assertFalse(IngredientDensity.isGoodFdcNameMatch("olive oil", "Butter, salted"))
    }

    private val soyFood = """
        {"fdcId":2,"description":"Soymilk, original and vanilla, unfortified",
         "foodPortions":[{"gramWeight":243,"value":1,"measureUnit":{"name":"cup","abbreviation":"cup"},
           "modifier":"","portionDescription":"1 cup"}],
         "foodNutrients":[
           {"nutrient":{"id":1008},"amount":43},
           {"nutrient":{"id":1003},"amount":2.6},
           {"nutrient":{"id":1005},"amount":4.9},
           {"nutrient":{"id":1004},"amount":1.5}
         ]}
    """.trimIndent()

    private val waterFood = """
        {"fdcId":3,"description":"Water, municipal",
         "foodPortions":[{"gramWeight":240,"value":1,"measureUnit":{"name":"cup","abbreviation":"cup"},
           "modifier":"","portionDescription":"1 cup"}],
         "foodNutrients":[
           {"nutrient":{"id":1008},"amount":0},
           {"nutrient":{"id":1003},"amount":0},
           {"nutrient":{"id":1005},"amount":0},
           {"nutrient":{"id":1004},"amount":0}
         ]}
    """.trimIndent()

    private fun focacciaLines() = listOf(
        RecipeIngredientEntity(
            mealId = 1, position = 0, name = "ground flaxseeds", amount = 0.25, unit = "cup",
            grams = 33.0, densityId = IngredientDensity.Source.AI, source = "import",
        ),
        RecipeIngredientEntity(
            mealId = 1, position = 1, name = "water", amount = 0.5, unit = "cup",
            grams = 120.0, densityId = "water", gPerMl = 1.0, source = "import",
        ),
        RecipeIngredientEntity(
            mealId = 1, position = 2, name = "soy milk", amount = 3.5, unit = "cup",
            grams = 865.0, densityId = "milk", gPerMl = 1.03, source = "import",
        ),
        RecipeIngredientEntity(
            mealId = 1, position = 3, name = "apple cider vinegar", amount = 2.0, unit = "tbsp",
            grams = 30.0, densityId = IngredientDensity.Source.AI, source = "import",
        ),
        RecipeIngredientEntity(
            mealId = 1, position = 4,
            name = "all purpose flour* (or a mix of whole wheat and white flour)",
            amount = 3.5, unit = "cup",
            grams = 445.0, densityId = "flour", gPerMl = 0.53, source = "import",
        ),
        RecipeIngredientEntity(
            mealId = 1, position = 5, name = "baking powder", amount = 1.0, unit = "tbsp",
            grams = 15.0, densityId = IngredientDensity.Source.AI, source = "import",
        ),
    )

    private fun aiNutrition(): NutritionEstimator = NutritionEstimator { name ->
        val n = name.lowercase()
        when {
            "flour" in n -> FdcNutritionProfile(
                0, "AP flour", RecipeMath.IngredientNutrition(10, 76, 1, fiberG = 3), 364,
            )
            "soy" in n -> FdcNutritionProfile(
                0, "soy milk", RecipeMath.IngredientNutrition(3, 5, 2), 43,
            )
            "flax" in n -> FdcNutritionProfile(
                0, "flax", RecipeMath.IngredientNutrition(18, 29, 42, fiberG = 27), 534,
            )
            "vinegar" in n -> FdcNutritionProfile(
                0, "vinegar", RecipeMath.IngredientNutrition(0, 1, 0), 21,
            )
            "baking" in n || "powder" in n -> FdcNutritionProfile(
                0, "baking powder", RecipeMath.IngredientNutrition(0, 28, 0), 53,
            )
            "water" in n -> FdcNutritionProfile(
                0, "water", RecipeMath.IngredientNutrition(0, 0, 0), 0,
            )
            else -> FdcNutritionProfile(
                0, name, RecipeMath.IngredientNutrition(1, 1, 1), 20,
            )
        }
    }

    @Test fun importedFocacciaGetsMacrosWithoutUsdaKey() = runBlocking {
        val nCache = MemNutritionCache()
        val resolver = IngredientDensityResolver(
            FdcClient({ null }, FdcTransport { _, _ -> error("no usda") }),
            MemCache(),
            ai = null,
            nutritionAi = aiNutrition(),
            nutritionCache = nCache,
        )
        val enriched = focacciaLines().map { resolver.enrichLine(it) }
        val byName = enriched.associateBy { it.name }
        val flour = byName.getValue("all purpose flour* (or a mix of whole wheat and white flour)")
        val soy = byName.getValue("soy milk")
        val water = byName.getValue("water")
        assertTrue("flour protein", flour.proteinG > 0)
        assertTrue("flour carbs", flour.carbsG > 0)
        assertTrue("flour kcal", flour.kcal > 0)
        assertEquals("ai", flour.source)
        assertTrue("soy protein", soy.proteinG > 0)
        assertTrue("soy kcal", soy.kcal > 0)
        assertEquals("ai", soy.source)
        // Water genuinely zero, but marked filled so we do not re-estimate forever.
        assertEquals(0, water.proteinG)
        assertEquals(0, water.carbsG)
        assertEquals(0, water.fatG)
        assertEquals(0, water.kcal)
        assertEquals("ai", water.source)
        assertFalse(water.nutritionNeedsFill())
        assertTrue(nCache.size() >= 3)
    }

    @Test fun importedFocacciaGetsMacrosWithUsdaKey() = runBlocking {
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("/foods/search") && "flour" in path ->
                    """{"foods":[{"fdcId":1,"description":"Flour, wheat, all-purpose"}]}"""
                path.contains("/foods/search") && ("soy" in path || "soymilk" in path) ->
                    """{"foods":[{"fdcId":2,"description":"Soymilk, original and vanilla, unfortified"}]}"""
                path.contains("/foods/search") && "water" in path ->
                    """{"foods":[{"fdcId":3,"description":"Water, municipal"}]}"""
                path.contains("/foods/search") -> """{"foods":[]}"""
                path.contains("/food/1") -> flourFood
                path.contains("/food/2") -> soyFood
                path.contains("/food/3") -> waterFood
                else -> null
            }
        }
        val resolver = IngredientDensityResolver(
            FdcClient({ "k" }, transport),
            MemCache(),
            ai = null,
            nutritionAi = aiNutrition(),
            nutritionCache = MemNutritionCache(),
        )
        val enriched = focacciaLines().map { resolver.enrichLine(it) }
        val flour = enriched.first { "flour" in it.name }
        val soy = enriched.first { it.name == "soy milk" }
        val flax = enriched.first { "flax" in it.name }
        assertTrue(flour.proteinG > 0)
        assertTrue(flour.carbsG > 0)
        assertEquals("fdc", flour.source)
        assertTrue(soy.proteinG > 0)
        assertEquals("fdc", soy.source)
        // Flax has no FDC hit in this fixture → AI fallback
        assertTrue(flax.proteinG > 0)
        assertEquals("ai", flax.source)
    }

    @Test fun enrichBackfillsNutritionOnBuiltinDensityLines() = runBlocking {
        // Reproduces the bug: grams already set via builtin, so needsDensityLookup is false,
        // and old code only filled nutrition when densityId was USDA.
        val resolver = IngredientDensityResolver(
            FdcClient({ null }, FdcTransport { _, _ -> null }),
            MemCache(),
            nutritionAi = aiNutrition(),
            nutritionCache = MemNutritionCache(),
        )
        val flour = RecipeIngredientEntity(
            mealId = 1, position = 0,
            name = "all purpose flour* (or a mix of whole wheat and white flour)",
            amount = 3.5, unit = "cup", grams = 445.0, densityId = "flour", gPerMl = 0.53,
            source = "import", proteinG = 0, carbsG = 0, fatG = 0, kcal = 0,
        )
        assertFalse(flour.needsDensityLookup())
        assertTrue(flour.needsEnrichment())
        val filled = resolver.enrichLine(flour)
        assertTrue(filled.proteinG > 0)
        assertTrue(filled.carbsG > 0)
        assertEquals("ai", filled.source)
    }

    @Test fun genuineZeroWaterDoesNotNeedRefill() = runBlocking {
        val water = RecipeIngredientEntity(
            mealId = 1, position = 0, name = "water", amount = 0.5, unit = "cup",
            grams = 120.0, densityId = "water", gPerMl = 1.0, source = "fdc",
            proteinG = 0, carbsG = 0, fatG = 0, kcal = 0,
        )
        assertFalse(water.nutritionIsEmpty())
        assertFalse(water.nutritionNeedsFill())
        assertFalse(water.needsEnrichment())
    }

    @Test fun soyMilkMatchesFdcSoymilkCompound() {
        assertTrue(
            IngredientDensity.isGoodFdcNameMatch(
                "soy milk",
                "Soymilk, original and vanilla, unfortified",
            ),
        )
        assertTrue(
            IngredientDensity.isGoodFdcNameMatch(
                "all purpose flour* (or a mix of whole wheat and white flour)",
                "Flour, wheat, all-purpose",
            ),
        )
    }
}
