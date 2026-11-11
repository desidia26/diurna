package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutrientId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutrientServingReviewTest {
    private val t = TestDb()
    private val nutrients = NutrientRepository(t.db)
    private val lib = LibraryRepository(t.db, nutrients = nutrients) { t.now }
    @After fun close() = t.close()
    private fun snapshot(grams: Int = 30, sodium: Double = 0.1) = OffSnapshot(
        lookupOk = true, lookedUpAt = 1L,
        rawJson = """{"status":1,"product":{"serving_quantity":$grams,"nutriments":{"sodium_100g":$sodium,"iron_100g":0}}}""",
    )
    private suspend fun save(s: OffSnapshot = snapshot()): Long {
        assertEquals(LibraryRepository.Result.Ok, lib.saveMeal(MealEntity(
            name = "Serving regression", kcal = 90, proteinG = 1, carbsG = 2, fatG = 3,
            offSnapshotJson = s.encode(),
        )))
        return lib.observeMeals().first().single().id
    }
    private suspend fun sodium(id: Long) = nutrients.profile(NutrientOwner.FOOD_ENTRY, id)[NutrientId.SODIUM]!!
    @Test fun savedMealServingEditsAndRelogUseCorrectGrams() = runTest {
        val meal = save()
        lib.logMeal(t.mon, meal)
        val entry = t.db.foodDao().forDate(t.mon.key()).single()
        assertEquals(30.0, sodium(entry.id), 0.0001)
        assertEquals(0.0, nutrients.profile(NutrientOwner.FOOD_ENTRY, entry.id)[NutrientId.IRON]!!, 0.0)
        assertNull(nutrients.profile(NutrientOwner.FOOD_ENTRY, entry.id)[NutrientId.CALCIUM])
        lib.setServings(entry.id, 0.5)
        assertEquals(15.0, sodium(entry.id), 0.0001)
        lib.logMeal(t.mon, meal, servings = 2.0)
        assertEquals(listOf(15.0, 60.0), t.db.foodDao().forDate(t.mon.key()).map { sodium(it.id) })
        lib.deleteMeal(meal)
        lib.setServings(entry.id, 2.0)
        assertEquals(60.0, sodium(entry.id), 0.0001)
    }
    @Test fun backfillIsIdempotentAndExistingEntrySnapshotsStayFrozen() = runTest {
        val meal = save()
        lib.logMeal(t.mon, meal)
        val entry = t.db.foodDao().forDate(t.mon.key()).single()
        t.db.nutrientValueDao().deleteOwner(NutrientOwner.MEAL, meal)
        t.db.nutrientValueDao().deleteOwner(NutrientOwner.FOOD_ENTRY, entry.id)
        nutrients.backfillFromOffSnapshots()
        assertEquals(30.0, sodium(entry.id), 0.0001)
        nutrients.replace(NutrientOwner.FOOD_ENTRY, entry.id,
            MicronutrientProfile.of(NutrientId.SODIUM to 99.0), NutrientSource.OFF)
        nutrients.backfillFromOffSnapshots()
        assertEquals(99.0, sodium(entry.id), 0.0001)
        val saved = lib.getMeal(meal)!!
        lib.saveMeal(saved.copy(offSnapshotJson = snapshot(60, 0.2).encode()))
        assertEquals(entry.offSnapshotJson, t.db.foodDao().get(entry.id)!!.offSnapshotJson)
        lib.setServings(entry.id, 2.0)
        assertEquals(entry.offSnapshotJson, t.db.foodDao().get(entry.id)!!.offSnapshotJson)
        assertEquals(60.0, sodium(entry.id), 0.0001)
        lib.logMeal(t.mon, meal)
        assertEquals(120.0, sodium(t.db.foodDao().forDate(t.mon.key()).last().id), 0.0001)
    }
    @Test fun cachedLegacyMealAmountsCannotPoisonNewEntries() = runTest {
        val meal = save()
        nutrients.replace(NutrientOwner.MEAL, meal,
            MicronutrientProfile.of(NutrientId.SODIUM to 999.0), NutrientSource.OFF)
        lib.logMeal(t.mon, meal)
        assertEquals(30.0, sodium(t.db.foodDao().forDate(t.mon.key()).single().id), 0.0001)
    }
}
