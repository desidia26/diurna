package app.diurna.ai

import app.diurna.data.FdcFoodCacheDao
import app.diurna.data.FdcFoodCacheEntity
import app.diurna.data.OffSnapshot
import app.diurna.data.fdc.AminoResolver
import app.diurna.data.fdc.FdcClient
import app.diurna.data.fdc.FdcTransport
import app.diurna.data.fdc.IngredientsJson
import app.diurna.domain.FoodIngredient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeIngredientFillTest {
    private class MemCache : FdcFoodCacheDao {
        val rows = mutableMapOf<String, FdcFoodCacheEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun upsert(row: FdcFoodCacheEntity) { rows[row.queryKey] = row }
    }

    private fun resolver(): AminoResolver {
        val cache = MemCache()
        val transport = FdcTransport { path, _ ->
            when {
                path.contains("search") ->
                    """{"foods":[{"fdcId":10,"description":"Oats"}]}"""
                path.endsWith("/10") ->
                    """{"fdcId":10,"description":"Oats","foodNutrients":[
                      {"nutrient":{"id":1214},"amount":0.5}
                    ]}"""
                else -> null
            }
        }
        return AminoResolver(FdcClient({ "k" }, transport), cache)
    }

    private class FakeEstimator(
        private val list: List<FoodIngredient> = emptyList(),
        private val fail: Boolean = false,
    ) : FoodEstimator {
        var calls = 0
        override val model: String = "fake"
        override suspend fun estimate(
            text: String,
            photo: ByteArray?,
            detailed: Boolean,
            ingredients: Boolean,
        ) = throw UnsupportedOperationException()
        override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext): List<FoodIngredient> {
            calls++
            if (fail) throw EstimateFailed("nope")
            return list
        }
    }

    @Test fun prefersStructuredOffOverAi() = runBlocking {
        val snap = OffSnapshot(
            productName = "Bar",
            lookedUpAt = 1L,
            lookupOk = true,
            ingredientsJson = """[{"text":"oats","percent":100}]""",
            ingredientsText = "oats, sugar",
        )
        val est = FakeEstimator(listOf(FoodIngredient("should-not-use", 1.0)))
        val r = BarcodeIngredientFill.resolve(
            snap, 50.0, BarcodeIngredientFill.MacrosHint(kcal = 200),
            est, resolver(),
        )
        assertEquals(0, est.calls)
        assertEquals("off", r.source)
        assertNotNull(r.ingredientsJson)
        assertEquals("off", IngredientsJson.decodeWithSource(r.ingredientsJson)!!.source)
        assertEquals("oats", IngredientsJson.decode(r.ingredientsJson)!!.single().name)
        assertEquals(50.0, IngredientsJson.decode(r.ingredientsJson)!!.single().grams, 0.01)
        assertNotNull(r.aminos)
    }

    @Test fun fallsBackToAiWhenNoPercents() = runBlocking {
        val snap = OffSnapshot(
            productName = "Bar",
            lookedUpAt = 1L,
            lookupOk = true,
            ingredientsText = "oats, honey",
            ingredientsJson = """[{"text":"oats"},{"text":"honey"}]""",
        )
        val est = FakeEstimator(listOf(FoodIngredient("oats", 30.0), FoodIngredient("honey", 10.0)))
        val r = BarcodeIngredientFill.resolve(
            snap, 40.0, BarcodeIngredientFill.MacrosHint(),
            est, resolver(),
        )
        assertEquals(1, est.calls)
        assertEquals("ai", r.source)
        assertEquals(2, IngredientsJson.decode(r.ingredientsJson)!!.size)
    }

    @Test fun emptyWhenAiFails() = runBlocking {
        val snap = OffSnapshot(lookedUpAt = 1L, lookupOk = true, ingredientsText = "mystery")
        val est = FakeEstimator(fail = true)
        val r = BarcodeIngredientFill.resolve(
            snap, 40.0, BarcodeIngredientFill.MacrosHint(),
            est, resolver(),
        )
        assertEquals(1, est.calls)
        assertNull(r.ingredientsJson)
        assertNull(r.aminos)
        assertTrue(!r.wrote)
    }
}
