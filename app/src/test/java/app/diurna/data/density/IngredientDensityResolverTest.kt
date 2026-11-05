package app.diurna.data.density

import app.diurna.ai.DensityEstimator
import app.diurna.ai.EstimateFailed
import app.diurna.ai.NoApiKey
import app.diurna.data.IngredientDensityCacheEntity
import app.diurna.data.IngredientDensityCacheDao
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcClient
import app.diurna.data.fdc.FdcTransport
import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensity
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
}
