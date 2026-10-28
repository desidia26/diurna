package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.data.LibraryRepository.Result
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val t = TestDb()
    private val lib = t.library
    private val mon = t.mon
    private val shake = MealEntity(name = "Premier protein shake", kcal = 160, proteinG = 30, carbsG = 5, fatG = 3, note = "1 bottle")

    @After fun tearDown() = t.close()

    private suspend fun meals() = lib.observeMeals().first()
    private suspend fun food() = t.food.observe(mon).first()

    // ---- Meals ----

    @Test fun loggingAMealCopiesExactNumbersWithoutAi() = runTest {
        lib.saveMeal(shake)
        lib.logMeal(mon, meals().single().id)
        val e = food().single()
        assertTrue(e.isMeal)
        assertEquals("Premier protein shake", e.text)
        assertEquals(160, e.kcalLow)
        assertEquals(160, e.kcalHigh)
        assertEquals(30, e.proteinG)
        assertTrue("counts toward totals", e.estimatedAt != null)
        assertTrue("never queued for AI", t.db.foodDao().pendingEstimates().isEmpty())
    }

    @Test fun aMealLoggedWithAPhotoKeepsIt() = runTest {
        lib.saveMeal(shake)
        lib.logMeal(mon, meals().single().id, photo = "shake.jpg")
        val e = food().single()
        assertEquals("shake.jpg", e.photo)
        assertTrue("still exact, never queued for AI", e.isMeal && t.db.foodDao().pendingEstimates().isEmpty())
    }

    @Test fun servingsScaleTheNumbers() = runTest {
        lib.saveMeal(shake)
        lib.logMeal(mon, meals().single().id)
        lib.setServings(food().single().id, 2.0)
        assertEquals(320, food().single().kcalLow)
        assertEquals(60, food().single().proteinG)
        lib.setServings(food().single().id, 0.5)
        assertEquals(80, food().single().kcalLow)
    }

    @Test fun editingAMealDoesNotRewriteHistory() = runTest {
        lib.saveMeal(shake)
        val meal = meals().single()
        lib.logMeal(mon, meal.id)
        lib.saveMeal(meal.copy(kcal = 200))
        assertEquals(160, food().single().kcalLow)
        lib.logMeal(mon, meal.id)
        assertEquals(listOf(160, 200), food().map { it.kcalLow })
    }

    @Test fun deletingAMealKeepsPastEntriesAndTheirServings() = runTest {
        lib.saveMeal(shake)
        lib.logMeal(mon, meals().single().id)
        lib.deleteMeal(meals().single().id)
        val e = food().single()
        assertNull(e.mealId)
        assertEquals(160, e.kcalLow)
        lib.setServings(e.id, 2.0) // scales from the entry's own numbers now
        assertEquals(320, food().single().kcalLow)
    }

    @Test fun saveEntryAsMealLinksTheEntry() = runTest {
        t.food.add(mon, "egg white grill")
        val entry = food().single()
        val r = lib.saveEntryAsMeal(entry.id, MealEntity(name = "Egg white grill", kcal = 300, proteinG = 27, carbsG = 31, fatG = 7))
        assertEquals(Result.Ok, r)
        val e = food().single()
        assertTrue(e.isMeal)
        assertEquals(meals().single().id, e.mealId)
        assertEquals(300, e.kcalLow)
    }

    @Test fun editingAMealEntrysTextMakesItAnAiEntry() = runTest {
        lib.saveMeal(shake)
        lib.logMeal(mon, meals().single().id)
        t.food.edit(food().single().id, "shake plus a banana", food().single().time)
        val e = food().single()
        assertFalse(e.isMeal)
        assertNull(e.mealId)
        assertNull("waiting for an estimate", e.kcalLow)
    }

    @Test fun mealNamesMustBeUniqueAndNonBlank() = runTest {
        assertEquals(Result.Ok, lib.saveMeal(shake))
        assertTrue(lib.saveMeal(shake.copy(name = "premier PROTEIN shake")) is Result.Problem)
        assertTrue(lib.saveMeal(shake.copy(name = "  ")) is Result.Problem)
        // Updating a meal under its own name is fine.
        assertEquals(Result.Ok, lib.saveMeal(meals().single().copy(kcal = 170)))
    }

    @Test fun mostLoggedMealsComeFirst() = runTest {
        lib.saveMeal(shake)
        lib.saveMeal(MealEntity(name = "Apple", kcal = 95, proteinG = 0, carbsG = 25, fatG = 0))
        lib.logMeal(mon, meals().first { it.name == "Premier protein shake" }.id)
        assertEquals("Premier protein shake", meals().first().name)
    }

    @Test fun servingsOnlyApplyToMealEntries() = runTest {
        t.food.add(mon, "eggs")
        val e = food().single()
        lib.setServings(e.id, 2.0)
        assertEquals(1.0, food().single().servings, 0.0)
        assertFalse(food().single().isMeal)
    }

    @Test fun backfilledMealGetsNoonAndTodaysGetsNow() = runTest {
        lib.saveMeal(shake)
        val id = meals().single().id
        t.now = mon.atTime(8, 42)
        lib.logMeal(mon, id)
        lib.logMeal(mon.minusDays(1), id)
        assertEquals("08:42", food().single().time)
        assertEquals("12:00", t.food.observe(mon.minusDays(1)).first().single().time)
    }

    // ---- Exercises ----

    @Test fun renameCarriesHistory() = runTest {
        t.days.startBlankWorkout(mon)
        t.days.addExercise(mon, "db bench press")
        val id = lib.observeCatalog().first().single { it.name == "db bench press" }.id
        assertEquals(Result.Ok, lib.renameExercise(id, "Dumbbell bench press"))
        assertEquals("Dumbbell bench press", t.days.observeWorkout(mon).first()!!.exercises.single().exercise.name)
    }

    @Test fun renameCannotCollide() = runTest {
        lib.addExercise("Squat")
        lib.addExercise("Front squat")
        val front = lib.observeCatalog().first().single { it.name == "Front squat" }.id
        assertTrue(lib.renameExercise(front, "squat") is Result.Problem)
    }

    @Test fun onlyUnusedExercisesCanBeDeletedOthersCanBeHidden() = runTest {
        lib.addExercise("Unused")
        t.days.startBlankWorkout(mon)
        t.days.addExercise(mon, "Used")
        val cat = lib.observeCatalog().first()
        assertTrue(lib.deleteExercise(cat.single { it.name == "Unused" }.id))
        val used = cat.single { it.name == "Used" }
        assertTrue(used.inUse)
        assertFalse(lib.deleteExercise(used.id))

        lib.setExerciseHidden(used.id, true)
        assertFalse("hidden from suggestions", "Used" in t.routine.observeExerciseNames(cardio = false).first())
        // Typing it again brings it back.
        t.days.addExercise(mon, "used")
        assertTrue("Used" in t.routine.observeExerciseNames(cardio = false).first())
    }

    // ---- Barcodes ----

    private val shakeJson = """{"status":1,"product":{"product_name":"Protein shake","brands":"Premier Protein",
        "serving_size":"1 bottle","nutriments":{"energy-kcal_serving":160,"proteins_serving":30,"carbohydrates_serving":5,"fat_serving":3}}}"""

    @Test fun newBarcodeIsLookedUpThenSavedWithItsBarcode() = runTest {
        t.products["0012345678905"] = shakeJson
        val found = lib.scan("012345678905") as LibraryRepository.ScanResult.Found
        assertEquals("Protein shake (Premier Protein)", found.draft.name)
        assertEquals("0012345678905", found.draft.barcode)
        assertTrue("not saved until checked", meals().isEmpty())

        assertEquals(Result.Ok, lib.saveMeal(found.draft))
        assertEquals("0012345678905", meals().single().barcode)
    }

    @Test fun knownBarcodeComesFromTheLibraryWithoutALookup() = runTest {
        lib.saveMeal(shake.copy(barcode = "0012345678905"))
        t.productsReachable = false
        // Scanned as UPC-A this time; saved as EAN-13.
        val known = lib.scan("012345678905") as LibraryRepository.ScanResult.Known
        assertEquals("Premier protein shake", known.meal.name)
        assertTrue("no network needed", t.productFetches.isEmpty())
    }

    @Test fun unknownAndUnreachable() = runTest {
        val missing = lib.scan("0012345678905") as LibraryRepository.ScanResult.NotFound
        assertEquals("0012345678905", missing.draft.barcode)
        assertNotNull(missing.draft.offSnapshotJson)
        assertFalse(OffSnapshot.decode(missing.draft.offSnapshotJson)!!.lookupOk)
        val bad = lib.scan("not a barcode") as LibraryRepository.ScanResult.NotFound
        assertNull(bad.draft.barcode)
        t.productsReachable = false
        val failed = lib.scan("0012345678905") as LibraryRepository.ScanResult.Failed
        assertEquals("0012345678905", failed.draft.barcode)
        assertNotNull(failed.draft.offSnapshotJson)
    }

    @Test fun loggingScannedMealWithServingsScalesIt() = runTest {
        lib.saveMeal(shake.copy(barcode = "0012345678905", offSnapshotJson = OffSnapshot.failed(1L).encode()))
        lib.logMeal(mon, lib.mealByBarcode("0012345678905")!!.id, 1.5)
        val e = food().single()
        assertEquals(240, e.kcalLow)
        assertEquals(45, e.proteinG)
        assertEquals(1.5, e.servings, 0.0)
        assertEquals("0012345678905", e.barcode)
        assertNotNull(e.offSnapshotJson)
    }

    @Test fun garbageAnswerIsNotFound() = runTest {
        t.products["0012345678905"] = "<html>not json</html>"
        val r = lib.scan("0012345678905") as LibraryRepository.ScanResult.NotFound
        assertEquals("0012345678905", r.draft.barcode)
    }

    @Test fun failedLookupStillPersistsBarcodeOnLog() = runTest {
        t.productsReachable = false
        val failed = lib.scan("0012345678905") as LibraryRepository.ScanResult.Failed
        val draft = failed.draft.copy(name = "Manual shake", kcal = 160, proteinG = 30, carbsG = 5, fatG = 3)
        assertEquals(Result.Ok, lib.saveMeal(draft))
        val meal = meals().single()
        assertEquals("0012345678905", meal.barcode)
        assertFalse(OffSnapshot.decode(meal.offSnapshotJson)!!.lookupOk)
        lib.logMeal(mon, meal.id, 1.0)
        val e = food().single()
        assertEquals("0012345678905", e.barcode)
        assertFalse(OffSnapshot.decode(e.offSnapshotJson)!!.lookupOk)
        assertEquals(160, e.kcalLow) // user values unchanged
    }

    @Test fun foundScanStoresOffSnapshotAndLogCopiesIt() = runTest {
        t.products["0012345678905"] = shakeJson
        val found = lib.scan("012345678905") as LibraryRepository.ScanResult.Found
        val snap = OffSnapshot.decode(found.draft.offSnapshotJson)!!
        assertTrue(snap.lookupOk)
        assertNotNull(snap.per100Json)
        assertEquals(Result.Ok, lib.saveMeal(found.draft))
        lib.logMeal(mon, meals().single().id, 1.0)
        val e = food().single()
        assertEquals("0012345678905", e.barcode)
        assertTrue(OffSnapshot.decode(e.offSnapshotJson)!!.lookupOk)
    }

    @Test fun twoMealsCantShareABarcode() = runTest {
        lib.saveMeal(shake.copy(barcode = "0012345678905"))
        val r = lib.saveMeal(shake.copy(name = "Other", barcode = "0012345678905"))
        assertTrue(r is Result.Problem)
        assertEquals(1, meals().size)
    }

    @Test fun barcodeLookupOnlyEverSendsDigits() = runTest {
        lib.scan("../../admin?x=1")
        lib.scan("12345678;rm")
        assertTrue(t.productFetches.isEmpty())
    }
}
