package app.diurna.ui

import app.diurna.ai.CardioReader
import app.diurna.ai.CardioReading
import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.FoodEstimate
import app.diurna.ai.FoodEstimator
import app.diurna.ai.LabelReader
import app.diurna.ai.NoApiKey
import app.diurna.ai.NutritionLabelReading
import app.diurna.ai.VisionSupport
import app.diurna.data.FoodPhotos
import app.diurna.domain.CardioPiece
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Photo → AI → dialog wiring: success, missing/corrupt photo, no key, parse/AI failure, network,
 * and vision-off. Fakes only — no real network.
 */
class PhotoAiReadsTest {
    private val root = Files.createTempDirectory("photo-ai").toFile()
    private val photos = FoodPhotos(File(root, "food-photos").apply { mkdirs() })

    @After fun tearDown() { root.deleteRecursively() }

    private fun store(bytes: ByteArray = byteArrayOf(1, 2, 3)): String {
        val name = photos.newName()
        File(photos.dir, name).writeBytes(bytes)
        return name
    }

    private val piece = CardioPiece(distanceM = 3260, durationS = 907.0, kcal = 120)
    private val reading = CardioReading(piece, "15 min Metronome")
    private val label = NutritionLabelReading(
        productName = "Cheerios", serving = "1 cup", kcal = 140, proteinG = 5, carbsG = 29, fatG = 2,
    )
    private val estimate = FoodEstimate(kcalLow = 400, kcalHigh = 550, proteinG = 20, carbsG = 40, fatG = 15, note = "burrito")

    private class FakeCardio(var answer: suspend (ByteArray) -> CardioReading) : CardioReader {
        override val model = "fake"
        val seen = mutableListOf<ByteArray>()
        override suspend fun read(photo: ByteArray): CardioReading {
            seen += photo
            return answer(photo)
        }
    }

    private class FakeLabel(var answer: suspend (ByteArray) -> NutritionLabelReading) : LabelReader {
        override val model = "fake"
        val seen = mutableListOf<ByteArray>()
        override suspend fun read(photo: ByteArray): NutritionLabelReading {
            seen += photo
            return answer(photo)
        }
    }

    private class FakeFood(var answer: suspend (String, ByteArray?) -> FoodEstimate) : FoodEstimator {
        override val model = "fake"
        val texts = mutableListOf<String>()
        val photosSeen = mutableListOf<ByteArray?>()
        override suspend fun estimate(
            text: String,
            photo: ByteArray?,
            detailed: Boolean,
            ingredients: Boolean,
        ): FoodEstimate {
            texts += text
            photosSeen += photo
            return answer(text, photo)
        }
    }

    @Test fun cardioSuccessDeletesTempAndOpensConfirmDialog() = runTest {
        val name = store(byteArrayOf(9, 8, 7))
        val fake = FakeCardio { reading }
        val r = PhotoAiReads.readCardioPhoto(name, photos, fake, supportsImages = true)
        assertEquals(CardioPhotoResult.Read(reading), r)
        assertTrue(fake.seen.single().contentEquals(byteArrayOf(9, 8, 7)))
        assertFalse(photos.exists(name))
        val edit = PhotoAiReads.cardioEditFrom(r, CardioPiece())
        assertEquals(piece, edit.piece)
        assertEquals("15 min Metronome", edit.note)
        assertTrue(edit.fromPhoto)
        assertEquals(null, edit.problem)
    }

    @Test fun cardioMissingPhotoIsAProblemAndStillDeletes() = runTest {
        val name = photos.newName() // never written
        val fake = FakeCardio { error("should not be called") }
        val r = PhotoAiReads.readCardioPhoto(name, photos, fake, supportsImages = true)
        assertEquals(CardioPhotoResult.Problem(PhotoAiReads.COULDNT_READ_PHOTO), r)
        assertTrue(fake.seen.isEmpty())
        val edit = PhotoAiReads.cardioEditFrom(r, piece)
        assertEquals(piece, edit.piece)
        assertEquals(PhotoAiReads.COULDNT_READ_PHOTO, edit.problem)
        assertTrue(edit.fromPhoto)
    }

    @Test fun cardioEmptyFileIsTreatedAsUnreadable() = runTest {
        val name = store(ByteArray(0))
        val fake = FakeCardio { error("should not be called") }
        assertEquals(
            CardioPhotoResult.Problem(PhotoAiReads.COULDNT_READ_PHOTO),
            PhotoAiReads.readCardioPhoto(name, photos, fake, supportsImages = true),
        )
        assertTrue(fake.seen.isEmpty())
        assertFalse(photos.exists(name))
    }

    @Test fun cardioVisionOffRefusesWithoutCallingAi() = runTest {
        val name = store()
        val fake = FakeCardio { error("should not be called") }
        val r = PhotoAiReads.readCardioPhoto(name, photos, fake, supportsImages = false)
        assertEquals(CardioPhotoResult.Problem(VisionSupport.PHOTO_FEATURES_DISABLED), r)
        assertTrue(fake.seen.isEmpty())
        assertFalse(photos.exists(name))
    }

