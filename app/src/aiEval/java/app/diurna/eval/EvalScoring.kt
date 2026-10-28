package app.diurna.eval

import app.diurna.ai.CardioReading
import app.diurna.ai.FoodEstimate
import kotlin.math.abs
import kotlin.math.roundToInt

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

data class CaseScore(
    val caseId: String,
    val provider: String,
    val prompt: String,
    val passed: Boolean,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val error: String? = null,
    val checks: List<CheckResult> = emptyList(),
) {
    val failedChecks: List<CheckResult> get() = checks.filter { !it.passed }
}

object EvalScoring {
    /** Atwater factors: used only as a consistency sanity check, not ground truth. */
    fun macroKcal(proteinG: Int, carbsG: Int, fatG: Int): Int =
        (4 * proteinG) + (4 * carbsG) + (9 * fatG)

    fun scoreFood(case: EvalCase, estimate: FoodEstimate): CaseScore {
        val e = case.expect
        val checks = mutableListOf<CheckResult>()
        val noteLower = estimate.note.lowercase()
        val zeros = estimate.kcalHigh == 0 && estimate.proteinG == 0 && estimate.carbsG == 0 && estimate.fatG == 0
        val looksNotFood = "not food" in noteLower || "not a food" in noteLower || "isn't food" in noteLower

        when (e.outcome) {
            "not_food" -> {
                checks += CheckResult("not_food_note", looksNotFood, "note=${estimate.note}")
                checks += CheckResult("near_zero", zeros || estimate.kcalHigh <= 20, "kcalHigh=${estimate.kcalHigh}")
            }
            "not_food_or_zeros" -> {
                checks += CheckResult(
                    "not_food_or_zeros",
                    looksNotFood || zeros || estimate.kcalHigh == 0,
                    "note=${estimate.note} zeros=$zeros kcal=${estimate.kcalLow}-${estimate.kcalHigh}",
                )
            }
            "estimate" -> {
                checks += CheckResult("not_marked_not_food", !looksNotFood, "note=${estimate.note}")
                e.kcalMid?.let { mid ->
                    val tol = ((e.kcalTolPct ?: 40.0) / 100.0)
                    val lo = (mid * (1 - tol)).roundToInt()
                    val hi = (mid * (1 + tol)).roundToInt()
                    // Pass if the model's range overlaps our tolerance band, or midpoint falls in band.
                    val midEst = (estimate.kcalLow + estimate.kcalHigh) / 2
                    val overlap = estimate.kcalLow <= hi && estimate.kcalHigh >= lo
                    val midOk = midEst in lo..hi
                    checks += CheckResult("kcal_band", overlap || midOk, "got ${estimate.kcalLow}-${estimate.kcalHigh} (mid $midEst), want ~$mid ±${e.kcalTolPct}% ($lo-$hi)")
                }
                e.proteinG?.let { checks += band("protein_g", estimate.proteinG, it) }
                e.carbsG?.let { checks += band("carbs_g", estimate.carbsG, it) }
                e.fatG?.let { checks += band("fat_g", estimate.fatG, it) }
                if (e.macrosConsistentWithKcal) {
                    val fromMacros = macroKcal(estimate.proteinG, estimate.carbsG, estimate.fatG)
                    val lo = (estimate.kcalLow * 0.6).roundToInt()
                    val hi = (estimate.kcalHigh * 1.4).roundToInt().coerceAtLeast(estimate.kcalHigh + 50)
                    checks += CheckResult(
                        "macros_vs_kcal",
                        fromMacros in lo..hi || abs(fromMacros - (estimate.kcalLow + estimate.kcalHigh) / 2) <= 150,
                        "macros≈$fromMacros kcal vs range ${estimate.kcalLow}-${estimate.kcalHigh}",
                    )
                }
                e.noteMaxLen?.let {
                    checks += CheckResult("note_len", estimate.note.length <= it, "len=${estimate.note.length} max=$it")
                }
                checks += CheckResult("range_ordered", estimate.kcalLow <= estimate.kcalHigh, "${estimate.kcalLow}<=${estimate.kcalHigh}")
                checks += CheckResult("non_negative", listOf(estimate.kcalLow, estimate.kcalHigh, estimate.proteinG, estimate.carbsG, estimate.fatG).all { it >= 0 }, "ok")
            }
            else -> checks += CheckResult("unknown_outcome", false, e.outcome)
        }
        return CaseScore(case.id, "", case.prompt, passed = checks.all { it.passed }, checks = checks)
    }

    fun scoreCardio(case: EvalCase, reading: CardioReading): CaseScore {
        val e = case.expect
        val checks = mutableListOf<CheckResult>()
        when (e.outcome) {
            "reject" -> checks += CheckResult("should_have_rejected", false, "got a reading: ${reading.note}")
            "reading" -> {
                val p = reading.piece
                e.distanceM?.let { r ->
                    val v = p.distanceM
                    checks += CheckResult("distance_m", v != null && r.contains(v), "got $v want ${r.min}-${r.max}")
                }
                e.durationS?.let { r ->
                    val v = p.durationS?.roundToInt()
                    checks += CheckResult("duration_s", v != null && r.contains(v), "got $v want ${r.min}-${r.max}")
                }
                e.kcal?.let { r ->
                    val v = p.kcal
                    checks += CheckResult("kcal", v != null && r.contains(v), "got $v want ${r.min}-${r.max}")
                }
                e.avgHr?.let { r ->
                    val v = p.avgHr
                    checks += CheckResult("avg_hr", v != null && r.contains(v), "got $v want ${r.min}-${r.max}")
                }
            }
            else -> checks += CheckResult("unknown_outcome", false, e.outcome)
        }
        return CaseScore(case.id, "", case.prompt, passed = checks.all { it.passed }, checks = checks)
    }

    fun scoreFoodFailure(case: EvalCase, error: String): CaseScore {
        val wantReject = case.expect.outcome == "reject"
        return CaseScore(
            caseId = case.id,
            provider = "",
            prompt = case.prompt,
            passed = wantReject,
            error = error,
            checks = listOf(CheckResult("failure", wantReject, error)),
        )
    }

    fun scoreCardioFailure(case: EvalCase, error: String): CaseScore {
        val wantReject = case.expect.outcome == "reject"
        return CaseScore(
            caseId = case.id,
            provider = "",
            prompt = case.prompt,
            passed = wantReject,
            error = error,
            checks = listOf(CheckResult("failure_as_reject", wantReject, error)),
        )
    }

    private fun band(name: String, got: Int, range: IntRangeExpect) =
        CheckResult(name, range.contains(got), "got $got want ${range.min}-${range.max}")
}
