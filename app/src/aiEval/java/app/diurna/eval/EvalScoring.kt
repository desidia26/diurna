package app.diurna.eval

import app.diurna.ai.CardioReading
import app.diurna.ai.FoodEstimate
import app.diurna.ai.NutritionLabelReading
import kotlin.math.abs
import kotlin.math.roundToInt

data class CheckResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
    /** When false, a miss is reported but does not fail the case (Ollama parse-health smoke). */
    val required: Boolean = true,
)

data class CaseScore(
    val caseId: String,
    val provider: String,
    val prompt: String,
    val passed: Boolean,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val error: String? = null,
    val checks: List<CheckResult> = emptyList(),
    /**
     * True when the case produced no usable structured model output (malformed/unparseable JSON,
     * missing required fields, parser exception, empty response, or HTTP/transport error with no
     * parseable body). Any parse failure fails the eval run regardless of pass-rate threshold.
     * Accuracy/scoring misses are not parse failures.
     */
    val parseFailure: Boolean = false,
) {
    val failedChecks: List<CheckResult> get() = checks.filter { !it.passed && it.required }
    val infoMisses: List<CheckResult> get() = checks.filter { !it.passed && !it.required }
}

object EvalScoring {
    /** Atwater factors: used only as a consistency sanity check, not ground truth. */
    fun macroKcal(proteinG: Int, carbsG: Int, fatG: Int): Int =
        (4 * proteinG) + (4 * carbsG) + (9 * fatG)

