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
           "modifier":"","portionDescription":"1 cup"}]}
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
        // Second call is cache-only (transport would still work; size stays 1)
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
        // Still cooling → null without calling AI again meaningfully (cache short-circuit)
        assertNull(resolver.resolve("mystery goo", DensityUnitKind.VOLUME))
        now = row.retryAfter!! + 1
        // After retry window, live path runs again (still no key → pending again)
        assertNull(resolver.resolve("mystery goo", DensityUnitKind.VOLUME))
    }

    @Test fun applyToLineSetsGramsAndNeverOverwritesManual() {
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

        val manual = blocked.copy(densityId = IngredientDensity.Source.MANUAL, grams = 100.0)
        assertEquals(manual, resolver.applyToLine(manual, lookup))
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
    }
}
