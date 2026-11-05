package app.diurna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffIngredientsTest {
    @Test fun percentTimesServingYieldsGrams() {
        val json = """[
            {"id":"en:oats","text":"oats","percent":60},
            {"id":"en:sugar","text":"sugar","percent_estimate":20.5},
            {"id":"en:oil","text":"sunflower oil","percent":10}
        ]"""
        val c = OffIngredients.fromStructured(json, servingGrams = 100.0)
        assertTrue(c.usable)
        assertEquals(3, c.ingredients.size)
        assertEquals("oats", c.ingredients[0].name)
        assertEquals(60.0, c.ingredients[0].grams, 0.01)
        assertEquals("sugar", c.ingredients[1].name)
        assertEquals(20.5, c.ingredients[1].grams, 0.01)
        assertEquals("sunflower oil", c.ingredients[2].name)
        assertEquals(10.0, c.ingredients[2].grams, 0.01)
    }

    @Test fun scalesWhenPercentsSumOver100() {
        val json = """[
            {"text":"noodles","percent_estimate":71.98},
            {"text":"seasoning","percent_estimate":31.73}
        ]"""
        val c = OffIngredients.fromStructured(json, servingGrams = 100.0)
        assertTrue(c.usable)
        val sum = c.ingredients.sumOf { it.grams }
        assertEquals(100.0, sum, 0.2)
    }

    @Test fun skipsNestedAndNamelessAndZero() {
        // Top-level only: nested objects are still top-level array entries in OFF;
        // we skip entries without percent and without a usable name.
        val json = """[
            {"id":"en:water","text":"water","percent":0},
            {"percent":5},
            {"text":"salt","percent":1.2},
            {"ingredients":[{"text":"sub","percent":50}],"text":"sauce","percent":8}
        ]"""
        val c = OffIngredients.fromStructured(json, servingGrams = 50.0)
        assertTrue(c.usable)
        assertEquals(2, c.ingredients.size)
        assertEquals("salt", c.ingredients[0].name)
        assertEquals(0.6, c.ingredients[0].grams, 0.01)
        assertEquals("sauce", c.ingredients[1].name)
        assertEquals(4.0, c.ingredients[1].grams, 0.01)
    }

    @Test fun taxonomyIdBecomesReadableName() {
        val json = """[{"id":"en:palm-oil","percent":12}]"""
        val c = OffIngredients.fromStructured(json, 100.0)
        assertEquals("palm oil", c.ingredients.single().name)
    }

    @Test fun noPercentsNotUsable() {
        val json = """[{"text":"flour"},{"text":"water"}]"""
        assertFalse(OffIngredients.fromStructured(json, 100.0).usable)
        assertFalse(OffIngredients.fromStructured(null, 100.0).usable)
        assertFalse(OffIngredients.fromStructured(json, 0.0).usable)
    }

    @Test fun snapshotRoundTripKeepsIngredients() {
        val snap = OffSnapshot(
            productName = "Bar",
            lookedUpAt = 1L,
            lookupOk = true,
            per100Json = """{"kcal":100}""",
            ingredientsText = "oats, sugar",
            ingredientsTextEn = "oats, sugar",
            ingredientsJson = """[{"text":"oats","percent":80}]""",
        )
        val decoded = OffSnapshot.decode(snap.encode())!!
        assertEquals("oats, sugar", decoded.ingredientsText)
        assertEquals("oats, sugar", decoded.ingredientsTextEn)
        assertTrue(OffIngredients.isUsable(decoded, 40.0))
        // Old snapshot without ingredient keys still decodes.
        val old = OffSnapshot.decode("""{"lookedUpAt":1,"lookupOk":true,"basisG":100,"productName":"X"}""")!!
        assertEquals(null, old.ingredientsJson)
        assertFalse(OffIngredients.isUsable(old))
    }
}