    @Test fun cardioNoApiKey() = runTest {
        val name = store()
        val fake = FakeCardio { throw NoApiKey("Add a key") }
        assertEquals(
            CardioPhotoResult.Problem("Add a key"),
            PhotoAiReads.readCardioPhoto(name, photos, fake, supportsImages = true),
        )
        assertFalse(photos.exists(name))
    }

    @Test fun cardioParseFailure() = runTest {
        val name = store()
        val fake = FakeCardio { throw EstimateFailed("No distance or time found") }
        assertEquals(
            CardioPhotoResult.Problem("No distance or time found"),
            PhotoAiReads.readCardioPhoto(name, photos, fake, supportsImages = true),
        )
    }

    @Test fun cardioNetworkFailures() = runTest {
        val name1 = store()
        assertEquals(
            CardioPhotoResult.Problem(PhotoAiReads.NETWORK_PROBLEM),
            PhotoAiReads.readCardioPhoto(name1, photos, FakeCardio { throw EstimateRetryable(IOException("offline")) }, true),
        )
        val name2 = store()
        assertEquals(
            CardioPhotoResult.Problem(PhotoAiReads.NETWORK_PROBLEM),
            PhotoAiReads.readCardioPhoto(name2, photos, FakeCardio { throw IOException("offline") }, true),
        )
    }

    @Test fun cardioUnexpectedErrorNamesTheType() = runTest {
        val name = store()
        val r = PhotoAiReads.readCardioPhoto(
            name, photos, FakeCardio { throw IllegalStateException("boom") }, true,
        )
        assertEquals(CardioPhotoResult.Problem("Couldn't read the screen (IllegalStateException)"), r)
    }

    @Test fun labelSuccessAndErrors() = runTest {
        val okName = store(byteArrayOf(4, 5))
        val ok = FakeLabel { label }
        assertEquals(LabelPhotoResult.Read(label), PhotoAiReads.readLabelPhoto(okName, photos, ok))
        assertTrue(ok.seen.single().contentEquals(byteArrayOf(4, 5)))
        assertFalse(photos.exists(okName))

        val missing = photos.newName()
        assertEquals(
            LabelPhotoResult.Problem(PhotoAiReads.COULDNT_READ_PHOTO),
            PhotoAiReads.readLabelPhoto(missing, photos, FakeLabel { error("no") }),
        )
        val noKey = store()
        assertEquals(
            LabelPhotoResult.Problem("need key"),
            PhotoAiReads.readLabelPhoto(noKey, photos, FakeLabel { throw NoApiKey("need key") }),
        )
        val bad = store()
        assertEquals(
            LabelPhotoResult.Problem("No calories found"),
            PhotoAiReads.readLabelPhoto(bad, photos, FakeLabel { throw EstimateFailed("No calories found") }),
        )
        val net = store()
        assertEquals(
            LabelPhotoResult.Problem(PhotoAiReads.NETWORK_PROBLEM),
            PhotoAiReads.readLabelPhoto(net, photos, FakeLabel { throw IOException("x") }),
        )
    }

    @Test fun restaurantEstimateBuildsPromptAndWiresErrors() = runTest {
        val name = store(byteArrayOf(1))
        val fake = FakeFood { _, _ -> estimate }
        val r = PhotoAiReads.estimateRestaurantPhoto(
            name, dishHint = "burrito bowl", restaurantHint = "Chipotle", detailed = false,
            photos = photos, estimator = fake,
        )
        assertEquals(RestaurantEstimateResult.Ok(estimate), r)
        assertEquals("Restaurant meal from Chipotle: burrito bowl", fake.texts.single())
        assertTrue(fake.photosSeen.single()!!.contentEquals(byteArrayOf(1)))
        assertFalse(photos.exists(name))

        assertEquals(
            "Restaurant meal (see photo of menu or nutrition page)",
            PhotoAiReads.restaurantPrompt("", ""),
        )
        assertEquals(
            "Restaurant meal from Place (see photo of menu or nutrition page)",
            PhotoAiReads.restaurantPrompt("", " Place "),
        )

        val missing = photos.newName()
        assertEquals(
            RestaurantEstimateResult.Problem(PhotoAiReads.COULDNT_READ_PHOTO),
            PhotoAiReads.estimateRestaurantPhoto(missing, "", "", false, photos, FakeFood { _, _ -> error("no") }),
        )
        val noKey = store()
        assertEquals(
            RestaurantEstimateResult.Problem("Add a key"),
            PhotoAiReads.estimateRestaurantPhoto(
                noKey, "x", "", false, photos, FakeFood { _, _ -> throw NoApiKey("Add a key") },
            ),
        )
        val fail = store()
        assertEquals(
            RestaurantEstimateResult.Problem("Couldn't estimate from that photo"),
            PhotoAiReads.estimateRestaurantPhoto(
                fail, "x", "", false, photos,
                FakeFood { _, _ -> throw EstimateFailed("Couldn't estimate from that photo") },
            ),
        )
        val net = store()
        assertEquals(
            RestaurantEstimateResult.Problem(PhotoAiReads.NETWORK_PROBLEM),
            PhotoAiReads.estimateRestaurantPhoto(
                net, "x", "", false, photos, FakeFood { _, _ -> throw EstimateRetryable() },
            ),
        )
    }
}
