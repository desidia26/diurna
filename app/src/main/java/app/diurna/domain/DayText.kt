package app.diurna.domain

import app.diurna.domain.AlarmRules.Morning
import java.util.Locale

/**
 * One-line summaries of a day, shared by the Day screen's folded cards and the lock-screen
 * summary so the two always say the same thing.
 */
object DayText {
    fun morning(result: AlarmRules.MorningResult, alarm: String, scannedAt: String?): String = when (result.status) {
        Morning.ON_TIME -> "Up at $scannedAt · on time"
        Morning.LATE -> "Up at $scannedAt · ${result.minutesLate} min late"
        Morning.OFF -> "No alarm today"
        Morning.WAITING -> "Alarm $alarm · not scanned yet"
        Morning.MISSED -> if (scannedAt == null) "Missed the $alarm alarm" else "Up at $scannedAt"
        Morning.UPCOMING, Morning.PLANNED -> "Alarm at $alarm"
    }

    /** [done] counts exercises with at least one set filled in. */
    fun workout(name: String, done: Int, total: Int): String = "$name · $done of $total exercises"

    /** The name a blank workout gets before anything says otherwise. */
    const val BLANK_WORKOUT = "Workout"

    /** One exercise of today's workout, for the lock-screen line. */
    data class ExerciseLine(
        val name: String,
        val cardio: Boolean,
        val done: Boolean,
        val distanceM: Int? = null,
        val durationS: Double? = null,
        val splitS: Double? = null,
        val kcal: Int? = null,
        val style: Cardio.Style = Cardio.Style.TIME,
    )

    /**
     * A blank workout that's just one cardio exercise goes by that exercise ("Treadmill"), without a
     * parenthesised note like "(incline)"; anything else keeps its own name.
     */
    fun workoutTitle(name: String, exercises: Int, cardioName: String?, cardioCount: Int): String =
        if (name == BLANK_WORKOUT && exercises == 1 && cardioCount == 1 && cardioName != null) shortName(cardioName) else name

    fun shortName(exercise: String): String =
        exercise.replace(Regex("""\s*\([^)]*\)\s*$"""), "").ifBlank { exercise }

    /**
     * The lock screen's workout line. Cardio alone reads as the piece in its exercise's style:
     * "Rowing machine · 3,260 m · 2:10/500m", "Run · 3.1 mi · 8:23/mi", "Elliptical · 30 min · 250 kcal".
     * With lifting (or several cardio exercises) it's the usual exercise count plus the cardio done,
     * "Push A · 3 of 4 exercises · 3.1 mi cardio". With no cardio it's exactly the plain count.
     */
    fun workout(name: String, exercises: List<ExerciseLine>): String {
        val cardio = exercises.filter { it.cardio }
        val title = workoutTitle(name, exercises.size, cardio.firstOrNull()?.name, cardio.size)
        if (exercises.size == 1 && cardio.size == 1) {
            val c = cardio.single()
            if (!c.done) return "$title · not logged yet"
            val parts = Cardio.headline(c.style, c.distanceM, c.durationS, c.splitS, c.kcal)
            return if (parts.isEmpty()) title else "$title · $parts"
        }
        val line = workout(title, exercises.count { it.done }, exercises.size)
        return cardioTotal(cardio.filter { it.done })?.let { "$line · $it cardio" } ?: line
    }

    /**
     * "3,260 m", "3.1 mi" (in the first distance exercise's units), or the minutes when nothing had
     * a distance; null with no cardio done.
     */
    fun cardioTotal(done: List<ExerciseLine>): String? {
        val withDistance = done.filter { it.style.hasDistance && (it.distanceM ?: 0) > 0 }
        if (withDistance.isNotEmpty()) return Cardio.distanceText(withDistance.sumOf { it.distanceM ?: 0 }, withDistance.first().style)
        val seconds = done.sumOf { it.durationS ?: 0.0 }
        return if (seconds > 0) Cardio.durationText(seconds) else null
    }

    fun plannedWorkout(name: String?): String = if (name != null) "$name planned" else "Rest day"

    /**
     * "1,640 / 2,260 kcal · P 120/180 · C 150/243 · F 45/63", or without targets
     * "~1,640 kcal · P 120 · C 150 · F 45". Null until something has been estimated.
     */
    fun nutrition(t: NutritionTotals, targets: Targets?): String? {
        if (t.estimated == 0) return null
        val line = if (targets != null) {
            "${n(t.kcal)} / ${n(targets.kcal)} kcal · P ${t.proteinG}/${targets.proteinG} · C ${t.carbsG}/${targets.carbsG} · F ${t.fatG}/${targets.fatG}"
        } else {
            "~${n(t.kcal)} kcal · P ${t.proteinG} · C ${t.carbsG} · F ${t.fatG}"
        }
        return if (t.pending > 0) "$line · ${t.pending} estimating" else line
    }

    private fun n(x: Int) = String.format(Locale.US, "%,d", x)

    /** "6,240 / 10,000 steps"; null when there's no count. */
    fun steps(steps: Long?, goal: Int): String? =
        steps?.let { "${String.format(Locale.US, "%,d", it)} / ${n(goal)} steps" }

    /** [times] are "HH:mm" entry times. */
    fun food(times: List<String>): String = when (times.size) {
        0 -> "No food logged yet"
        1 -> "1 food entry · ${times.single()}"
        else -> "${times.size} food entries · last ${times.max()}"
    }
}