    fun scoreFood(
        case: EvalCase,
        estimate: FoodEstimate,
        mode: ScoringMode = ScoringMode.ACCURACY,
    ): CaseScore {
        val bandsRequired = mode == ScoringMode.ACCURACY
        // Semantic rejection (not-food) is accuracy-like; keep it informational for weak smoke models.
        val outcomeRequired = mode == ScoringMode.ACCURACY
        val e = case.expect
        val checks = mutableListOf<CheckResult>()
        val noteLower = estimate.note.lowercase()
        val zeros = estimate.kcalHigh == 0 && estimate.proteinG == 0 && estimate.carbsG == 0 && estimate.fatG == 0
        val looksNotFood = "not food" in noteLower || "not a food" in noteLower || "isn't food" in noteLower

        when (e.outcome) {
            "not_food" -> {
                checks += CheckResult("not_food_note", looksNotFood, "note=${estimate.note}", required = outcomeRequired)
                checks += CheckResult("near_zero", zeros || estimate.kcalHigh <= 20, "kcalHigh=${estimate.kcalHigh}", required = outcomeRequired)
                // Always require a successful structured parse for parse-health.
                checks += CheckResult("parsed", true, "FoodEstimate")
                checks += CheckResult("range_ordered", estimate.kcalLow <= estimate.kcalHigh, "${estimate.kcalLow}<=${estimate.kcalHigh}")
                checks += CheckResult(
                    "non_negative",
                    listOf(estimate.kcalLow, estimate.kcalHigh, estimate.proteinG, estimate.carbsG, estimate.fatG).all { it >= 0 },
                    "ok",
                )
            }
            "not_food_or_zeros" -> {
                checks += CheckResult(
                    "not_food_or_zeros",
                    looksNotFood || zeros || estimate.kcalHigh == 0,
                    "note=${estimate.note} zeros=$zeros kcal=${estimate.kcalLow}-${estimate.kcalHigh}",
                    required = outcomeRequired,
                )
                checks += CheckResult("parsed", true, "FoodEstimate")
                checks += CheckResult("range_ordered", estimate.kcalLow <= estimate.kcalHigh, "${estimate.kcalLow}<=${estimate.kcalHigh}")
                checks += CheckResult(
                    "non_negative",
                    listOf(estimate.kcalLow, estimate.kcalHigh, estimate.proteinG, estimate.carbsG, estimate.fatG).all { it >= 0 },
                    "ok",
                )
            }
            "estimate" -> {
                // Semantic "not food" label is accuracy-like; informational under PARSE_HEALTH (Ollama).
                checks += CheckResult(
                    "not_marked_not_food",
                    !looksNotFood,
                    "note=${estimate.note}",
                    required = bandsRequired,
                )
                e.kcalMid?.let { mid ->
                    val tol = ((e.kcalTolPct ?: 40.0) / 100.0)
                    val lo = (mid * (1 - tol)).roundToInt()
                    val hi = (mid * (1 + tol)).roundToInt()
                    val midEst = (estimate.kcalLow + estimate.kcalHigh) / 2
                    val overlap = estimate.kcalLow <= hi && estimate.kcalHigh >= lo
                    val midOk = midEst in lo..hi
                    checks += CheckResult(
                        "kcal_band",
                        overlap || midOk,
                        "got ${estimate.kcalLow}-${estimate.kcalHigh} (mid $midEst), want ~$mid ±${e.kcalTolPct}% ($lo-$hi)",
                        required = bandsRequired,
                    )
                }
                e.proteinG?.let { checks += band("protein_g", estimate.proteinG, it, bandsRequired) }
                e.carbsG?.let { checks += band("carbs_g", estimate.carbsG, it, bandsRequired) }
                e.fatG?.let { checks += band("fat_g", estimate.fatG, it, bandsRequired) }
                if (e.macrosConsistentWithKcal) {
                    val fromMacros = macroKcal(estimate.proteinG, estimate.carbsG, estimate.fatG)
                    val lo = (estimate.kcalLow * 0.6).roundToInt()
                    val hi = (estimate.kcalHigh * 1.4).roundToInt().coerceAtLeast(estimate.kcalHigh + 50)
                    checks += CheckResult(
                        "macros_vs_kcal",
                        fromMacros in lo..hi || abs(fromMacros - (estimate.kcalLow + estimate.kcalHigh) / 2) <= 150,
                        "macros≈$fromMacros kcal vs range ${estimate.kcalLow}-${estimate.kcalHigh}",
                        required = bandsRequired,
                    )
                }
                e.noteMaxLen?.let {
                    checks += CheckResult("note_len", estimate.note.length <= it, "len=${estimate.note.length} max=$it")
                }
                if (e.requireDetail) {
                    val hasDetail = listOf(
                        estimate.fiberG, estimate.solubleFiberG, estimate.insolubleFiberG,
                        estimate.sugarsG, estimate.addedSugarsG, estimate.starchG,
                        estimate.satFatG, estimate.monoFatG, estimate.polyFatG, estimate.transFatG,
                        estimate.cholesterolMg, estimate.omega3G, estimate.omega6G, estimate.omega9G,
                    ).any { it != null }
                    checks += CheckResult(
                        "has_detail",
                        hasDetail,
                        "fiber=${estimate.fiberG} sugars=${estimate.sugarsG} sat=${estimate.satFatG}",
                        // Weak smoke models often omit optional detail; informational for parse-health.
                        required = bandsRequired,
                    )
                }
                if (e.requireIngredients) {
                    val n = estimate.ingredients?.size ?: 0
                    checks += CheckResult(
                        "has_ingredients",
                        n > 0,
                        "count=$n",
                        required = bandsRequired,
                    )
                }
                checks += CheckResult("range_ordered", estimate.kcalLow <= estimate.kcalHigh, "${estimate.kcalLow}<=${estimate.kcalHigh}")
                checks += CheckResult(
                    "non_negative",
                    listOf(estimate.kcalLow, estimate.kcalHigh, estimate.proteinG, estimate.carbsG, estimate.fatG).all { it >= 0 },
                    "ok",
                )
                // Parse-health always requires a successful structured parse (we already have FoodEstimate).
                checks += CheckResult("parsed", true, "FoodEstimate")
            }
            else -> checks += CheckResult("unknown_outcome", false, e.outcome)
        }
        return CaseScore(
            case.id, "", case.prompt,
            passed = checks.filter { it.required }.all { it.passed },
            checks = checks,
        )
    }

