package app.daybook.domain

import app.daybook.domain.AlarmRules.Morning
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
