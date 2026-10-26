package app.diurna.data

import app.diurna.domain.PhotoSizing
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FoodPhotosTest {
    private val root = Files.createTempDirectory("photos").toFile()
    private val photos = FoodPhotos(File(root, "food-photos").apply { mkdirs() })

    @After fun tearDown() { root.deleteRecursively() }

    private fun write(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) = File(photos.dir, name).apply { writeBytes(bytes) }

    @Test fun storedPhotosReadBack() {
        val name = photos.newName()
        write(name, byteArrayOf(9, 8, 7))
        assertTrue(photos.exists(name))
        assertArrayEquals(byteArrayOf(9, 8, 7), photos.read(name))
        photos.delete(name)
        assertFalse(photos.exists(name))
        assertNull(photos.read(name))
    }

    @Test fun namesOutsideThePatternNeverTouchTheDisk() {
        // A restored or edited database is outside data: it must not reach other files.
        File(root, "secret.jpg").writeText("no")
        listOf("../secret.jpg", "/etc/passwd", "secret.jpg", "", "${photos.newName()}/../../secret.jpg").forEach {
            assertNull(it, photos.file(it))
            assertNull(it, photos.read(it))
            photos.delete(it)
        }
        assertTrue(File(root, "secret.jpg").exists())
        assertNull(photos.file(null))
    }

    @Test fun emptyOrOversizedFilesAreNotSent() {
        val empty = photos.newName().also { write(it, ByteArray(0)) }
        val huge = photos.newName().also { write(it, ByteArray(PhotoSizing.MAX_BYTES + 1)) }
        assertNull(photos.read(empty))
        assertNull(photos.read(huge))
    }

    @Test fun sweepDeletesOnlyOldUnusedPhotos() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        val used = photos.newName().also { write(it).setLastModified(now - 5 * day) }
        val orphan = photos.newName().also { write(it).setLastModified(now - 2 * day) }
        val draft = photos.newName().also { write(it).setLastModified(now - 60_000) }
        assertEquals(1, photos.sweep(setOf(used), now))
        assertTrue(photos.exists(used))
        assertFalse(photos.exists(orphan))
        assertTrue("a recent draft may still be on screen", photos.exists(draft))
    }
}
