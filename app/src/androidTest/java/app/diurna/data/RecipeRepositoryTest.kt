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

    @Test fun capturedFactsSurviveSavingAndResavingTheRecipe() = runBlocking {
        val captured = CapturedFacts(
            ingredientsText = "PECANS.", ingredientsSource = "label", servingGrams = 28.0,
            servingText = "1 oz (28g)", factsJson = """{"basis":"per_100g","fields":{"kcal":{"v":714.3,"s":"label"}}}""",
        )
        val line = RecipeIngredientEntity(
            mealId = 0, position = 0, name = "Pecans", amount = 56.0, unit = "g", grams = 56.0,
            proteinG = 6, carbsG = 8, fatG = 40, kcal = 400, source = "photo", barcode = "0078742202334",
            captured = captured,
        )
        val meal = MealEntity(name = "Pecan pie bits", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, kind = MealEntity.KIND_RECIPE)
        assertEquals(LibraryRepository.Result.Ok, lib.saveRecipe(meal, listOf(line)))
        val saved = lib.observeMeals(MealEntity.KIND_RECIPE).first().single()
        val first = lib.ingredientsFor(saved.id).single()
        assertEquals(captured, first.captured)
        // Editing the recipe re-inserts lines; the capture travels with the line.
        assertEquals(LibraryRepository.Result.Ok, lib.saveRecipe(saved.copy(name = "Pecan bits"), listOf(first.copy(id = 0))))
        assertEquals(captured, lib.ingredientsFor(saved.id).single().captured)
        assertEquals(714.3, lib.ingredientsFor(saved.id).single().captured!!.facts().getValue("kcal").value, 1e-9)
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

    private val photoLines = listOf(
        RecipeIngredientEntity(mealId = 0, position = 0, name = "Rolled oats", amount = 40.0, unit = "g", grams = 40.0,
            proteinG = 5, carbsG = 27, fatG = 3, kcal = 150, fiberG = 4, source = "photo", barcode = "0011110766557"),
        RecipeIngredientEntity(mealId = 0, position = 1, name = "Sliced almonds", amount = 15.0, unit = "g", grams = 15.0,
            proteinG = 3, carbsG = 3, fatG = 8, kcal = 85, fiberG = 2, source = "photo"),
    )

    @Test fun mealFromPhotoLogsOneEntryWithoutTouchingTheLibrary() = runBlocking {
        assertEquals(LibraryRepository.Result.Ok, lib.logRecipe(t.mon, "Oats and almonds", photoLines, saveToLibrary = false))
        val food = t.food.observe(t.mon).first().single()
        assertEquals("Oats and almonds", food.text)
        // Labeled 150+85; not MacroEnergy 239 from the macros and fiber.
        assertEquals(235, food.kcalLow)
        assertEquals(8, food.proteinG)
        assertEquals(30, food.carbsG)
        assertEquals(11, food.fatG)
        assertEquals(6, food.fiberG)
        assertTrue(food.isMeal)
        assertEquals(null, food.mealId)
        assertTrue(lib.observeMeals(MealEntity.KIND_RECIPE).first().isEmpty())
        // Servings scale the entry's own numbers.
        lib.setServings(food.id, 2.0)
        assertEquals(470, t.food.observe(t.mon).first().single().kcalLow)
    }

    @Test fun mealFromPhotoCanAlsoSaveTheRecipe() = runBlocking {
        assertEquals(LibraryRepository.Result.Ok, lib.logRecipe(t.mon, "Oats and almonds", photoLines, saveToLibrary = true))
        val saved = lib.observeMeals(MealEntity.KIND_RECIPE).first().single()
        assertEquals(235, saved.kcal)
        assertEquals(listOf("Rolled oats", "Sliced almonds"), lib.ingredientsFor(saved.id).map { it.name })
        val food = t.food.observe(t.mon).first().single()
        assertEquals(saved.id, food.mealId)
        assertEquals(235, food.kcalLow)
        // A taken name is a problem to show; nothing more is logged.
        val again = lib.logRecipe(t.mon, "Oats and almonds", photoLines, saveToLibrary = true)
        assertTrue(again is LibraryRepository.Result.Problem)
        assertEquals(1, t.food.observe(t.mon).first().size)
        // Logging the same name without saving is fine.
        assertEquals(LibraryRepository.Result.Ok, lib.logRecipe(t.mon, "Oats and almonds", photoLines, saveToLibrary = false))
        assertEquals(2, t.food.observe(t.mon).first().size)
    }

    @Test fun mealFromPhotoNeedsANameAndIngredients() = runBlocking {
        assertTrue(lib.logRecipe(t.mon, "  ", photoLines, saveToLibrary = false) is LibraryRepository.Result.Problem)
        assertTrue(lib.logRecipe(t.mon, "Empty", emptyList(), saveToLibrary = false) is LibraryRepository.Result.Problem)
        assertTrue(t.food.observe(t.mon).first().isEmpty())
    }
}
