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
        val ollamaModel: String? = null,
        val ollamaBaseUrl: String? = null,
        /** When true, ollama skips cardio/vision/label cases (default for CPU smoke). */
        val ollamaTextOnly: Boolean = true,
    )

    suspend fun run(config: Config): EvalReport {
        val set = EvalCases.filter(EvalCases.load(casesDir, config.setName), config.tag)
        val scores = mutableListOf<CaseScore>()
        val models = mutableMapOf<String, String?>()

        for (pid in config.providers) {
            val clients = try {
                EvalProviders.resolve(
                    pid, config.grokModel, config.claudeModel,
                    config.ollamaModel, config.ollamaBaseUrl,
                )
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
                val reason = score.skipReason
                if (score.skipped && reason != null && (
                        "not reachable" in reason || "HTTP 404" in reason ||
                            "HTTP 401" in reason || "HTTP 403" in reason
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
                    if (pid == EvalProviderId.OLLAMA && config.ollamaTextOnly && photo != null) {
                        return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "ollama smoke skips vision cases by default",
                        )
                    }
                    val est = clients.food.estimate(
                        case.input.text.orEmpty(),
                        photo,
                        detailed = case.input.detailed || case.input.ingredients,
                        ingredients = case.input.ingredients,
                    )
                    EvalScoring.scoreFood(case, est, clients.scoringMode).copy(provider = pid.id)
                }
                "cardio" -> {
                    if (pid == EvalProviderId.OLLAMA && config.ollamaTextOnly) {
                        return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "ollama smoke skips cardio/vision by default",
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
                    EvalScoring.scoreCardio(case, reading, clients.scoringMode).copy(provider = pid.id)
                }
                "label" -> {
                    if (pid == EvalProviderId.OLLAMA && config.ollamaTextOnly) {
                        return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "ollama smoke skips label/vision by default",
                        )
                    }
                    val reader = clients.label
                        ?: return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "no label client",
                        )
                    if (photo == null) {
                        return CaseScore(case.id, pid.id, case.prompt, passed = false, error = "label case needs image")
                    }
                    val reading = reader.read(photo)
                    EvalScoring.scoreLabel(case, reading, clients.scoringMode).copy(provider = pid.id)
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
                "label" -> EvalScoring.scoreLabelFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                else -> CaseScore(case.id, pid.id, case.prompt, passed = false, error = e.message)
            }
        } catch (e: Exception) {
            CaseScore(case.id, pid.id, case.prompt, passed = false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
