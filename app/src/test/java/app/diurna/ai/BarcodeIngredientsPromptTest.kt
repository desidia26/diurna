package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeIngredientsPromptTest {
    @Test fun userMessageIncludesHintsNotAsOrders() {
        val msg = BarcodeIngredientsPrompt.userMessage(
            BarcodeIngredientsContext(
                productName = "Oat bar",
                brand = "Acme",
                servingGrams = 45.0,
                servingSize = "1 bar",
                ingredientsText = "oats, sugar, oil",
                kcal = 200,
                proteinG = 5,
                carbsG = 30,
                fatG = 8,
            ),
        )
        assertTrue(msg.contains("Product: Oat bar"))
        assertTrue(msg.contains("Brand: Acme"))
        assertTrue(msg.contains("45 g"))
        assertTrue(msg.contains("Ingredients text: oats, sugar, oil"))
        assertTrue(msg.contains("do not change"))
        assertTrue("amino" !in msg.lowercase() || "Do NOT invent amino" in BarcodeIngredientsPrompt.SYSTEM)
    }

    @Test fun parseIngredientsOnly() {
        val list = BarcodeIngredientsPrompt.parse(
            """{"ingredients":[{"name":"oats, rolled","grams":30},{"name":"sugar","grams":5},{"name":"skip","grams":0}]}""",
        )
        assertEquals(2, list.size)
        assertEquals("oats, rolled", list[0].name)
        assertEquals(30.0, list[0].grams, 0.0)
    }

    @Test fun schemaRequiresOnlyIngredients() {
        val required = BarcodeIngredientsPrompt.SCHEMA["required"] as List<*>
        assertEquals(listOf("ingredients"), required)
        assertTrue("kcal_low" !in (BarcodeIngredientsPrompt.SCHEMA["properties"] as Map<*, *>).keys.map { it.toString() })
    }
}
