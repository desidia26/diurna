package app.diurna.eval

import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import java.io.File

class EvalRunner(
    private val root: File,
    private val casesDir: File = File(root, "ai-evals/cases"),
    private val imagesRoot: File = File(root, "ai-evals"),
) {
    data class Config(
        val providers: List<EvalProviderId>,
        val setName: String = "default",
        val tag: String? = null,
        val grokModel: String? = null,
        val claudeModel: String? = null,
        val githubModel: String? = null,
        /** When true, github-models skips cardio (vision) cases to save quota. */
        val githubFoodOnly: Boolean = true,
    )

    suspend fun run(config: Config): EvalReport {
        val set = EvalCases.filter(EvalCases.load(casesDir, config.setName), config.tag)
        val scores = mutableListOf<CaseScore>()
        val models = mutableMapOf<String, String?>()

        for (pid in config.providers) {
            val clients = try {
                EvalProviders.resolve(pid, config.grokModel, config.claudeModel, config.githubModel)
            } catch (e: ProviderSkip) {
                models[pid.id] = null
                set.cases.forEach { c ->
                    scores += CaseScore(
                        caseId = c.id, provider = pid.id, prompt = c.prompt,
                        passed = true, skipped = true, skipReason = e.message,
                    )
                }
                continue
            }
            models[pid.id] = clients.food.model
            var providerDead: String? = null
            for (case in set.cases) {
                if (providerDead != null) {
                    scores += CaseScore(
                        caseId = case.id, provider = pid.id, prompt = case.prompt,
                        passed = true, skipped = true, skipReason = providerDead,
                    )
                    continue
                }
                val score = runCase(pid, clients, case, config)
                scores += score
                // Don't keep hitting a retired/unavailable endpoint (and don't burn the rate limiter).
                val reason = score.skipReason
                if (score.skipped && reason != null && (
                        "retired" in reason || "non-JSON" in reason || "HTTP 401" in reason ||
                            "HTTP 403" in reason || "HTTP 404" in reason || "Models unavailable" in reason
                        )
                ) {
                    providerDead = reason
                }
            }
        }
        return EvalReportWriter.aggregate(set.passThreshold, scores, models)
    }

    private suspend fun runCase(
        pid: EvalProviderId,
        clients: EvalClients,
        case: EvalCase,
        config: Config,
    ): CaseScore {
        val photo = case.input.image?.let { rel ->
            val f = File(imagesRoot, rel)
            if (!f.isFile) return CaseScore(
                case.id, pid.id, case.prompt, passed = false,
                error = "missing image $rel",
            )
            f.readBytes()
        }
        return try {
            when (case.prompt) {
                "food" -> {
                    val est = clients.food.estimate(case.input.text.orEmpty(), photo)
                    EvalScoring.scoreFood(case, est).copy(provider = pid.id)
                }
                "cardio" -> {
                    if (pid == EvalProviderId.GITHUB_MODELS && config.githubFoodOnly) {
                        return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "github-models smoke skips cardio/vision by default",
                        )
                    }
                    val reader = clients.cardio
                        ?: return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "no cardio client",
                        )
                    if (photo == null) {
                        return CaseScore(case.id, pid.id, case.prompt, passed = false, error = "cardio case needs image")
                    }
                    val reading = reader.read(photo)
                    EvalScoring.scoreCardio(case, reading).copy(provider = pid.id)
                }
                else -> CaseScore(case.id, pid.id, case.prompt, passed = false, error = "unknown prompt ${case.prompt}")
            }
        } catch (e: ProviderSkip) {
            CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = e.message)
        } catch (e: NoApiKey) {
            CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = e.message)
        } catch (e: EstimateRetryable) {
            CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = "retryable: ${e.cause?.message ?: e.message}")
        } catch (e: EstimateFailed) {
            when (case.prompt) {
                "food" -> EvalScoring.scoreFoodFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                "cardio" -> EvalScoring.scoreCardioFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                else -> CaseScore(case.id, pid.id, case.prompt, passed = false, error = e.message)
            }
        } catch (e: Exception) {
            CaseScore(case.id, pid.id, case.prompt, passed = false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
