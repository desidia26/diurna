package app.diurna.ai

import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a cardio machine's end-of-workout summary screen from a photo (treadmill, bike, rower,
 * elliptical, a watch or app summary), so a piece can be logged without typing. Whatever fields the
 * screen shows are mapped; the rest stay blank. The person always checks the numbers before saving.
 */
interface CardioReader {
    val model: String

    /** [photo]: a JPEG, already downscaled. Throws [NoApiKey], [EstimateFailed] or [EstimateRetryable]. */
    suspend fun read(photo: ByteArray): CardioReading
}

/** What the screen showed, in stored units (metres, seconds per 500 m); null where it didn't show a value. */
data class CardioReading(val piece: CardioPiece, val note: String)

object CardioPrompt {
    const val SYSTEM = """You read the summary screen of a cardio workout from a photo: a treadmill, exercise bike, rowing machine, elliptical, stair climber, or a watch or app summary (for example Peloton, Concept2, Ergatta, Life Fitness, Apple Watch).

Report the totals for the whole workout, exactly as the screen shows them, with their units:
- distance and distance_unit: total distance as a number, and "m", "km" or "mi" as shown.
- duration_s: total elapsed time in seconds (15:07 is 907, 1:02:30 is 3750).
- pace_s and pace_unit: the AVERAGE pace as time per distance, in seconds (2:10 is 130), and what it's per: "500m" (rowers' split), "km" or "mi". Don't work it out yourself.
- best_pace_s: the best/fastest pace in the same unit as pace_unit, if shown.
- speed and speed_unit: the AVERAGE speed if shown instead of or as well as pace, and "kmh" or "mph".
- watts: average power in watts.
- kcal: total calories (Cal or kcal).
- avg_hr: average heart rate in bpm.
- note: the workout's title or type if shown (e.g. "15 min Metronome", "Hill climb"), under 60 characters.

Use 0 (and unit "none") for anything the screen doesn't show or you can't read clearly; never guess a number. Ignore per-interval rows, goals, targets and records from other workouts. If the photo isn't a workout summary, return all zeros and note "not a workout screen"."""

    const val USER_TEXT = "Read this workout summary screen."

    private val INT = mapOf("type" to "integer")
    private val NUM = mapOf("type" to "number")
    private fun oneOf(vararg values: String) = mapOf("type" to "string", "enum" to values.toList())