    fun scoreCardio(
        case: EvalCase,
        reading: CardioReading,
        mode: ScoringMode = ScoringMode.ACCURACY,
    ): CaseScore {
        val bandsRequired = mode == ScoringMode.ACCURACY
        val e = case.expect
        val checks = mutableListOf<CheckResult>()
        when (e.outcome) {
            "reject" -> checks += CheckResult("should_have_rejected", false, "got a reading: ${reading.note}")
            "reading" -> {
                checks += CheckResult("parsed", true, "CardioReading")
                val p = reading.piece
                e.distanceM?.let { r ->
                    val v = p.distanceM
                    checks += CheckResult("distance_m", v != null && r.contains(v), "got $v want ${r.min}-${r.max}", required = bandsRequired)
                }
                e.durationS?.let { r ->
                    val v = p.durationS?.roundToInt()
                    checks += CheckResult("duration_s", v != null && r.contains(v), "got $v want ${r.min}-${r.max}", required = bandsRequired)
                }
                e.kcal?.let { r ->
                    val v = p.kcal
                    checks += CheckResult("kcal", v != null && r.contains(v), "got $v want ${r.min}-${r.max}", required = bandsRequired)
                }
                e.avgHr?.let { r ->
                    val v = p.avgHr
                    checks += CheckResult("avg_hr", v != null && r.contains(v), "got $v want ${r.min}-${r.max}", required = bandsRequired)
                }
            }
            else -> checks += CheckResult("unknown_outcome", false, e.outcome)
        }
        return CaseScore(
            case.id, "", case.prompt,
            passed = checks.filter { it.required }.all { it.passed },
            checks = checks,
        )
    }

    fun scoreLabel(
        case: EvalCase,
        reading: NutritionLabelReading,
        mode: ScoringMode = ScoringMode.ACCURACY,
    ): CaseScore {
        val bandsRequired = mode == ScoringMode.ACCURACY
        val e = case.expect
        val checks = mutableListOf<CheckResult>()
        when (e.outcome) {
            "reject" -> checks += CheckResult(
                "should_have_rejected",
                false,
                "got a reading: ${reading.productName} ${reading.kcal} kcal",
            )
            "reading" -> {
                checks += CheckResult("parsed", true, "NutritionLabelReading")
                checks += CheckResult("kcal_positive", reading.kcal > 0, "kcal=${reading.kcal}")
                e.kcal?.let { r ->
                    checks += CheckResult(
                        "kcal",
                        r.contains(reading.kcal),
                        "got ${reading.kcal} want ${r.min}-${r.max}",
                        required = bandsRequired,
                    )
                }
                e.proteinG?.let { checks += band("protein_g", reading.proteinG, it, bandsRequired) }
                e.carbsG?.let { checks += band("carbs_g", reading.carbsG, it, bandsRequired) }
                e.fatG?.let { checks += band("fat_g", reading.fatG, it, bandsRequired) }
                checks += CheckResult(
                    "non_negative",
                    listOf(reading.kcal, reading.proteinG, reading.carbsG, reading.fatG).all { it >= 0 },
                    "ok",
                )
            }
            else -> checks += CheckResult("unknown_outcome", false, e.outcome)
        }
        return CaseScore(
            case.id, "", case.prompt,
            passed = checks.filter { it.required }.all { it.passed },
            checks = checks,
        )
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
            // Unexpected inability to produce a FoodEstimate is a parse failure.
            parseFailure = !wantReject,
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
            parseFailure = !wantReject,
        )
    }

    fun scoreLabelFailure(case: EvalCase, error: String): CaseScore {
        val wantReject = case.expect.outcome == "reject"
        return CaseScore(
            caseId = case.id,
            provider = "",
            prompt = case.prompt,
            passed = wantReject,
            error = error,
            checks = listOf(CheckResult("failure_as_reject", wantReject, error)),
            parseFailure = !wantReject,
        )
    }

    private fun band(name: String, got: Int, range: IntRangeExpect, required: Boolean) =
        CheckResult(name, range.contains(got), "got $got want ${range.min}-${range.max}", required = required)
}
