package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One package photo → panel values per serving + ingredients text; unknown stays null, 0 stays 0. */
class PackagePromptTest {
    private fun json(
        panel: Boolean = true,
        ingredients: String = "PECANS.",
        servingGrams: Double = 28.0,
        kcal: Double = 200.0,
        extra: String = "",
    ) = """{"has_nutrition_panel":$panel,"product_name":"Pecan Halves","brand":"Member's Mark",
        "serving_text":"1 oz (28g/about 1/4 cup)","serving_grams":$servingGrams,"servings_per_container":32,
        "kcal":$kcal,"protein_g":3,"carbs_g":4,"fat_g":20,"fiber_g":3,"sugars_g":1,"added_sugars_g":0,
        "sat_fat_g":2,"trans_fat_g":0,"mono_fat_g":11,"poly_fat_g":6,"cholesterol_mg":0,"sodium_mg":0,
        "potassium_mg":115,"calcium_mg":20,"iron_mg":0.7,"vitamin_d_ug":0,
        "ingredients_text":"$ingredients","note":""$extra}"""

    @Test fun fullPanelParsesPerServingWithRealZeros() {
        val r = PackagePrompt.parse(json())
        assertTrue(r.hasPanel)
        assertEquals("Pecan Halves", r.productName)
        assertEquals("Member's Mark", r.brand)
        assertEquals(28.0, r.servingGrams!!, 1e-9)
        assertEquals(32.0, r.servingsPerContainer!!, 1e-9)
        assertEquals(200.0, r.perServing.getValue("kcal"), 1e-9)
        assertEquals(0.0, r.perServing.getValue("trans_fat_g"), 1e-9) // printed 0 is a real 0
        assertEquals(0.7, r.perServing.getValue("iron_mg"), 1e-9)
        assertEquals("PECANS.", r.ingredientsText)
    }

    @Test fun minusOneMeansUnknownNotZero() {
        val r = PackagePrompt.parse(json().replace("\"sodium_mg\":0", "\"sodium_mg\":-1").replace("\"serving_grams\":28.0", "\"serving_grams\":-1"))
        assertNull(r.perServing["sodium_mg"])
        assertFalse(r.perServing.containsKey("sodium_mg"))
        // Gram weight printed in the serving line still counts.
        assertEquals(28.0, r.servingGrams!!, 1e-9)
    }

    @Test fun volumeOnlyServingHasNoGrams() {
        val r = PackagePrompt.parse(json(servingGrams = -1.0).replace("1 oz (28g/about 1/4 cup)", "1/4 cup"))
        assertNull(r.servingGrams)
    }

    @Test fun implausibleNumbersAreDroppedNotClamped() {
        val r = PackagePrompt.parse(json().replace("\"fat_g\":20", "\"fat_g\":5000").replace("\"sodium_mg\":0", "\"sodium_mg\":-7"))
        assertNull(r.perServing["fat_g"])
        assertNull(r.perServing["sodium_mg"])
    }

    @Test fun ingredientsOnlyIsAPartialReading() {
        val r = PackagePrompt.parse(json(panel = false, kcal = -1.0, ingredients = "Dried cranberries (sugar, cranberries), sunflower oil."))
        assertFalse(r.hasPanel)
        assertTrue("no panel: no numbers kept", r.perServing.isEmpty())
        assertEquals("Dried cranberries (sugar, cranberries), sunflower oil.", r.ingredientsText)
    }

    @Test fun panelWithoutCaloriesKeepsWhatWasRead() {
        val r = PackagePrompt.parse(json(kcal = -1.0))
        assertNull(r.perServing["kcal"])
        assertEquals(20.0, r.perServing.getValue("fat_g"), 1e-9)
    }

    @Test fun nothingUsableFails() {
        try {
            PackagePrompt.parse(json(panel = false, kcal = -1.0, ingredients = ""))
            fail("expected EstimateFailed")
        } catch (e: EstimateFailed) {
            assertTrue(e.message!!.contains("label", ignoreCase = true))
        }
    }

    @Test fun malformedFails() {
        for (bad in listOf("", "not json", "[]", "{\"kcal\":\"two hundred\"}")) {
            try { PackagePrompt.parse(bad); fail(bad) } catch (_: EstimateFailed) { }
        }
    }

    @Test fun textIsCappedAndControlCharactersRemoved() {
        val long = "A".repeat(5000)
        val r = PackagePrompt.parse(json(ingredients = "Oats\\u0000, honey $long"))
        assertTrue(r.ingredientsText!!.length <= PackagePrompt.MAX_INGREDIENTS)
        assertFalse(r.ingredientsText!!.contains('\u0000'))
    }

    @Test fun schemaRequiresEveryPropertyForStrictStructuredOutput() {
        @Suppress("UNCHECKED_CAST")
        val props = (PackagePrompt.SCHEMA["properties"] as Map<String, Any>).keys
        @Suppress("UNCHECKED_CAST")
        val required = (PackagePrompt.SCHEMA["required"] as List<String>).toSet()
        assertEquals(props, required)
        assertEquals(false, PackagePrompt.SCHEMA["additionalProperties"])
        assertTrue(PackagePrompt.NUTRIENT_KEYS.all { it in props })
    }
}
