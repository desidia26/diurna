package app.diurna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealRestaurantTest {
    private fun meal(
        kind: String = MealEntity.KIND_SIMPLE,
        restaurantName: String? = null,
        nutritionSource: String? = null,
    ) = MealEntity(
        name = "Burrito",
        kcal = 700,
        proteinG = 30,
        carbsG = 80,
        fatG = 25,
        kind = kind,
        restaurantName = restaurantName,
        nutritionSource = nutritionSource,
    )

    @Test fun simpleIsNotRestaurant() {
        val m = meal()
        assertTrue(m.isSimple)
        assertFalse(m.isRestaurant)
        assertFalse(m.isRecipe)
    }

    @Test fun restaurantFlagsAndSourceConstants() {
        val m = meal(
            kind = MealEntity.KIND_RESTAURANT,
            restaurantName = "Chipotle",
            nutritionSource = MealEntity.SOURCE_PUBLISHED,
        )
        assertTrue(m.isRestaurant)
        assertFalse(m.isSimple)
        assertFalse(m.isRecipe)
        assertEquals("Chipotle", m.restaurantName)
        assertEquals(MealEntity.SOURCE_PUBLISHED, m.nutritionSource)
        assertEquals("published", MealEntity.SOURCE_PUBLISHED)
        assertEquals("estimate", MealEntity.SOURCE_ESTIMATE)
        assertEquals("restaurant", MealEntity.KIND_RESTAURANT)
    }

    @Test fun nonRestaurantKeepsNullRestaurantFields() {
        val m = meal(kind = MealEntity.KIND_RECIPE)
        assertNull(m.restaurantName)
        assertNull(m.nutritionSource)
        assertTrue(m.isRecipe)
    }
}
