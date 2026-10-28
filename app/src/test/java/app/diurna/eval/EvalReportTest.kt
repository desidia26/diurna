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
}
