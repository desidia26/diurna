package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One package photo → products with panel values per serving, ingredients, identity; questions when unsure. */
class PackagePromptTest {
    private fun wrap(products: String, questions: String = "") = """{"products":[$products],"questions":[$questions],"note":""}"""
    private fun one(json: String) = PackagePrompt.parse(json).products.single()
    private fun json(
        panel: Boolean = true,
        ingredients: String = "PECANS.",
        servingGrams: Double = 28.0,
        kcal: Double = 200.0,
        extra: String = "",
        name: String = "Pecan Halves",
        brand: String = "Member's Mark",
        centerX: Double = -1.0,
        printed: String = "",
    ) = wrap(product(panel, ingredients, servingGrams, kcal, extra, name, brand, centerX, printed))

    private fun product(
        panel: Boolean = true,
        ingredients: String = "PECANS.",
        servingGrams: Double = 28.0,
        kcal: Double = 200.0,
        extra: String = "",
        name: String = "Pecan Halves",
        brand: String = "Member's Mark",
        centerX: Double = -1.0,
        printed: String = "",
    ) = """{"has_nutrition_panel":$panel,"product_name":"$name","brand":"$brand","size_text":"NET WT 32 OZ (2 LB) 907g",
        "printed_barcode":"$printed","center_x":$centerX,
        "serving_text":"1 oz (28g/about 1/4 cup)","serving_grams":$servingGrams,"servings_per_container":32,
        "kcal":$kcal,"protein_g":3,"carbs_g":4,"fat_g":20,"fiber_g":3,"sugars_g":1,"added_sugars_g":0,
        "sat_fat_g":2,"trans_fat_g":0,"mono_fat_g":11,"poly_fat_g":6,"cholesterol_mg":0,"sodium_mg":0,
        "potassium_mg":115,"calcium_mg":20,"iron_mg":0.7,"vitamin_d_ug":0,
        "ingredients_text":"$ingredients","note":""$extra}"""

    @Test fun fullPanelParsesPerServingWithRealZeros() {
        val r = one(json())
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
        val r = one(json().replace("\"sodium_mg\":0", "\"sodium_mg\":-1").replace("\"serving_grams\":28.0", "\"serving_grams\":-1"))
        assertNull(r.perServing["sodium_mg"])
        assertFalse(r.perServing.containsKey("sodium_mg"))
        // Gram weight printed in the serving line still counts.
        assertEquals(28.0, r.servingGrams!!, 1e-9)
    }

    @Test fun volumeOnlyServingHasNoGrams() {
        val r = one(json(servingGrams = -1.0).replace("1 oz (28g/about 1/4 cup)", "1/4 cup"))
        assertNull(r.servingGrams)
    }

    @Test fun implausibleNumbersAreDroppedNotClamped() {
        val r = one(json().replace("\"fat_g\":20", "\"fat_g\":5000").replace("\"sodium_mg\":0", "\"sodium_mg\":-7"))
        assertNull(r.perServing["fat_g"])
        assertNull(r.perServing["sodium_mg"])
    }

    @Test fun ingredientsOnlyIsAPartialReading() {
        val r = one(json(panel = false, kcal = -1.0, ingredients = "Dried cranberries (sugar, cranberries), sunflower oil."))
        assertFalse(r.hasPanel)
        assertTrue("no panel: no numbers kept", r.perServing.isEmpty())
        assertEquals("Dried cranberries (sugar, cranberries), sunflower oil.", r.ingredientsText)
    }

    @Test fun panelWithoutCaloriesKeepsWhatWasRead() {
        val r = one(json(kcal = -1.0))
        assertNull(r.perServing["kcal"])
        assertEquals(20.0, r.perServing.getValue("fat_g"), 1e-9)
    }

    @Test fun nothingUsableFails() {
        try {
            PackagePrompt.parse(json(panel = false, kcal = -1.0, ingredients = "", name = "", brand = ""))
            fail("expected EstimateFailed")
        } catch (e: EstimateFailed) {
            assertTrue(e.message!!.contains("label", ignoreCase = true))
        }
    }

    @Test fun malformedFails() {
        for (bad in listOf("", "not json", "[]", "{\"products\":\"x\"}", wrap("{\"kcal\":\"two hundred\"}"))) {
            try { PackagePrompt.parse(bad); fail(bad) } catch (_: EstimateFailed) { }
        }
    }

    @Test fun textIsCappedAndControlCharactersRemoved() {
        val long = "A".repeat(5000)
        val r = one(json(ingredients = "Oats\\u0000, honey $long"))
        assertTrue(r.ingredientsText!!.length <= PackagePrompt.MAX_INGREDIENTS)
        assertFalse(r.ingredientsText!!.contains('\u0000'))
    }

