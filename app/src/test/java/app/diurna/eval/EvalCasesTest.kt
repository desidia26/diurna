package app.diurna.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalCasesTest {
    private val casesDir = File("../ai-evals/cases").takeIf { it.isDirectory }
        ?: File("ai-evals/cases").takeIf { it.isDirectory }
        ?: File("../../ai-evals/cases") // Gradle unit-test cwd is usually app/

    @Test fun loadsDefaultSet() {
        val dir = resolveCasesDir()
        val set = EvalCases.load(dir, "default")
        assertTrue(set.cases.size >= 8)
        assertTrue(set.cases.any { it.prompt == "food" })
        assertTrue(set.cases.any { it.prompt == "cardio" })
        assertTrue(set.cases.any { it.prompt == "label" })
        assertTrue(set.cases.any { it.prompt == "density" })
        assertTrue(set.cases.any { it.input.detailed })
        assertTrue(set.cases.any { it.input.ingredients })
        assertTrue(set.passThreshold in 0.0..1.0)
    }

    @Test fun fullIncludesDefaultPlusExtras() {
        val dir = resolveCasesDir()
        val def = EvalCases.load(dir, "default")
        val full = EvalCases.load(dir, "full")
        assertTrue(full.cases.size > def.cases.size)
        assertTrue(full.cases.any { it.id == "food-burrito-bowl" })
        assertTrue(full.cases.any { it.id == "food-big-mac" })
        assertTrue(full.cases.any { it.id == "label-cereal" })
    }

    @Test fun smokeIsTinyAndTextOnly() {
        val set = EvalCases.load(resolveCasesDir(), "smoke")
        assertTrue("smoke stays small", set.cases.size in 5..12)
        assertTrue(set.cases.all { it.input.image == null })
        assertTrue(set.cases.all { it.prompt in setOf("food", "density") })
        assertTrue(set.cases.any { it.prompt == "food" })
        assertTrue(set.cases.any { it.prompt == "density" })
        assertTrue(set.cases.any { it.input.detailed && !it.input.ingredients })
        assertTrue(set.cases.any { it.input.ingredients })
    }

    @Test fun defaultIncludesDensity() {
        val set = EvalCases.load(resolveCasesDir(), "default")
        assertTrue(set.cases.any { it.prompt == "density" && it.input.unitKind == "volume" })
        assertTrue(set.cases.any { it.prompt == "density" && it.input.unitKind == "each" })
        assertTrue(set.cases.any { it.expect.gPerMl != null })
        assertTrue(set.cases.any { it.expect.gramsPerEach != null })
    }

    private fun resolveCasesDir(): File {
        val candidates = listOf(
            File("ai-evals/cases"),
            File("../ai-evals/cases"),
            File(System.getProperty("user.dir"), "ai-evals/cases"),
            File(System.getProperty("user.dir"), "../ai-evals/cases"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("ai-evals/cases not found from cwd=${System.getProperty("user.dir")}")
    }
}
