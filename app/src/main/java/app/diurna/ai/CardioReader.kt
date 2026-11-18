package app.diurna.ai

import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.math.abs
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
- tiles: every value on the summary's stats tiles or rows, left to right and top to bottom, each as "LABEL: value" exactly as printed (e.g. "TOTAL DISTANCE: 2,168 m", "TIME: 15:07"). Skip a tile whose label or value is cut off at the edge of the photo or unreadable. Don't include the title or header.
- planned: the workout's planned length exactly as its title, program name or header prints it (e.g. "30 MIN", "20 min"), or "" if none. That is the plan, not how long it took.
- time: how long the workout actually took: the elapsed-time value copied from one of your tiles as printed, seconds included (e.g. "15:07", "1:02:30", "32m 10s"), or "" if no tile shows it. Never put the planned length here: workouts often end before or after their plan, and a blank is fine because the person will type it in.
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
     * Every field required, 0, "none", "" or [] for not shown: both providers' strict structured
     * output handle plain required numbers, strings, string arrays and enums, so nothing depends on
     * nullable-type support.
     */
    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "distance" to NUM,
            "distance_unit" to oneOf("m", "km", "mi", "none"),
            // The tiles come before the time, and the plan gets its own slot, so the time is read
            // from what the screen shows rather than taken from the workout's title (see elapsed).
            "tiles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "planned" to mapOf("type" to "string"),
            "time" to mapOf("type" to "string"),
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
            "distance", "distance_unit", "tiles", "planned", "time", "pace_s", "pace_unit", "best_pace_s",
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
            durationS = elapsed(text("time"), node.get("tiles")),
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

    /**
     * The elapsed time in seconds, kept only when one of the reply's own tiles shows it. A model
     * that can't find the time tile (cut off at the photo's edge, say) tends to fill in the
     * workout's planned length from its title instead: "22 MIN" logged as 22:00 for a class that
     * took 19:26 (report r1118). That plan is never a tile, so the check leaves the time blank for
     * the person to type. A piece that ran exactly its planned length still shows it on a tile, so
     * it's kept. Target, goal and remaining tiles don't count.
     */
    internal fun elapsed(time: String, tiles: JsonNode?): Double? {
        val seconds = seconds(time) ?: return null
        val shown = tiles?.takeIf { it.isArray }?.mapNotNull { t -> t.takeIf { it.isTextual }?.asText() }.orEmpty()
            .filterNot { NOT_ELAPSED.containsMatchIn(it.substringBefore(":")) }
            .flatMap(::timesIn)
        return seconds.takeIf { s -> shown.any { abs(it - s) < 0.5 } }
    }

    private val NOT_ELAPSED = Regex("""(?i)target|goal|plan|remain|left|estimat""")
    private val CLOCK = Regex("""(?<![\d:.])\d{1,3}(?::[0-5]\d){1,2}(?:\.\d+)?(?![\d:])""")
    private val HMS = Regex("""(?i)(?:(\d+)\s*h(?:ours?|rs?)?\s*)?(?:(\d+)\s*m(?:in(?:utes?|s)?)?\s*)?(?:(\d+(?:\.\d+)?)\s*s(?:ec(?:onds?|s)?)?)?""")
    private val UNIT_TIME = Regex("""(?i)\d\s*(h|hrs?|hours?|min|mins|minutes?|s|secs?|seconds?)\b|\d\s*m\s*\d+\s*s\b""")

    /** "19:26", "1:02:30", "15:07.4", "32m 10s", "1h 5m", "45 min" in seconds; null for anything else. */
    internal fun seconds(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        Cardio.parseClock(t)?.let { return it }
        val (h, min, sec) = (HMS.matchEntire(t) ?: return null).destructured
        if (h.isEmpty() && min.isEmpty() && sec.isEmpty()) return null
        return (h.toDoubleOrNull() ?: 0.0) * 3600 + (min.toDoubleOrNull() ?: 0.0) * 60 + (sec.toDoubleOrNull() ?: 0.0)
    }

    /** Every time a tile's value shows: clocks, or a value with h/m/s units ("4,289 m" is a distance, not minutes). */
    private fun timesIn(tile: String): List<Double> {
        val value = tile.substringAfter(": ", tile).trim()
        val clocks = CLOCK.findAll(value).mapNotNull { Cardio.parseClock(it.value) }.toList()
        if (clocks.isNotEmpty() || !UNIT_TIME.containsMatchIn(value)) return clocks
        return listOfNotNull(seconds(value))
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
