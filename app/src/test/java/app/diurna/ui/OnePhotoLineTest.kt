package app.diurna.ui

import app.diurna.ai.PackageReading
import app.diurna.data.OnePhotoCapture.Capture
import app.diurna.data.OnePhotoCapture.CodeState
import app.diurna.data.OnePhotoCapture.LabelRead
import app.diurna.domain.IngredientUnit
import app.diurna.domain.PackageMerge.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirm screen → recipe line: amounts scale from per-100 g facts; unknown never becomes 0. */
class OnePhotoLineTest {
    private val reading = PackageReading(true, "Flax", "", "1 Tbsp (7g)", 7.0, 86.0,
        mapOf("kcal" to 35.0, "protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8, "fiber_g" to 1.4), "FLAXSEEDS")
    private val code = CodeState.Read("0860009719569", null, null)
    private val full = Capture(code, LabelRead.Read(reading))

    private fun ok(r: OnePhotoLine) = (r as OnePhotoLine.Ok).draft

    @Test fun gramsScaleFromPer100g() {
        val d = ok(onePhotoLine(full, ManualFill(), "Flax", amount = 14.0, servings = false))
        assertEquals(IngredientUnit.G, d.unit)
        assertEquals(14.0, d.grams!!, 0.0)
        assertEquals(70, d.kcal)
        assertEquals(3, d.nutrition.proteinG)
        assertEquals(6, d.nutrition.fatG)
        assertEquals(3, d.nutrition.fiberG)
        assertNull(d.nutrition.sugarsG)
        assertEquals("0860009719569", d.barcode)
        assertEquals("package", d.source)
        assertEquals(Source.LABEL.wire, d.captured!!.ingredientsSource)
        assertEquals(7.0, d.captured!!.servingGrams!!, 0.0)
    }

    @Test fun servingsUseTheServingWeight() {
        val d = ok(onePhotoLine(full, ManualFill(), "Flax", amount = 2.0, servings = true))
        assertEquals(14.0, d.grams!!, 1e-9)
        assertEquals(70, d.kcal)
    }

    @Test fun servingsWithoutAServingWeightAreRefused() {
        val noWeight = Capture(code, LabelRead.Read(reading.copy(servingGrams = null, servingText = "1 Tbsp")))
        assertFalse(servingsAvailable(noWeight, ManualFill()))
        val r = onePhotoLine(noWeight, ManualFill(), "Flax", amount = 2.0, servings = true)
        assertTrue(r is OnePhotoLine.Problem)
        // Typing the serving weight unlocks both the per-serving numbers and servings.
        val fill = ManualFill(servingGrams = 7.0)
        assertTrue(servingsAvailable(noWeight, fill))
        assertEquals(70, ok(onePhotoLine(noWeight, fill, "Flax", 2.0, servings = true)).kcal)
    }

    @Test fun missingRequiredValuesBlockSaveUntilTypedAndNeverBecomeZero() {
        val partial = Capture(code, LabelRead.Read(reading.copy(perServing = mapOf("kcal" to 35.0))))
        assertEquals(listOf("protein_g", "carbs_g", "fat_g"), missingRequired(partial, ManualFill()))
        val r = onePhotoLine(partial, ManualFill(), "Flax", 14.0, servings = false)
        assertTrue(r is OnePhotoLine.Problem)
        val fill = ManualFill(values = mapOf("protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8), perServing = true)
        val d = ok(onePhotoLine(partial, fill, "Flax", 14.0, servings = false))
        assertEquals(6, d.nutrition.fatG)
        assertNull(d.nutrition.fiberG)
        assertEquals(Source.MANUAL, d.captured!!.facts().getValue("fat_g").source)
        assertEquals(Source.LABEL, d.captured!!.facts().getValue("kcal").source)
    }

    @Test fun nothingFoundKeepsTheBarcodeAndTakesManualPer100g() {
        val empty = Capture(code, LabelRead.Skipped("offline", "Offline"))
        assertEquals(listOf("kcal", "protein_g", "carbs_g", "fat_g"), missingRequired(empty, ManualFill()))
        val fill = ManualFill(values = mapOf("kcal" to 500.0, "protein_g" to 20.0, "carbs_g" to 30.0, "fat_g" to 40.0))
        val d = ok(onePhotoLine(empty, fill, "Seeds", 50.0, servings = false))
        assertEquals(250, d.kcal)
        assertEquals("0860009719569", d.barcode)
        assertTrue(d.captured!!.labelPending())
    }

    @Test fun perServingManualValuesNeedAServingWeight() {
        val empty = Capture(CodeState.None, LabelRead.NoPhoto)
        val fill = ManualFill(values = mapOf("kcal" to 35.0, "protein_g" to 1.0, "carbs_g" to 2.0, "fat_g" to 3.0), perServing = true)
        assertTrue(onePhotoLine(empty, fill, "X", 10.0, servings = false) is OnePhotoLine.Problem)
    }

    @Test fun blankNameOrBadAmountIsAProblem() {
        assertTrue(onePhotoLine(full, ManualFill(), " ", 14.0, servings = false) is OnePhotoLine.Problem)
        assertTrue(onePhotoLine(full, ManualFill(), "Flax", null, servings = false) is OnePhotoLine.Problem)
        assertTrue(onePhotoLine(full, ManualFill(), "Flax", 0.0, servings = false) is OnePhotoLine.Problem)
    }

    @Test fun labelReadClearsThePendingMark() {
        val d = ok(onePhotoLine(full, ManualFill(), "Flax", 14.0, servings = false))
        assertFalse(d.captured!!.labelPending())
    }

    @Test fun sourceChipsListEachFieldWithItsSourceAndDisagreements() {
        val off = app.diurna.data.parseOffSnapshot("""{"status":1,"product":{"product_name":"Flax","serving_quantity":7,
            "nutriments":{"energy-kcal_100g":600,"proteins_100g":20,"carbohydrates_100g":30,"fat_100g":40}}}""", 1L)!!
        val draft = app.diurna.data.MealEntity(name = "Flax", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0,
            barcode = "0860009719569", offSnapshotJson = off.encode())
        val c = Capture(CodeState.Read("0860009719569", app.diurna.data.LibraryRepository.ScanResult.Found(draft), null), LabelRead.Read(reading))
        val rows = factRows(c, ManualFill())
        val kcal = rows.first { it.key == "kcal" }
        assertEquals(Source.LABEL, kcal.source)
        assertEquals(500.0, kcal.per100g, 0.01)
        assertEquals(listOf(Source.OFF), kcal.others.map { it.source })
        assertEquals("fiber_g", rows.last().key)
    }
}
