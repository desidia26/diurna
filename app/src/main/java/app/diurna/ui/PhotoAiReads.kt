package app.diurna.ui

import app.diurna.ai.CardioReader
import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.FoodEstimator
import app.diurna.ai.LabelReader
import app.diurna.ai.NoApiKey
import app.diurna.ai.VisionSupport
import app.diurna.data.FoodPhotos
import app.diurna.domain.CardioPiece
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Photo → AI → dialog-state wiring shared by Day and Library. Pure of Android UI so JVM tests can
 * drive it with fakes; the ViewModels only pass container deps and forward the result.
 *
 * Temp imported files are always deleted: they exist only to feed one AI call (or to fail before it).
 */
object PhotoAiReads {

    suspend fun readCardioPhoto(
        name: String,
        photos: FoodPhotos,
        reader: CardioReader,
        supportsImages: Boolean,
    ): CardioPhotoResult {
        if (!supportsImages) {
            photos.delete(name)
            return CardioPhotoResult.Problem(VisionSupport.PHOTO_FEATURES_DISABLED)
        }
        val bytes = photos.read(name)
        photos.delete(name)
        if (bytes == null) return CardioPhotoResult.Problem(COULDNT_READ_PHOTO)
        return try {
            CardioPhotoResult.Read(reader.read(bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoApiKey) {
            CardioPhotoResult.Problem(e.message ?: "Add an API key in Setup to read photos")
        } catch (e: EstimateFailed) {
            CardioPhotoResult.Problem(e.message ?: "Couldn't read the screen")
        } catch (e: EstimateRetryable) {
            CardioPhotoResult.Problem(NETWORK_PROBLEM)
        } catch (e: IOException) {
            CardioPhotoResult.Problem(NETWORK_PROBLEM)
        } catch (e: Exception) {
            CardioPhotoResult.Problem("Couldn't read the screen (${e.javaClass.simpleName})")
        }
    }

    suspend fun readLabelPhoto(
        name: String,
        photos: FoodPhotos,
        reader: LabelReader,
    ): LabelPhotoResult {
        val bytes = photos.read(name)
        photos.delete(name)
        if (bytes == null) return LabelPhotoResult.Problem(COULDNT_READ_PHOTO)
        return try {
            LabelPhotoResult.Read(reader.read(bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoApiKey) {
            LabelPhotoResult.Problem(e.message ?: "Add an API key in Setup to read labels")
        } catch (e: EstimateFailed) {
            LabelPhotoResult.Problem(e.message ?: "Couldn't read the label")
        } catch (e: EstimateRetryable) {
            LabelPhotoResult.Problem(NETWORK_PROBLEM)
        } catch (e: IOException) {
            LabelPhotoResult.Problem(NETWORK_PROBLEM)
        } catch (e: Exception) {
            LabelPhotoResult.Problem("Couldn't read the label (${e.javaClass.simpleName})")
        }
    }

    suspend fun estimateRestaurantPhoto(
        name: String,
        dishHint: String,
        restaurantHint: String,
        detailed: Boolean,
        photos: FoodPhotos,
        estimator: FoodEstimator,
    ): RestaurantEstimateResult {
        val bytes = photos.read(name)
        photos.delete(name)
        if (bytes == null) return RestaurantEstimateResult.Problem(COULDNT_READ_PHOTO)
        val text = restaurantPrompt(dishHint, restaurantHint)
        return try {
            RestaurantEstimateResult.Ok(estimator.estimate(text, bytes, detailed = detailed, ingredients = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoApiKey) {
            RestaurantEstimateResult.Problem(e.message ?: "Add an API key in Setup to estimate from photos")
        } catch (e: EstimateFailed) {
            RestaurantEstimateResult.Problem(e.message ?: "Couldn't estimate from that photo")
        } catch (e: EstimateRetryable) {
            RestaurantEstimateResult.Problem(NETWORK_PROBLEM)
        } catch (e: IOException) {
            RestaurantEstimateResult.Problem(NETWORK_PROBLEM)
        } catch (e: Exception) {
            RestaurantEstimateResult.Problem("Couldn't estimate (${e.javaClass.simpleName})")
        }
    }

    /** Maps a cardio photo read onto the confirm/edit dialog state. */
    fun cardioEditFrom(result: CardioPhotoResult, fallback: CardioPiece): CardioEdit = when (result) {
        is CardioPhotoResult.Read -> CardioEdit(result.reading.piece, fromPhoto = true, note = result.reading.note)
        is CardioPhotoResult.Problem -> CardioEdit(fallback, fromPhoto = true, problem = result.message)
    }

    fun restaurantPrompt(dishHint: String, restaurantHint: String): String = buildString {
        append("Restaurant meal")
        if (restaurantHint.isNotBlank()) append(" from ").append(restaurantHint.trim())
        if (dishHint.isNotBlank()) append(": ").append(dishHint.trim())
        else append(" (see photo of menu or nutrition page)")
    }

    const val COULDNT_READ_PHOTO = "Couldn't read that photo"
    const val NETWORK_PROBLEM = "Couldn't reach the AI. Check your connection and try again."
}
