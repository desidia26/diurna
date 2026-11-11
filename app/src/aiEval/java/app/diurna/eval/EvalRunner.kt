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
    private val detections by lazy { EvalDetections(File(imagesRoot, "detections.json")) }

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
        /** Package photos: "twopass" (identity, lookups, label read with records; the app's default) or "closeup" (one read per crop). */
        val pipeline: String = "twopass",
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
                val reason = score.skipReason ?: score.error
                if (reason != null && (
                        "not reachable" in reason || "HTTP 404" in reason ||
                            "HTTP 401" in reason || "HTTP 403" in reason
                        ) && (score.skipped || score.parseFailure)
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
                "barcode_ingredients" -> {
                    val inp = case.input
                    val grams = inp.servingGrams ?: 0.0
                    if (grams <= 0) {
                        return CaseScore(case.id, pid.id, case.prompt, passed = false, error = "servingGrams required")
                    }
                    val ctx = app.diurna.ai.BarcodeIngredientsContext(
                        productName = inp.productName,
                        brand = inp.brand,
                        servingGrams = grams,
                        servingSize = inp.servingSize,
                        ingredientsText = inp.ingredientsText,
                        kcal = inp.kcal,
                        proteinG = inp.proteinG,
                        carbsG = inp.carbsG,
                        fatG = inp.fatG,
                    )
                    val list = clients.food.guessBarcodeIngredients(ctx)
                    EvalScoring.scoreBarcodeIngredients(case, list, clients.scoringMode).copy(provider = pid.id)
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
                "package" -> {
                    if (pid == EvalProviderId.OLLAMA && config.ollamaTextOnly) {
                        return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "ollama smoke skips package/vision by default",
                        )
                    }
                    val reader = clients.pkg
                        ?: return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "no package client",
                        )
                    if (photo == null) {
                        return CaseScore(case.id, pid.id, case.prompt, passed = false, error = "package case needs image")
                    }
                    // As the phone reads it: each package the finder sees is read from its own close-up
                    // crop of the full-resolution photo, or the photo is read whole (scaled to MAX_EDGE).
                    val rel = case.input.image!!
                    if (!detections.has(rel)) return CaseScore(case.id, pid.id, case.prompt, passed = false, error = "no recorded detections for $rel in ai-evals/detections.json")
                    val started = System.nanoTime()
                    val appReader = reader.reader
                    val twoPass = config.pipeline == "twopass" && appReader != null
                    var passes = ""
                    val o = if (twoPass) {
                        // No live network: the lookups answer from ai-evals/lookups.json. The phone's barcode decoder
                        // (ML Kit) can't run here, so only digits pass 1 reads off the package are looked up by code.
                        val lookups = EvalLookups(File(imagesRoot, "lookups.json"))
                        val t = app.diurna.ai.PackageTwoPass.read(detections.source(rel, photo), EvalImages.fit(photo), appReader!!, lookups)
                        passes = " pass1_ms=${t.identifyMs} lookup_ms=${t.lookupMs} pass2_ms=${t.readMs} lookups=[${lookups.asked.joinToString(",")}]" +
                            " guessed=${t.result.reading.products.sumOf { it.guessed.size }} conflicts=${t.result.reading.products.sumOf { it.conflicts.size }}" +
                            " conflict_list=[" + t.result.reading.products.flatMap { p -> p.conflicts.map { c -> "${p.productName.take(14)}:${c.key} ${c.label}/${c.known} ${c.source}" } }.joinToString(",") + "]" +
                            (if (t.singlePassFallback) " single_pass_fallback" else "")
                        t.result
                    } else app.diurna.ai.PackageCrops.read(detections.source(rel, photo), EvalImages.fit(photo), reader = { reader.read(it) }, closeUp = { reader.readCloseUp(it) })
                    val ms = (System.nanoTime() - started) / 1_000_000
                    val score = EvalScoring.scorePackagePhoto(case, o.reading, clients.scoringMode)
                    val detail = (if (twoPass) "twopass " else "closeup ") + (o.fallback?.let { "whole photo (${it.name.lowercase()})" } ?: "crops read=${o.read}/${o.crops.size}") +
                        " calls=${o.calls} ms=$ms$passes products=${o.reading.products.size}: " +
                        o.reading.products.joinToString("; ") { p -> "${(p.brand + " " + p.productName).trim().take(28)}@${p.centerX?.let { "%.2f".format(it) }} kcal=${p.perServing["kcal"]}" }
                    score.copy(provider = pid.id, checks = score.checks + CheckResult("crops", true, detail, required = false))
                }
                "package_refine" -> {
                    if (pid == EvalProviderId.OLLAMA && config.ollamaTextOnly && photo != null) {
                        return CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = "ollama smoke skips vision")
                    }
                    val reader = clients.pkg
                        ?: return CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = "no package client")
                    val prevFile = case.input.previous?.let { File(imagesRoot, it) }
                    if (prevFile == null || !prevFile.isFile || case.input.answer.isNullOrBlank()) {
                        return CaseScore(case.id, pid.id, case.prompt, passed = false, error = "package_refine needs previous + answer")
                    }
                    val previous = app.diurna.ai.PackagePrompt.parse(prevFile.readText())
                    EvalScoring.scorePackagePhoto(case, reader.refine(photo, previous, case.input.answer), clients.scoringMode).copy(provider = pid.id)
                }
                "density" -> {
                    val dens = clients.density
                        ?: return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "no density client",
                        )
                    val kindKey = case.input.unitKind ?: "volume"
                    val kind = app.diurna.domain.DensityUnitKind.ofKey(kindKey)
                        ?: return CaseScore(
                            case.id, pid.id, case.prompt, passed = false,
                            error = "bad unitKind $kindKey",
                        )
                    val name = case.input.text.orEmpty()
                    val lookup = dens.estimate(name, kind)
                    EvalScoring.scoreDensity(case, lookup, clients.scoringMode).copy(provider = pid.id)
                }
                "ingredient_nutrition" -> {
                    val nut = clients.ingredientNutrition
                        ?: return CaseScore(
                            case.id, pid.id, case.prompt, passed = true, skipped = true,
                            skipReason = "no ingredient nutrition client",
                        )
                    val name = case.input.text.orEmpty()
                    val profile = nut.estimate(name)
                    EvalScoring.scoreIngredientNutrition(case, profile, clients.scoringMode)
                        .copy(provider = pid.id)
                }
                else -> CaseScore(case.id, pid.id, case.prompt, passed = false, error = "unknown prompt ${case.prompt}")
            }
        } catch (e: NoApiKey) {
            CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = e.message)
        } catch (e: MissingKeySkip) {
            // Missing key mid-case (should be rare; resolve usually catches this) — skip, not fail.
            CaseScore(case.id, pid.id, case.prompt, passed = true, skipped = true, skipReason = e.message)
        } catch (e: ProviderSkip) {
            // Mid-case transport/HTTP/unavailable after the provider was resolved: no parseable
            // output for this case → parse failure (not a skip). Initial probe UnavailableSkip
            // is thrown from resolve() and handled above as a full-provider skip.
            CaseScore(
                case.id, pid.id, case.prompt,
                passed = false,
                error = e.message,
                parseFailure = true,
                checks = listOf(CheckResult("parse", false, e.message ?: "provider skip")),
            )
        } catch (e: EstimateRetryable) {
            // HTTP/transport trouble with no parseable body — hard parse failure.
            CaseScore(
                case.id, pid.id, case.prompt,
                passed = false,
                error = "retryable: ${e.cause?.message ?: e.message}",
                parseFailure = true,
                checks = listOf(CheckResult("parse", false, e.cause?.message ?: e.message ?: "EstimateRetryable")),
            )
        } catch (e: EstimateFailed) {
            when (case.prompt) {
                "food" -> EvalScoring.scoreFoodFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                "cardio" -> EvalScoring.scoreCardioFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                "barcode_ingredients" -> CaseScore(
                    case.id, pid.id, case.prompt, passed = false, error = e.message ?: "EstimateFailed",
                )
                "label" -> EvalScoring.scoreLabelFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                "package", "package_refine" -> EvalScoring.scorePackageFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                "density" -> EvalScoring.scoreDensityFailure(case, e.message ?: "EstimateFailed").copy(provider = pid.id)
                "ingredient_nutrition" -> EvalScoring.scoreIngredientNutritionFailure(
                    case, e.message ?: "EstimateFailed",
                ).copy(provider = pid.id)
                else -> CaseScore(
                    case.id, pid.id, case.prompt,
                    passed = false, error = e.message, parseFailure = true,
                )
            }
        } catch (e: Exception) {
            CaseScore(
                case.id, pid.id, case.prompt,
                passed = false,
                error = "${e.javaClass.simpleName}: ${e.message}",
                parseFailure = true,
                checks = listOf(CheckResult("parse", false, "${e.javaClass.simpleName}: ${e.message}")),
            )
        }
    }
}
