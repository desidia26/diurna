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
        assertTrue(set.passThreshold in 0.0..1.0)
    }

    @Test fun fullIncludesDefaultPlusExtras() {
        val dir = resolveCasesDir()
        val def = EvalCases.load(dir, "default")
        val full = EvalCases.load(dir, "full")
        assertTrue(full.cases.size > def.cases.size)
        assertTrue(full.cases.any { it.id == "food-burrito-bowl" })
        assertTrue(full.cases.any { it.id == "food-big-mac" })
    }

    @Test fun smokeIsTinyAndTextOnly() {
        val set = EvalCases.load(resolveCasesDir(), "smoke")
        assertEquals(4, set.cases.size)
        assertTrue(set.cases.all { it.input.image == null })
        assertTrue(set.cases.any { it.prompt == "food" })
        assertTrue(set.cases.any { it.prompt == "barcode_ingredients" })
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
