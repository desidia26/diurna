package app.diurna.debug

import app.diurna.ai.*
import app.diurna.domain.FoodIngredient
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

suspend fun <T> DebugLog.operation(name: String, screen: Screen = Screen.BACKGROUND, block: suspend () -> T): T {
    val id = UUID.randomUUID().toString(); val start = System.nanoTime()
    actions.dispatch(Action.Event(screen, name, id))
    try {
        val value = block()
        actions.dispatch(Action.Event(screen, name, id, Action.Result.OK, (System.nanoTime() - start) / 1_000_000))
        return value
    } catch (e: Exception) {
        actions.dispatch(Action.Event(screen, name, id, if (e is CancellationException) Action.Result.CANCELLED else Action.Result.FAILED,
            (System.nanoTime() - start) / 1_000_000))
        throw e
    }
}

class LoggedFoodEstimator(private val delegate: FoodEstimator, private val debug: DebugLog) : FoodEstimator {
    override val model get() = delegate.model
    override suspend fun estimate(text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean): FoodEstimate =
        debug.operation("ai.food") { delegate.estimate(text, photo, detailed, ingredients) }
    override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext): List<FoodIngredient> =
        debug.operation("ai.barcode_ingredients") { delegate.guessBarcodeIngredients(ctx) }
}
class LoggedCardioReader(private val delegate: CardioReader, private val debug: DebugLog) : CardioReader {
    override val model get() = delegate.model
    override suspend fun read(photo: ByteArray): CardioReading = debug.operation("ai.cardio") { delegate.read(photo) }
}
class LoggedLabelReader(private val delegate: LabelReader, private val debug: DebugLog) : LabelReader {
    override val model get() = delegate.model
    override suspend fun read(photo: ByteArray): NutritionLabelReading = debug.operation("ai.label") { delegate.read(photo) }
}
