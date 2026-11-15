package app.diurna.debug

import app.diurna.ai.*
import app.diurna.domain.FoodIngredient
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

suspend fun <T> DebugLog.operation(name: String, screen: Screen = Screen.BACKGROUND, block: suspend () -> T): T =
    actions.operation(name, screen, block = block)

/** STARTED, then OK / FAILED / CANCELLED with latency. [details] (allowlisted keys) go on STARTED. */
suspend fun <T> Store.operation(name: String, screen: Screen = Screen.BACKGROUND,
                                details: Map<String, String> = emptyMap(), block: suspend () -> T): T {
    val id = UUID.randomUUID().toString(); val start = System.nanoTime()
    dispatch(Action.Event(screen, name, id, details = details))
    try {
        val value = block()
        dispatch(Action.Event(screen, name, id, Action.Result.OK, (System.nanoTime() - start) / 1_000_000))
        return value
    } catch (e: Exception) {
        dispatch(Action.Event(screen, name, id, if (e is CancellationException) Action.Result.CANCELLED else Action.Result.FAILED,
            (System.nanoTime() - start) / 1_000_000))
        throw e
    }
}

/** Logs each estimate as ai.food; STARTED carries how many photos went with it (a count, never the photos). */
class LoggedFoodEstimator(private val delegate: FoodEstimator, private val actions: Store) : FoodEstimator {
    constructor(delegate: FoodEstimator, debug: DebugLog) : this(delegate, debug.actions)
    override val model get() = delegate.model
    override suspend fun estimate(text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean): FoodEstimate =
        actions.operation("ai.food", details = mapOf("photos" to (if (photo == null) 0 else 1).toString())) {
            delegate.estimate(text, photo, detailed, ingredients)
        }
    override suspend fun estimatePhotos(text: String, photos: List<ByteArray>, detailed: Boolean, ingredients: Boolean): FoodEstimate =
        actions.operation("ai.food", details = mapOf("photos" to photos.size.toString())) {
            delegate.estimatePhotos(text, photos, detailed, ingredients)
        }
    override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext): List<FoodIngredient> =
        actions.operation("ai.barcode_ingredients") { delegate.guessBarcodeIngredients(ctx) }
}
class LoggedCardioReader(private val delegate: CardioReader, private val debug: DebugLog) : CardioReader {
    override val model get() = delegate.model
    override suspend fun read(photo: ByteArray): CardioReading = debug.operation("ai.cardio") { delegate.read(photo) }
}
class LoggedLabelReader(private val delegate: LabelReader, private val debug: DebugLog) : LabelReader {
    override val model get() = delegate.model
    override suspend fun read(photo: ByteArray): NutritionLabelReading = debug.operation("ai.label") { delegate.read(photo) }
}
