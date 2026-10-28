package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeRepositoryTest {
    private val t = TestDb()
    private val lib = t.library

    @After fun tearDown() = t.close()

    @Test fun saveRecipeSnapshotsAndLogsByServings() = runBlocking {
        val lines = listOf(
            RecipeIngredientEntity(
                mealId = 0, position = 0, name = "Oats", amount = 80.0, unit = "g", grams = 80.0,
                proteinG = 10, carbsG = 50, fatG = 5, kcal = 280, fiberG = 8,
                hisMg = 100, source = "manual",
            ),
            RecipeIngredientEntity(
                mealId = 0, position = 1, name = "Milk", amount = 200.0, unit = "g", grams = 200.0,
                proteinG = 6, carbsG = 10, fatG = 4, kcal = 100, source = "manual",
            ),
        )
        val meal = MealEntity(
            name = "Overnight oats", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0,
            kind = MealEntity.KIND_RECIPE, servingsYield = 2.0, cookedWeightG = 280.0,
        )
        assertEquals(LibraryRepository.Result.Ok, lib.saveRecipe(meal, lines))
        val saved = lib.observeMeals(MealEntity.KIND_RECIPE).first().single()
        assertEquals(MealEntity.KIND_RECIPE, saved.kind)
        assertEquals(2.0, saved.servingsYield!!, 0.0)
        // (10+6)/2 = 8 protein
        assertEquals(8, saved.proteinG)
        assertEquals(30, saved.carbsG) // (50+10)/2
        assertEquals(1, saved.aminoMatched)
        assertEquals(2, saved.aminoTotal)
        assertTrue(saved.aminoPartial)

        val ings = lib.ingredientsFor(saved.id)
        assertEquals(2, ings.size)
        assertEquals("Oats", ings[0].name)
        assertEquals(80.0, ings[0].grams!!, 0.0)

        lib.logMeal(t.mon, saved.id, servings = 1.0)
        val food = t.food.observe(t.mon).first().single()
        assertEquals(saved.kcal, food.kcalLow)
        assertEquals(saved.proteinG, food.proteinG)
        assertEquals(1.0, food.servings, 0.0)
    }

    @Test fun logByGramsUsesCookedWeight() = runBlocking {
        val line = RecipeIngredientEntity(
            mealId = 0, position = 0, name = "Chicken", amount = 200.0, unit = "g", grams = 200.0,
            proteinG = 40, carbsG = 0, fatG = 8, kcal = 240, source = "manual",
        )
        val meal = MealEntity(
            name = "Chicken batch", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0,
            kind = MealEntity.KIND_RECIPE, servingsYield = 1.0, cookedWeightG = 200.0,
        )
        assertEquals(LibraryRepository.Result.Ok, lib.saveRecipe(meal, listOf(line)))
        val saved = lib.observeMeals(MealEntity.KIND_RECIPE).first().single()
        assertEquals(LibraryRepository.Result.Ok, lib.logMealByGrams(t.mon, saved.id, 50.0))
        val food = t.food.observe(t.mon).first().single()
        assertEquals(0.25, food.servings, 1e-9)
        assertEquals((saved.proteinG * 0.25).toInt(), food.proteinG)
    }

    @Test fun refusesVolumeWithoutDensityOnSave() = runBlocking {
        val line = RecipeIngredientEntity(
            mealId = 0, position = 0, name = "Oil", amount = 1.0, unit = "tbsp", grams = null,
            proteinG = 0, carbsG = 0, fatG = 14, kcal = 120, source = "manual",
        )
        val meal = MealEntity(
            name = "Oily", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0,
            kind = MealEntity.KIND_RECIPE, servingsYield = 1.0,
        )
        val r = lib.saveRecipe(meal, listOf(line))
        assertTrue(r is LibraryRepository.Result.Problem)
    }

    @Test fun pastLogUnchangedWhenRecipeEdited() = runBlocking {
        val line = RecipeIngredientEntity(
            mealId = 0, position = 0, name = "Rice", amount = 100.0, unit = "g", grams = 100.0,
            proteinG = 8, carbsG = 70, fatG = 1, kcal = 320, source = "manual",
        )
        assertEquals(
            LibraryRepository.Result.Ok,
            lib.saveRecipe(
                MealEntity(name = "Rice bowl", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, kind = MealEntity.KIND_RECIPE, servingsYield = 1.0),
                listOf(line),
            ),
        )
        val saved = lib.observeMeals(MealEntity.KIND_RECIPE).first().single()
        lib.logMeal(t.mon, saved.id, 1.0)
        val before = t.food.observe(t.mon).first().single()

        val richer = line.copy(proteinG = 20, carbsG = 60, fatG = 2, kcal = 340)
        assertEquals(
            LibraryRepository.Result.Ok,
            lib.saveRecipe(saved.copy(name = "Rice bowl"), listOf(richer)),
        )
        val after = t.food.observe(t.mon).first().single()
        assertEquals(before.proteinG, after.proteinG)
        assertEquals(before.kcalLow, after.kcalLow)
        assertEquals(8, after.proteinG)
    }
}
