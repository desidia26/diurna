package app.diurna.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalReportTest {
    @Test fun allSkippedIsOverallPass() {
        val scores = listOf(
            CaseScore("a", "grok", "food", passed = true, skipped = true, skipReason = "no key"),
            CaseScore("b", "grok", "food", passed = true, skipped = true, skipReason = "no key"),
        )
        val r = EvalReportWriter.aggregate(0.7, scores, mapOf("grok" to null))
        assertTrue(r.overallPassed)
        assertFalse(r.anyProviderRan)
    }

    @Test fun belowThresholdFails() {
        val scores = listOf(
            CaseScore("a", "grok", "food", passed = true),
            CaseScore("b", "grok", "food", passed = false, checks = listOf(CheckResult("x", false, "no"))),
            CaseScore("c", "grok", "food", passed = false, checks = listOf(CheckResult("x", false, "no"))),
        )
        val r = EvalReportWriter.aggregate(0.7, scores, mapOf("grok" to "m"))
        assertFalse(r.overallPassed)
        val md = EvalReportWriter.toMarkdown(r)
        assertTrue(md.contains("FAIL"))
        val tmp = File.createTempFile("eval", ".json")
        EvalReportWriter.writeJson(r, tmp)
        assertTrue(tmp.readText().contains("overallPassed"))
        tmp.delete()
    }

    @Test fun parseFailureFailsEvenWhenPassRateAboveThreshold() {
        // 4/5 passed → 80% >= 70% threshold, but one parse failure must still fail overall.
        val scores = listOf(
            CaseScore("a", "ollama", "food", passed = true),
            CaseScore("b", "ollama", "food", passed = true),
            CaseScore("c", "ollama", "food", passed = true),
            CaseScore("d", "ollama", "food", passed = true),
            CaseScore(
                "e", "ollama", "food", passed = false,
                error = "Couldn't read the estimate",
                parseFailure = true,
                checks = listOf(CheckResult("parse", false, "Couldn't read the estimate")),
            ),
        )
        val r = EvalReportWriter.aggregate(0.7, scores, mapOf("ollama" to "qwen"))
        assertTrue("pass rate should be 80%", (r.providers.single().passRate ?: 0.0) >= 0.7)
        assertTrue(r.anyParseFailure)
        assertFalse("parse failure must fail overall", r.overallPassed)
        val md = EvalReportWriter.toMarkdown(r)
        assertTrue(md.contains("Parse failures"))
        assertTrue(md.contains("FAIL"))
    }

    @Test fun accuracyMissAloneDoesNotSetParseFailure() {
        val scores = listOf(
            CaseScore(
                "a", "grok", "food", passed = false,
                checks = listOf(CheckResult("kcal_band", false, "way off", required = true)),
                parseFailure = false,
            ),
            CaseScore("b", "grok", "food", passed = true),
            CaseScore("c", "grok", "food", passed = true),
            CaseScore("d", "grok", "food", passed = true),
        )
        // 3/4 = 75% >= 70%; no parse failure → pass
        val r = EvalReportWriter.aggregate(0.7, scores, mapOf("grok" to "m"))
        assertFalse(r.anyParseFailure)
        assertTrue(r.overallPassed)
        val md = EvalReportWriter.toMarkdown(r)
        assertTrue(md.contains("Accuracy / score failures") || md.contains("kcal_band"))
    }

}
