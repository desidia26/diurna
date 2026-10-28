package app.diurna.eval

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * JVM entry for `./gradlew aiEval`. Reuses the app's [app.diurna.ai] prompt builders, estimators
 * and parsers — never a copied prompt string.
 *
 * Args / system properties (Gradle -P maps into -D):
 * - provider=grok|claude|github-models|all
 * - set=default|full|smoke
 * - tag= optional case tag filter (e.g. smoke)
 * - grokModel= / claudeModel= / githubModel=
 * - outDir=ai-evals/out
 */
fun main(args: Array<String>) = runBlocking {
    val props = args.toList().plus(
        listOf("provider", "set", "tag", "grokModel", "claudeModel", "githubModel", "outDir", "root")
            .mapNotNull { k -> System.getProperty(k)?.let { "$k=$it" } },
    ).mapNotNull { s ->
        val i = s.indexOf('=')
        if (i <= 0) null else s.substring(0, i) to s.substring(i + 1)
    }.toMap()

    val root = File(props["root"] ?: System.getProperty("user.dir") ?: ".")
    val providerRaw = props["provider"] ?: "all"
    val setName = props["set"] ?: "default"
    val tag = props["tag"]
    val outDir = File(root, props["outDir"] ?: "ai-evals/out")

    println("AI evals: root=$root provider=$providerRaw set=$setName tag=${tag ?: "—"}")
    // Never print env values; only presence.
    fun present(name: String) = !System.getenv(name).isNullOrBlank()
    println(
        "Keys: XAI_API_KEY=${if (present("XAI_API_KEY")) "set" else "absent"}, " +
            "ANTHROPIC_API_KEY=${if (present("ANTHROPIC_API_KEY")) "set" else "absent"}, " +
            "GITHUB_TOKEN=${if (present("GITHUB_TOKEN") || present("GH_TOKEN")) "set" else "absent"}",
    )

    val report = EvalRunner(root).run(
        EvalRunner.Config(
            providers = EvalProviderId.parse(providerRaw),
            setName = setName,
            tag = tag,
            grokModel = props["grokModel"],
            claudeModel = props["claudeModel"],
            githubModel = props["githubModel"],
        ),
    )

    val md = File(outDir, "summary.md")
    val json = File(outDir, "results.json")
    EvalReportWriter.writeMarkdown(report, md)
    EvalReportWriter.writeJson(report, json)
    EvalReportWriter.appendStepSummary(report)
    println(EvalReportWriter.toMarkdown(report))
    println("Wrote ${md.absolutePath} and ${json.absolutePath}")

    if (!report.overallPassed) {
        kotlin.system.exitProcess(1)
    }
}
