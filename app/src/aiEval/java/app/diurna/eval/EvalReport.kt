package app.diurna.eval

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

data class ProviderSummary(
    val provider: String,
    val model: String?,
    val ran: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val parseFailures: Int = 0,
    val passRate: Double?,
    val skipReasons: List<String> = emptyList(),
)

data class EvalReport(
    val threshold: Double,
    val providers: List<ProviderSummary>,
    val scores: List<CaseScore>,
    val overallPassed: Boolean,
    val anyProviderRan: Boolean,
    /** True when any case had a parseFailure; forces overall fail regardless of threshold. */
    val anyParseFailure: Boolean = false,
)

object EvalReportWriter {
    private val mapper = ObjectMapper()

    fun aggregate(threshold: Double, scores: List<CaseScore>, models: Map<String, String?>): EvalReport {
        val byProvider = scores.groupBy { it.provider }
        val summaries = byProvider.map { (p, list) ->
            val ran = list.count { !it.skipped }
            val passed = list.count { it.passed && !it.skipped }
            val failed = list.count { !it.passed && !it.skipped }
            val skipped = list.count { it.skipped }
            val parseFailures = list.count { it.parseFailure }
            ProviderSummary(
                provider = p,
                model = models[p],
                ran = ran,
                passed = passed,
                failed = failed,
                skipped = skipped,
                parseFailures = parseFailures,
                passRate = if (ran == 0) null else passed.toDouble() / ran,
                skipReasons = list.mapNotNull { it.skipReason }.distinct(),
            )
        }.sortedBy { it.provider }
        val anyRan = summaries.any { it.ran > 0 }
        val anyParseFailure = scores.any { it.parseFailure }
        val thresholdOk = if (!anyRan) {
            true // nothing to grade; missing keys / unreachable Ollama is a skip, not a fail
        } else {
            summaries.filter { it.ran > 0 }.all { (it.passRate ?: 0.0) >= threshold }
        }
        // Any parse failure fails the run even when the pass-rate threshold is still met.
        val overall = thresholdOk && !anyParseFailure
        return EvalReport(threshold, summaries, scores, overall, anyRan, anyParseFailure)
    }

    fun toMarkdown(report: EvalReport): String = buildString {
        appendLine("## AI prompt evals")
        appendLine()
        appendLine("Pass threshold: **${"%.0f".format(report.threshold * 100)}%** of cases per provider that ran.")
        appendLine("Any **parse failure** (unparseable / empty / transport with no body) fails the run regardless of that threshold.")
        appendLine()
        if (!report.anyProviderRan) {
            appendLine("> No provider ran (missing keys, or Ollama unreachable). Harness OK; live accuracy not scored.")
            appendLine()
        }
        appendLine("| Provider | Model | Passed | Failed | Parse fail | Skipped | Pass rate |")
        appendLine("|---|---|---:|---:|---:|---:|---:|")
        report.providers.forEach { p ->
            val rate = p.passRate?.let { "${"%.0f".format(it * 100)}%" } ?: "—"
            appendLine("| ${p.provider} | ${p.model ?: "—"} | ${p.passed} | ${p.failed} | ${p.parseFailures} | ${p.skipped} | $rate |")
        }
        appendLine()
        report.providers.filter { it.skipReasons.isNotEmpty() }.forEach { p ->
            appendLine("- **${p.provider} skips:** ${p.skipReasons.joinToString("; ")}")
        }
        val parseFails = report.scores.filter { it.parseFailure }
        if (parseFails.isNotEmpty()) {
            appendLine()
            appendLine("### Parse failures (block the build)")
            parseFails.forEach { s ->
                appendLine("- `${s.provider}` / `${s.caseId}`: " +
                    (s.error ?: s.failedChecks.joinToString { "${it.name}: ${it.detail}" }))
            }
        }
        val scoreFails = report.scores.filter { !it.passed && !it.skipped && !it.parseFailure }
        if (scoreFails.isNotEmpty()) {
            appendLine()
            appendLine("### Accuracy / score failures")
            scoreFails.forEach { s ->
                appendLine("- `${s.provider}` / `${s.caseId}`: " +
                    (s.error ?: s.failedChecks.joinToString { "${it.name}: ${it.detail}" }))
            }
        }
        val info = report.scores.filter { it.infoMisses.isNotEmpty() }
        if (info.isNotEmpty()) {
            appendLine()
            appendLine("### Informational (parse-health; does not fail the build)")
            info.forEach { s ->
                appendLine("- `${s.provider}` / `${s.caseId}`: " +
                    s.infoMisses.joinToString { "${it.name}: ${it.detail}" })
            }
        }
        appendLine()
        if (report.anyParseFailure) {
            appendLine()
            appendLine("> Parse failure(s) present — overall FAIL regardless of pass-rate threshold.")
        }
        appendLine()
        appendLine("Overall: **${if (report.overallPassed) "PASS" else "FAIL"}**")
    }

    fun writeJson(report: EvalReport, file: File) {
        file.parentFile?.mkdirs()
        val root = mapper.createObjectNode()
        root.put("threshold", report.threshold)
        root.put("overallPassed", report.overallPassed)
        root.put("anyProviderRan", report.anyProviderRan)
        root.put("anyParseFailure", report.anyParseFailure)
        val providers = root.putArray("providers")
        report.providers.forEach { p ->
            providers.addObject().apply {
                put("provider", p.provider)
                put("model", p.model)
                put("ran", p.ran)
                put("passed", p.passed)
                put("failed", p.failed)
                put("skipped", p.skipped)
                put("parseFailures", p.parseFailures)
                if (p.passRate != null) put("passRate", p.passRate) else putNull("passRate")
            }
        }
        val scores = root.putArray("scores")
        report.scores.forEach { s ->
            scores.addObject().apply {
                put("caseId", s.caseId)
                put("provider", s.provider)
                put("prompt", s.prompt)
                put("passed", s.passed)
                put("skipped", s.skipped)
                put("parseFailure", s.parseFailure)
                put("skipReason", s.skipReason)
                put("error", s.error)
                val ch = putArray("checks")
                s.checks.forEach { c ->
                    ch.addObject().apply {
                        put("name", c.name)
                        put("passed", c.passed)
                        put("detail", c.detail)
                        put("required", c.required)
                    }
                }
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, root)
    }

    fun writeMarkdown(report: EvalReport, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toMarkdown(report))
    }

    fun appendStepSummary(report: EvalReport) {
        val path = System.getenv("GITHUB_STEP_SUMMARY") ?: return
        File(path).appendText(toMarkdown(report) + "\n")
    }
}