    /**
     * Every field required, 0 or "none" for not shown: both providers' strict structured output
     * handle plain required numbers and enums, so nothing depends on nullable-type support.
     */
    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "distance" to NUM,
            "distance_unit" to oneOf("m", "km", "mi", "none"),
            "duration_s" to NUM,
            "pace_s" to NUM,
            "pace_unit" to oneOf("500m", "km", "mi", "none"),
            "best_pace_s" to NUM,
            "speed" to NUM,
            "speed_unit" to oneOf("kmh", "mph", "none"),
            "watts" to INT,
            "kcal" to INT,
            "avg_hr" to INT,
            "note" to mapOf("type" to "string"),
        ),
        "required" to listOf(
            "distance", "distance_unit", "duration_s", "pace_s", "pace_unit", "best_pace_s",
            "speed", "speed_unit", "watts", "kcal", "avg_hr", "note",
        ),
        "additionalProperties" to false,
    )

    private val mapper = ObjectMapper()

    /**
     * Units are converted to stored ones (metres; pace as seconds per 500 m, from a speed when no
     * pace was shown). Zero, unitless and out-of-range values become null ([CardioPiece.clean]).
     * A reading with neither distance nor time can't be logged, so it fails with a message instead.
     */
    fun parse(json: String): CardioReading {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject }
            ?: throw EstimateFailed("Couldn't read the screen")
        fun num(name: String): Double? = node.get(name)?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it.isFinite() && it > 0 }
        fun int(name: String): Int? = num(name)?.takeIf { it <= Int.MAX_VALUE.toDouble() }?.let { Math.round(it).toInt() }
        fun text(name: String): String = node.get(name)?.takeIf { it.isTextual }?.asText().orEmpty()
        val metresPer = when (text("distance_unit")) {
            "m" -> 1.0
            "km" -> 1000.0
            "mi" -> Cardio.MILE_M
            else -> null
        }
        val pace = when (text("pace_unit")) {
            "500m" -> Cardio.Pace.PER_500M
            "km" -> Cardio.Pace.PER_KM
            "mi" -> Cardio.Pace.PER_MILE
            else -> null
        }
        val speed = when (text("speed_unit")) {
            "kmh" -> Cardio.Pace.KMH
            "mph" -> Cardio.Pace.MPH
            else -> null
        }
        val split = pace?.let { p -> num("pace_s")?.let(p::toSplit) } ?: speed?.let { sp -> num("speed")?.let(sp::toSplit) }
        val piece = CardioPiece(
            distanceM = metresPer?.let { m -> num("distance")?.let { Math.round(it * m).toInt() } },
            durationS = num("duration_s"),
            splitS = split,
            bestSplitS = pace?.let { p -> num("best_pace_s")?.let(p::toSplit) },
            watts = int("watts"),
            kcal = int("kcal"),
            avgHr = int("avg_hr"),
        ).clean()
        val note = text("note").trim().take(80)
        if (piece.distanceM == null && piece.durationS == null) {
            throw EstimateFailed(if (note.isNotEmpty()) "No distance or time found ($note). Enter it by hand." else "No distance or time found. Enter it by hand.")
        }
        return CardioReading(piece, note)
    }
}

class ClaudeCardioReader(
    private val apiKey: () -> String?,
    // Reading printed numbers off a screen is easy for a small vision model.
    override val model: String = "claude-haiku-5-5",
    private val baseUrl: () -> String? = { null },
    private val supportsImages: () -> Boolean = { true },
) : CardioReader {
    override suspend fun read(photo: ByteArray): CardioReading = withContext(Dispatchers.IO) {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to read photos")
        val params = ClaudeRequest.params(model, CardioPrompt.USER_TEXT, photo, CardioPrompt.SYSTEM, CardioPrompt.SCHEMA)
        CardioPrompt.parse(ClaudeCall.json(apiKey, model, params, declined = "The model declined to read this photo", baseUrl = baseUrl))
    }
}

class GrokCardioReader(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String = { GrokFoodEstimator.DEFAULT_MODEL },
    private val transportProvider: () -> XaiTransport = { HttpXaiTransport() },
    private val supportsImages: () -> Boolean = { true },
) : CardioReader {
    constructor(
        apiKey: () -> String?,
        model: String = GrokFoodEstimator.DEFAULT_MODEL,
        transport: XaiTransport,
        supportsImages: () -> Boolean = { true },
    ) : this(apiKey, { model }, { transport }, supportsImages)

    override val model: String get() = modelProvider()

    override suspend fun read(photo: ByteArray): CardioReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        val key = apiKey() ?: throw NoApiKey(NO_KEY)
        val request = XaiChat.request(model, CardioPrompt.SYSTEM, CardioPrompt.USER_TEXT, photo, schemaName = "cardio_summary", schema = CardioPrompt.SCHEMA)
        return CardioPrompt.parse(XaiChat.call(transportProvider(), key, model, request))
    }

    companion object {
        const val NO_KEY = "Add an xAI (Grok) API key in Setup to read photos"
    }
}

/** Uses whichever AI provider is selected in Setup, like [SelectedFoodEstimator]. */
class SelectedCardioReader(
    private val selected: () -> AiProvider,
    private val byProvider: Map<AiProvider, CardioReader>,
) : CardioReader {
    override val model: String get() = byProvider.getValue(selected()).model
    override suspend fun read(photo: ByteArray): CardioReading = byProvider.getValue(selected()).read(photo)
}
