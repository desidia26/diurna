package app.diurna.data.fdc

import app.diurna.domain.FoodIngredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientsJsonTest {
    @Test fun arrayBackCompat() {
        val raw = """[{"name":"oats","grams":40},{"name":"sugar","grams":5,"fdcId":123,"matched":"Sugars, granulated"}]"""
        val list = IngredientsJson.decode(raw)!!
        assertEquals(2, list.size)
        assertEquals(123, list[1].fdcId)
        assertEquals(null, IngredientsJson.decodeWithSource(raw)!!.source)
    }

    @Test fun encodeWithSourceRoundTrips() {
        val list = listOf(FoodIngredient("oats", 40.0, fdcId = 9, matchedDescription = "Oats"))
        val raw = IngredientsJson.encode(list, "off")!!
        val enc = IngredientsJson.decodeWithSource(raw)!!
        assertEquals("off", enc.source)
        assertEquals("oats", enc.ingredients.single().name)
        assertEquals(9, enc.ingredients.single().fdcId)
        assertEquals(listOf(FoodIngredient("oats", 40.0, 9, "Oats")), IngredientsJson.decode(raw))
    }

    @Test fun encodeWithoutSourceStaysArray() {
        val raw = IngredientsJson.encode(listOf(FoodIngredient("salt", 1.0)))!!
        assertEquals('[', raw.trim().first())
        assertNull(IngredientsJson.decodeWithSource(raw)!!.source)
    }
}
