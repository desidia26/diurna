package app.diurna.ui

import app.diurna.ai.PackagePhotoReading
import app.diurna.ai.PackageReading
import app.diurna.data.DecisionLog
import app.diurna.data.OnePhotoCapture.Capture
import app.diurna.data.OnePhotoCapture.CodeRead
import app.diurna.data.OnePhotoCapture.LabelStatus
import app.diurna.domain.IngredientUnit
import app.diurna.domain.PackageMerge.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirm screen → recipe lines: amounts scale from per-100 g facts; unknown never becomes 0. */
class OnePhotoLineTest {
    private val reading = PackageReading(true, "Flax", "", "1 Tbsp (7g)", 7.0, 86.0,
        mapOf("kcal" to 35.0, "protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8, "fiber_g" to 1.4), "FLAXSEEDS")
    private val code = CodeRead("0860009719569", null, null, null)

    private fun cap(r: PackageReading? = reading, status: LabelStatus = LabelStatus.Read, codes: List<CodeRead> = listOf(code)) = Capture(
        status, if (r == null) DecisionLog() else DecisionLog().addRevision(PackagePhotoReading(listOf(r)), DecisionLog.ORIGIN_READ), codes,
    )
    private val full = cap()

    private fun ok(r: OnePhotoLine) = (r as OnePhotoLine.Ok).draft
    private fun line(c: Capture, fill: ManualFill, name: String, amount: Double?, servings: Boolean) =
        onePhotoLine(c, c.rows.single(), fill, name, amount, servings)

    @Test fun gramsScaleFromPer100g() {
        val d = ok(line(full, ManualFill(), "Flax", amount = 14.0, servings = false))
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

    @Test fun theSavedLineCarriesTheDecisionLogWithFieldWinnersAndWhy() {
        val d = ok(line(full, ManualFill(), "Flax", 14.0, servings = false))
        val log = DecisionLog.fromJson(d.captured!!.decisionLogJson)!!
        assertEquals(1, log.revisions.size)
        val kcal = log.entries.first { it.kind == DecisionLog.FIELD && it.text.startsWith("calories") }
        assertTrue(kcal.text, kcal.text.contains("Label") && kcal.text.contains(DecisionLog.PRECEDENCE))
        assertEquals(DecisionLog.SAVE, log.entries.last().kind)
        assertTrue(log.entries.last().text.contains("14 g"))
    }

    @Test fun servingsUseTheServingWeight() {
        val d = ok(line(full, ManualFill(), "Flax", amount = 2.0, servings = true))
        assertEquals(14.0, d.grams!!, 1e-9)
        assertEquals(70, d.kcal)
    }

    @Test fun servingsWithoutAServingWeightAreRefused() {
        val noWeight = cap(reading.copy(servingGrams = null, servingText = "1 Tbsp"))
        val row = noWeight.rows.single()
        assertFalse(servingsAvailable(row, ManualFill()))
        assertTrue(line(noWeight, ManualFill(), "Flax", 2.0, servings = true) is OnePhotoLine.Problem)
        val fill = ManualFill(servingGrams = 7.0)
        assertTrue(servingsAvailable(row, fill))
        assertEquals(70, ok(line(noWeight, fill, "Flax", 2.0, servings = true)).kcal)
    }

    @Test fun missingRequiredValuesBlockSaveUntilTypedAndNeverBecomeZero() {
        val partial = cap(reading.copy(perServing = mapOf("kcal" to 35.0)))
        assertEquals(listOf("protein_g", "carbs_g", "fat_g"), missingRequired(partial.rows.single(), ManualFill()))
        assertTrue(line(partial, ManualFill(), "Flax", 14.0, servings = false) is OnePhotoLine.Problem)
        val fill = ManualFill(values = mapOf("protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8), perServing = true)
        val d = ok(line(partial, fill, "Flax", 14.0, servings = false))
        assertEquals(6, d.nutrition.fatG)
        assertNull(d.nutrition.fiberG)
        assertEquals(Source.MANUAL, d.captured!!.facts().getValue("fat_g").source)
        assertEquals(Source.LABEL, d.captured!!.facts().getValue("kcal").source)
        assertTrue(DecisionLog.fromJson(d.captured!!.decisionLogJson)!!.entries.any { it.kind == DecisionLog.EDIT && "fat" in it.text })
    }

    @Test fun nothingFoundKeepsTheBarcodeAndTakesManualPer100g() {
        val empty = cap(r = null, status = LabelStatus.Skipped("offline", "Offline"))
        assertEquals(listOf("kcal", "protein_g", "carbs_g", "fat_g"), missingRequired(empty.rows.single(), ManualFill()))
        val fill = ManualFill(values = mapOf("kcal" to 500.0, "protein_g" to 20.0, "carbs_g" to 30.0, "fat_g" to 40.0))
        val d = ok(line(empty, fill, "Seeds", 50.0, servings = false))
        assertEquals(250, d.kcal)
        assertEquals("0860009719569", d.barcode)
        assertTrue(d.captured!!.labelPending())
    }

    @Test fun perServingManualValuesNeedAServingWeight() {
        val empty = cap(r = null, status = LabelStatus.NoPhoto, codes = emptyList())
        val fill = ManualFill(values = mapOf("kcal" to 35.0, "protein_g" to 1.0, "carbs_g" to 2.0, "fat_g" to 3.0), perServing = true)
        assertTrue(line(empty, fill, "X", 10.0, servings = false) is OnePhotoLine.Problem)
    }

    @Test fun blankNameOrBadAmountIsAProblem() {
        assertTrue(line(full, ManualFill(), " ", 14.0, servings = false) is OnePhotoLine.Problem)
        assertTrue(line(full, ManualFill(), "Flax", null, servings = false) is OnePhotoLine.Problem)
        assertTrue(line(full, ManualFill(), "Flax", 0.0, servings = false) is OnePhotoLine.Problem)
    }

    @Test fun labelReadClearsThePendingMark() {
        assertFalse(ok(line(full, ManualFill(), "Flax", 14.0, servings = false)).captured!!.labelPending())
    }

    @Test fun sourceRowsListEachFieldWithItsSourceAndDisagreements() {
        val off = app.diurna.data.parseOffSnapshot("""{"status":1,"product":{"product_name":"Flax","serving_quantity":7,
            "nutriments":{"energy-kcal_100g":600,"proteins_100g":20,"carbohydrates_100g":30,"fat_100g":40}}}""", 1L)!!
        val draft = app.diurna.data.MealEntity(name = "Flax", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0,
            barcode = "0860009719569", offSnapshotJson = off.encode())
        val c = cap(codes = listOf(CodeRead("0860009719569", null, app.diurna.data.LibraryRepository.ScanResult.Found(draft), null)))
        val rows = factRows(c.rows.single(), ManualFill())
        val kcal = rows.first { it.key == "kcal" }
        assertEquals(Source.LABEL, kcal.source)
        assertEquals(500.0, kcal.per100g, 0.01)
        assertEquals(listOf(Source.OFF), kcal.others.map { it.source })
        assertEquals("fiber_g", rows.last().key)
    }
}
