package app.diurna.ai

import app.diurna.data.FoodEntryEntity
import app.diurna.data.MealEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DetailBackfillTest {

    private fun entry(
        carbs: Int = 40,
        fat: Int = 20,
        protein: Int = 30,
        kcalLow: Int = 400,
        kcalHigh: Int = 500,
        source: String = FoodEntryEntity.SOURCE_AI,
        fiber: Int? = null,
        sugars: Int? = null,
        satFat: Int? = null,
        his: Int? = null,
        mealId: Long? = null,
        servings: Double = 1.0,
        estimatedAt: Long? = 1L,
        note: String? = "note",
        model: String? = "model",
    ) = FoodEntryEntity(
        id = 1, date = "2026-10-01", time = "12:00", text = "rice", loggedAt = 1L,
        kcalLow = kcalLow, kcalHigh = kcalHigh, proteinG = protein, carbsG = carbs, fatG = fat,
        fiberG = fiber, sugarsG = sugars, satFatG = satFat, hisMg = his,
        estimateNote = note, estimateModel = model, estimatedAt = estimatedAt,
        source = source, mealId = mealId, servings = servings,
    )

    @Test fun mergeNeverOverwritesExisting() {
        val current = DetailBackfill.Detail(fiberG = 5, sugarsG = null, satFatG = 3, hisMg = 10)
        val incoming = DetailBackfill.Detail(fiberG = 99, sugarsG = 12, satFatG = 99, hisMg = 99, starchG = 8)
        val merged = DetailBackfill.mergeNullsOnly(current, incoming, storedCarbs = 40, storedFat = 20)
        assertEquals(5, merged.fiberG) // kept
        assertEquals(12, merged.sugarsG) // filled
        assertEquals(3, merged.satFatG) // kept
        assertEquals(10, merged.hisMg) // kept
        assertEquals(8, merged.starchG) // filled
    }

    @Test fun clampToStoredParents() {
        val incoming = DetailBackfill.Detail(
            fiberG = 100, sugarsG = 50, starchG = 80,
            satFatG = 30, omega3G = 25, cholesterolMg = 200,
        )
        val clamped = DetailBackfill.clampToParents(incoming, carbs = 40, fat = 20)
        assertEquals(40, clamped.fiberG)
        assertEquals(40, clamped.sugarsG)
        assertEquals(40, clamped.starchG)
        assertEquals(20, clamped.satFatG)
        assertEquals(20, clamped.omega3G)
        assertEquals(200, clamped.cholesterolMg) // not clamped to fat grams
    }

    @Test fun mergeClampsNewlyFilledToStoredCarbsEvenWhenEstimateDiverges() {
        // Stored carbs=40; new estimate claims carbs would be 90 with fiber 70 — we only fill nulls
        // and clamp to stored 40.
        val current = DetailBackfill.fromEntry(entry(carbs = 40, fiber = null))
        val incoming = DetailBackfill.Detail(fiberG = 70, sugarsG = 20)
        val merged = DetailBackfill.mergeNullsOnly(current, incoming, storedCarbs = 40, storedFat = 20)
        assertEquals(40, merged.fiberG)
        assertEquals(20, merged.sugarsG)
    }

    @Test fun mealCopyScalesByServings() {
        val meal = MealEntity(
            id = 9, name = "bowl", kcal = 500, proteinG = 30, carbsG = 50, fatG = 20,
            fiberG = 10, satFatG = 4, hisMg = 100, aminoMatched = 2, aminoTotal = 3,
            ingredientsJson = "[]",
        )
        val d = DetailBackfill.fromMeal(meal, servings = 2.0)
        assertEquals(20, d.fiberG)
        assertEquals(8, d.satFatG)
        assertEquals(200, d.hisMg)
        assertEquals(2, d.aminoMatched)
        assertEquals(3, d.aminoTotal)
        assertEquals("[]", d.ingredientsJson)
    }

    @Test fun mealMergeFillsNullsOnly() {
        val entry = entry(source = FoodEntryEntity.SOURCE_MEAL, mealId = 9, fiber = 1, satFat = null, servings = 1.5)
        val meal = MealEntity(
            id = 9, name = "bowl", kcal = 500, proteinG = 30, carbsG = 50, fatG = 20,
            fiberG = 10, satFatG = 4,
        )
        val merged = DetailBackfill.mergeNullsOnly(
            DetailBackfill.fromEntry(entry),
            DetailBackfill.fromMeal(meal, entry.servings),
            entry.carbsG, entry.fatG,
        )
        assertEquals(1, merged.fiberG) // kept existing
        assertEquals(6, merged.satFatG) // 4 * 1.5
    }

    @Test fun idempotentNeedsAiFillFalseWhenComplete() {
        val complete = entry(
            fiber = 5, sugars = 10, satFat = 3, his = 1,
        ).copy(
            solubleFiberG = 1, insolubleFiberG = 4, addedSugarsG = 0, starchG = 20,
            monoFatG = 5, polyFatG = 5, transFatG = 0, cholesterolMg = 0,
            omega3G = 1, omega6G = 2, omega9G = 3,
            ileMg = 1, leuMg = 1, lysMg = 1, metMg = 1, pheMg = 1, thrMg = 1, trpMg = 1,
            valMg = 1, cysMg = 1, tyrMg = 1,
            ingredientsJson = "[]", aminoMatched = 1, aminoTotal = 1,
        )
        assertFalse(DetailBackfill.needsAiFill(complete, wantAminos = true))
        assertFalse(DetailBackfill.needsAiFill(complete, wantAminos = false))
    }

    @Test fun needsAiFillWhenSubMacroNull() {
        assertTrue(DetailBackfill.needsAiFill(entry(fiber = null), wantAminos = false))
    }

    @Test fun needsAiFillAminosOnlyWhenKey() {
        val e = entry(fiber = 5).copy(
            solubleFiberG = 1, insolubleFiberG = 4, sugarsG = 10, addedSugarsG = 0, starchG = 20,
            satFatG = 3, monoFatG = 5, polyFatG = 5, transFatG = 0, cholesterolMg = 0,
            omega3G = 1, omega6G = 2, omega9G = 3,
            // aminos still null
        )
        assertFalse(DetailBackfill.needsAiFill(e, wantAminos = false))
        assertTrue(DetailBackfill.needsAiFill(e, wantAminos = true))
    }

    @Test fun skipsPendingNeverEstimated() {
        val pending = entry(estimatedAt = null)
        assertFalse(DetailBackfill.needsAiFill(pending, wantAminos = true))
        assertFalse(DetailBackfill.needsMealAttempt(pending))
    }

    @Test fun rangeLast30AndAll() {
        val today = LocalDate.of(2026, 10, 28)
        val r = DetailBackfill.Range.lastDays(today, 30)
        assertEquals(LocalDate.of(2026, 9, 29), r.from)
        assertEquals(today, r.to)
        assertEquals("2026-09-29", r.fromKey())
        assertEquals("2026-10-28", r.toKey())
        val all = DetailBackfill.Range.all()
        assertNull(all.from)
        assertNull(all.to)
        assertNull(all.fromKey())
        assertNull(all.toKey())
    }

    @Test fun unchangedMacrosAfterMerge() {
        val a = entry(id = 1, carbs = 40, fat = 20, protein = 30, kcalLow = 400, kcalHigh = 500)
        val b = entry(id = 2, carbs = 10, fat = 5, protein = 8, kcalLow = 100, kcalHigh = 120).copy(id = 2)
        // helper: FoodEntryEntity doesn't take id in our factory as named - use copy
        val entries = listOf(a, b.copy(id = 2, text = "beans"))
        val before = DetailBackfill.sumMacros(entries)
        // Simulate fill: only detail changes; macros fields on entities unchanged.
        val afterDetail = entries.map { e ->
            val merged = DetailBackfill.mergeNullsOnly(
                DetailBackfill.fromEntry(e),
                DetailBackfill.Detail(fiberG = 9, satFatG = 2),
                e.carbsG, e.fatG,
            )
            // macros snapshot from original entry must equal post-merge macros
            assertEquals(DetailBackfill.macrosOf(e), DetailBackfill.MacroSnapshot(e.kcalLow, e.kcalHigh, e.proteinG, e.carbsG, e.fatG))
            assertNotEquals(DetailBackfill.fromEntry(e).fiberG, merged.fiberG) // detail did change
            e // entity macros untouched
        }
        assertEquals(before, DetailBackfill.sumMacros(afterDetail))
        assertEquals(500, before.kcalLow) // 400+100
        assertEquals(620, before.kcalHigh)
        assertEquals(38, before.protein)
        assertEquals(50, before.carbs)
        assertEquals(25, before.fat)
    }

    // silence unused - entry() default id
    private fun entry(id: Long, carbs: Int, fat: Int, protein: Int, kcalLow: Int, kcalHigh: Int) =
        entry(carbs = carbs, fat = fat, protein = protein, kcalLow = kcalLow, kcalHigh = kcalHigh).copy(id = id)
}
