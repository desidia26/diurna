package app.diurna.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The evening check-in's metrics: what a definition and a value may be, and how they read. */
object DailyMetrics {
    enum class Kind(val key: String, val label: String) {
        SCALE("scale", "Scale"), NUMBER("number", "Number"), YES_NO("yesno", "Yes / no"), TEXT("text", "Text");

        companion object {
            // An unknown kind is shown as text: never charted, never holding up the reminder.
            fun of(key: String): Kind = entries.firstOrNull { it.key == key } ?: TEXT
        }
    }

    data class Def(
        val id: Long,
        val name: String,
        val kind: Kind,
        val scaleMax: Int = DEFAULT_STEPS,
        /** Scale only: empty, or one per step (blank where a step has no word). */
        val words: List<String> = emptyList(),
        val unit: String? = null,
        val archived: Boolean = false,
    )

    data class Value(val value: Double? = null, val text: String? = null)

    const val NAME_MAX = 40
    const val WORD_MAX = 16
    const val UNIT_MAX = 12
    const val TEXT_MAX = 2000
    const val DEFAULT_STEPS = 5
    val SCALE_STEPS = 3..10
    /** Bigger than anything a person tracks daily; stops a stray paste becoming a chart's whole y-axis. */
    const val NUMBER_LIMIT = 1_000_000.0

    // The seeded two keep the words they've had since v5, so ratings mean the same thing over months.
    const val MOOD_KEY = "mood"
    const val ENERGY_KEY = "energy"
    val moodWords = listOf("rough", "meh", "fine", "good", "great")
    val energyWords = listOf("drained", "low", "okay", "good", "high")

    fun parseWords(stored: String?): List<String> =
        stored?.split('\n')?.map { it.trim() }?.takeIf { w -> w.any { it.isNotEmpty() } } ?: emptyList()

    /** Null when no step has a word. */
    fun joinWords(words: List<String>): String? =
        words.map { cleanLine(it) }.takeIf { w -> w.any { it.isNotEmpty() } }?.joinToString("\n")

    /** Trimmed, single-spaced, on one line. */
    fun cleanLine(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /** What's wrong with a definition, in plain words; null when it can be saved. */
    fun problem(def: Def, otherNames: Collection<String>): String? {
        val name = cleanLine(def.name)
        return when {
            name.isEmpty() -> "Give it a name."
            name.length > NAME_MAX -> "Keep the name to $NAME_MAX characters."
            otherNames.any { it.equals(name, ignoreCase = true) } -> "There's already a metric called $name."
            def.kind == Kind.SCALE && def.scaleMax !in SCALE_STEPS -> "A scale has ${SCALE_STEPS.first} to ${SCALE_STEPS.last} steps."
            def.kind == Kind.SCALE && def.words.isNotEmpty() && def.words.size != def.scaleMax -> "Give each step a word, or leave them all blank."
            def.kind == Kind.SCALE && def.words.any { cleanLine(it).length > WORD_MAX } -> "Keep each word to $WORD_MAX characters."
            def.kind == Kind.NUMBER && (def.unit?.let(::cleanLine)?.length ?: 0) > UNIT_MAX -> "Keep the unit to $UNIT_MAX characters."
            else -> null
        }
    }

    /** A value that fits the metric, or null (stored as not filled in). */
    fun cleanValue(def: Def, v: Double?): Double? {
        if (v == null || !v.isFinite()) return null
        return when (def.kind) {
            Kind.SCALE -> v.roundToInt().takeIf { it in 1..def.scaleMax }?.toDouble()
            Kind.NUMBER -> v.takeIf { abs(it) <= NUMBER_LIMIT }
            Kind.YES_NO -> v.takeIf { it == 0.0 || it == 1.0 }
            Kind.TEXT -> null
        }
    }

    fun cleanText(s: String?): String? = s?.trim()?.take(TEXT_MAX)?.ifEmpty { null }

    fun filled(def: Def, v: Value?): Boolean = if (def.kind == Kind.TEXT) v?.text != null else v?.value != null

    fun word(def: Def, v: Double?): String? = v?.let { def.words.getOrNull(it.roundToInt() - 1) }?.ifEmpty { null }

    /** Up to two decimals, no trailing zeros: 7.5, 8, 0.25. */
    fun number(v: Double): String {
        val hundredths = (v * 100).roundToLong()
        if (hundredths % 100 == 0L) return (hundredths / 100).toString()
        return "%.2f".format(v).trimEnd('0')
    }

    /** "4 good", "7.5 h", "yes", or the text; null when not filled in. */
    fun display(def: Def, v: Value?): String? = when (def.kind) {
        Kind.TEXT -> v?.text
        else -> v?.value?.let { n ->
            when (def.kind) {
                Kind.SCALE -> listOfNotNull(n.roundToInt().toString(), word(def, n)).joinToString(" ")
                Kind.NUMBER -> listOfNotNull(number(n), def.unit?.ifBlank { null }).joinToString(" ")
                else -> if (n == 1.0) "yes" else "no"
            }
        }
    }

    /** Active metrics still waiting for a value. Text is optional, so it never holds the check-in open. */
    fun missing(defs: List<Def>, values: Map<Long, Value>): List<Def> =
        defs.filter { !it.archived && it.kind != Kind.TEXT && !filled(it, values[it.id]) }

    fun complete(defs: List<Def>, values: Map<Long, Value>): Boolean = missing(defs, values).isEmpty()

    /** The folded card: "Mood 4 good · Energy not rated", or "Not rated yet". */
    fun summary(defs: List<Def>, values: Map<Long, Value>): String {
        val active = defs.filter { !it.archived }
        if (active.none { filled(it, values[it.id]) }) return "Not rated yet"
        return active.mapNotNull { d ->
            val v = values[d.id]
            when {
                d.kind == Kind.TEXT -> "${d.name} written".takeIf { filled(d, v) }
                filled(d, v) -> "${d.name} ${display(d, v)}"
                else -> "${d.name} not rated"
            }
        }.joinToString(" · ")
    }

    /**
     * Per Monday-to-Sunday week touching [from]..[to], the percent of answered days that were yes;
     * null for weeks with no answers. Days outside the range don't count.
     */
    fun weeklyYesShare(values: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<Double?> {
        val byWeek = values.filterKeys { it in from..to }.entries.groupBy({ Metrics.weekOf(it.key) }, { it.value })
        return generateSequence(Metrics.weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }
            .map { w -> byWeek[w]?.let { 100.0 * it.count { v -> v == 1.0 } / it.size } }.toList()
    }
}
