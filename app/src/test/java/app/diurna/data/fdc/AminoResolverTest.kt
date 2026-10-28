package app.diurna.data.fdc

import app.diurna.data.FdcFoodCacheDao
import app.diurna.data.FdcFoodCacheEntity
import app.diurna.domain.FoodIngredient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AminoResolverTest {
    private class MemCache : FdcFoodCacheDao {
        val rows = mutableMapOf<String, FdcFoodCacheEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun upsert(row: FdcFoodCacheEntity) { rows[row.queryKey] = row }
    }

    @Test fun sumsScaledAminosAndCaches() = runBlocking {
        val cache = MemCache()
        var searches = 0
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("search") -> {
                    searches++
                    """{"foods":[{"fdcId":10,"description":"Lentils, cooked"}]}"""
                }
                path.endsWith("/10") ->
                    """{"fdcId":10,"description":"Lentils, cooked","foodNutrients":[
                      {"nutrient":{"id":1214},"amount":0.624},
                      {"nutrient":{"id":1210},"amount":0.08}
                    ]}"""
                else -> null
            }
        }
        val resolver = AminoResolver(FdcClient({ "k" }, transport), cache)
        val r1 = resolver.resolve(listOf(FoodIngredient("lentils, cooked", 100.0)))
        assertEquals(624, r1.aminos!!.lysMg)
        assertEquals(80, r1.aminos!!.trpMg)
        assertEquals(10, r1.ingredients[0].fdcId)
        assertEquals(1, searches)

        val r2 = resolver.resolve(listOf(FoodIngredient("Lentils, Cooked", 50.0)))
        assertEquals(312, r2.aminos!!.lysMg)
        assertEquals(1, searches) // cache hit
        assertEquals(1, cache.rows.size)
    }

    @Test fun nullAminosWhenNoKeyOrLookupFails() = runBlocking {
        val cache = MemCache()
        val empty = AminoResolver(FdcClient({ null }, FdcTransport { _, _ -> error("no net") }), cache)
        val r = empty.resolve(listOf(FoodIngredient("tofu", 100.0)))
        assertNull(r.aminos)
        assertNull(r.ingredients[0].fdcId)

        val miss = AminoResolver(
            FdcClient({ "k" }, FdcTransport { _, _ -> """{"foods":[]}""" }),
            cache,
        )
        val r2 = miss.resolve(listOf(FoodIngredient("mystery dust", 10.0)))
        assertNull(r2.aminos)
        assertTrue(r2.ingredients[0].fdcId == null)
    }

    @Test fun ingredientsJsonRoundTrip() {
        val list = listOf(
            FoodIngredient("tofu, firm", 120.0, 99, "Tofu, raw, firm"),
            FoodIngredient("rice, white, cooked", 150.0),
        )
        val encoded = IngredientsJson.encode(list)!!
        val decoded = IngredientsJson.decode(encoded)!!
        assertEquals(2, decoded.size)
        assertEquals(99, decoded[0].fdcId)
        assertEquals("Tofu, raw, firm", decoded[0].matchedDescription)
        assertNull(decoded[1].fdcId)
    }
}
