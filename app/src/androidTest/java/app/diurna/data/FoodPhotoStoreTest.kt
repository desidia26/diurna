package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FoodPhotoStoreTest {
    private val t = TestDb()

    @After fun tearDown() = t.close()

    private fun touch(): String {
        val name = t.foodPhotos.newName()
        File(t.photoDir, name).writeBytes(byteArrayOf(1, 2, 3))
        return name
    }

    @Test fun addEntryWithGallerySyncsPrimaryAndRows() = runTest {
        val a = touch()
        val b = touch()
        t.food.add(t.mon, "burrito", listOf(a, b))
        val e = t.food.observe(t.mon).first().single()
        assertEquals(a, e.photo)
        val gallery = t.foodPhotoStore.forEntry(e.id)
        assertEquals(listOf(a, b), gallery.map { it.fileName })
        assertEquals(listOf(0, 1), gallery.map { it.position })
    }

    @Test fun replaceAllCapsAtFiveAndDeletesOrphans() = runTest {
        val names = (1..6).map { touch() }
        t.food.add(t.mon, "bowl", names)
        val e = t.food.observe(t.mon).first().single()
        val gallery = t.foodPhotoStore.forEntry(e.id)
        assertEquals(5, gallery.size)
        assertEquals(names.take(5), gallery.map { it.fileName })
        assertFalse("6th file dropped as orphan", File(t.photoDir, names[5]).exists())
        assertTrue(File(t.photoDir, names[0]).exists())
    }

    @Test fun deleteEntryRemovesGalleryFiles() = runTest {
        val a = touch()
        t.food.add(t.mon, "x", listOf(a))
        val id = t.food.observe(t.mon).first().single().id
        t.food.delete(id)
        assertTrue(t.foodPhotoStore.forEntry(id).isEmpty())
        assertFalse(File(t.photoDir, a).exists())
    }

    @Test fun mealGalleryIndependentOfEntryPrimary() = runTest {
        val m1 = touch()
        val m2 = touch()
        assertEquals(
            LibraryRepository.Result.Ok,
            t.library.saveMealWithPhotos(
                MealEntity(name = "Bowl", kcal = 500, proteinG = 30, carbsG = 50, fatG = 15),
                listOf(m1, m2),
            ),
        )
        val mealId = t.library.observeMeals().first().single().id
        assertEquals(listOf(m1, m2), t.foodPhotoStore.forMeal(mealId).map { it.fileName })
        t.library.deleteMeal(mealId)
        assertTrue(t.foodPhotoStore.forMeal(mealId).isEmpty())
        assertFalse(File(t.photoDir, m1).exists())
        assertFalse(File(t.photoDir, m2).exists())
    }

    @Test fun editSecondaryPhotosDoesNotClearPrimary() = runTest {
        val a = touch()
        val b = touch()
        t.food.add(t.mon, "taco", listOf(a))
        val id = t.food.observe(t.mon).first().single().id
        t.food.edit(id, "taco", "12:00", listOf(a, b))
        val e = t.food.observe(t.mon).first().single()
        assertEquals(a, e.photo)
        assertEquals(listOf(a, b), t.foodPhotoStore.forEntry(id).map { it.fileName })
    }

    @Test fun removingAllPhotosClearsPrimary() = runTest {
        val a = touch()
        t.food.add(t.mon, "solo", listOf(a))
        val id = t.food.observe(t.mon).first().single().id
        t.food.edit(id, "solo", "12:00", emptyList())
        assertNull(t.food.observe(t.mon).first().single().photo)
        assertTrue(t.foodPhotoStore.forEntry(id).isEmpty())
        assertFalse(File(t.photoDir, a).exists())
    }
}