    @Test fun severalProductsKeepOrderIdentityAndPosition() {
        val r = PackagePrompt.parse(wrap(
            product(name = "Pecan Halves", centerX = 0.2, printed = "0 78742 20233 4") + "," +
                product(name = "Sliced Almonds", brand = "Mariani", centerX = 0.8, kcal = 170.0, printed = "unreadable"),
        ))
        assertEquals(listOf("Pecan Halves", "Sliced Almonds"), r.products.map { it.productName })
        assertEquals(0.2, r.products[0].centerX!!, 1e-9)
        assertEquals("078742202334", r.products[0].printedBarcode) // digits only
        assertNull(r.products[1].printedBarcode) // not a code
        assertEquals("NET WT 32 OZ (2 LB) 907g", r.products[0].sizeText)
        assertTrue(r.questions.isEmpty())
    }

    @Test fun aFrontOfPackageIdentityIsKeptWithoutNumbers() {
        val r = one(json(panel = false, kcal = -1.0, ingredients = "", name = "Flax Seeds", brand = "Roots Circle"))
        assertFalse(r.hasPanel)
        assertTrue(r.perServing.isEmpty())
        assertNull(r.ingredientsText)
        assertEquals("Roots Circle", r.brand)
        assertNull(r.centerX)
    }

    @Test fun emptyProductsAreDroppedAndAtMostEightKept() {
        val empty = product(panel = false, kcal = -1.0, ingredients = "", name = "", brand = "")
        val r = PackagePrompt.parse(wrap(empty + "," + product()))
        assertEquals(1, r.products.size)
        val many = PackagePrompt.parse(wrap((1..12).joinToString(",") { product(name = "P$it") }))
        assertEquals(PackagePrompt.MAX_PRODUCTS, many.products.size)
    }

    @Test fun questionsAreParsedAndBounded() {
        val q = """{"product_index":0,"topic":"serving_basis","text":"Use the Dry or the Prepared column?","suggested_answers":["Dry","Prepared","a","b","c"]},""" +
            """{"product_index":-1,"topic":"weird","text":"Which package is the almonds?","suggested_answers":[]},""" +
            """{"product_index":7,"topic":"identity","text":"  ","suggested_answers":["x"]}"""
        val r = PackagePrompt.parse(wrap(product(), q))
        assertEquals(2, r.questions.size)
        assertEquals(0, r.questions[0].productIndex)
        assertEquals("serving_basis", r.questions[0].topic)
        assertEquals(4, r.questions[0].suggestions.size)
        assertNull(r.questions[1].productIndex)
        assertEquals("other", r.questions[1].topic)
    }

    @Test fun questionsAloneStillCountAsAReading() {
        val q = """{"product_index":-1,"topic":"identity","text":"Is this oats or barley?","suggested_answers":["Oats","Barley"]}"""
        val r = PackagePrompt.parse(wrap("", q))
        assertTrue(r.products.isEmpty())
        assertEquals(1, r.questions.size)
    }

    @Test fun interpretationRoundTripsThroughItsStoredJson() {
        val q = """{"product_index":0,"topic":"serving_basis","text":"Dry or prepared?","suggested_answers":["Dry"]}"""
        val r = PackagePrompt.parse(wrap(product(centerX = 0.5) + "," + product(name = "Oats", panel = false, kcal = -1.0, ingredients = "OATS"), q))
        assertEquals(r, PackagePrompt.parse(PackagePrompt.toJson(r)))
    }

    @Test fun refinementSendsThePreviousReadingAndTheAnswerAsData() {
        val r = PackagePrompt.parse(json())
        val text = PackagePrompt.refineText(r, "Use the dry column\u0007 " + "x".repeat(2000), hasPhoto = false)
        assertTrue(text.contains(PackagePrompt.toJson(r)))
        assertTrue(text.contains("Use the dry column"))
        assertFalse(text.contains('\u0007'))
        assertTrue(text.length < PackagePrompt.toJson(r).length + 1_200)
        assertTrue(text.contains("no photo", ignoreCase = true))
    }

    @Test fun schemaRequiresEveryPropertyForStrictStructuredOutput() {
        @Suppress("UNCHECKED_CAST")
        val props = (PackagePrompt.SCHEMA["properties"] as Map<String, Any>).keys
        @Suppress("UNCHECKED_CAST")
        val required = (PackagePrompt.SCHEMA["required"] as List<String>).toSet()
        assertEquals(props, required)
        assertEquals(false, PackagePrompt.SCHEMA["additionalProperties"])
        @Suppress("UNCHECKED_CAST")
        fun items(name: String) = ((PackagePrompt.SCHEMA["properties"] as Map<String, Any>)[name] as Map<String, Any>)["items"] as Map<String, Any>
        for (name in listOf("products", "questions")) {
            val item = items(name)
            @Suppress("UNCHECKED_CAST")
            assertEquals(name, (item["properties"] as Map<String, Any>).keys, (item["required"] as List<String>).toSet())
            assertEquals(false, item["additionalProperties"])
        }
        @Suppress("UNCHECKED_CAST")
        assertTrue(PackagePrompt.NUTRIENT_KEYS.all { it in (items("products")["properties"] as Map<String, Any>) })
    }
}
