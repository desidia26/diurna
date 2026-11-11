package app.diurna.eval

import app.diurna.ai.PackageReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackageScoringTest {
    private fun reading(per: Map<String, Double>, ingredients: String? = "PECANS.", panel: Boolean = true, grams: Double? = 28.0) =
        PackageReading(panel, "Pecan Halves", "Member's Mark", "1 oz (28g)", grams, 32.0, per, ingredients)

    private val pecans = EvalCase(
        "p", "package", input = CaseInput(image = "x.jpg"),
        expect = CaseExpect(
            outcome = "reading",
            requirePanel = true,
            servingGrams = DoubleRangeExpect(27.0, 29.0),
            nutrients = mapOf(
                "kcal" to NutrientExpect(190.0, 210.0),
                "fat_g" to NutrientExpect(18.0, 22.0),
                "carbs_g" to NutrientExpect(2.0, 6.0, required = false),
            ),
            ingredientsContainsAny = listOf("pecan"),
        ),
    )

    @Test fun inBandReadingPasses() {
        val s = EvalScoring.scorePackage(pecans, reading(mapOf("kcal" to 200.0, "fat_g" to 20.0, "carbs_g" to 4.0)))
        assertTrue(s.failedChecks.toString(), s.passed)
    }

    @Test fun optionalNutrientMissOrUnknownDoesNotFail() {
        val s = EvalScoring.scorePackage(pecans, reading(mapOf("kcal" to 200.0, "fat_g" to 20.0)))
        assertTrue(s.passed)
        assertTrue(s.checks.any { it.name == "carbs_g" && !it.passed && !it.required })
    }

    @Test fun requiredUnknownOrOutOfBandFailsAsAccuracyNotParse() {
        val s = EvalScoring.scorePackage(pecans, reading(mapOf("kcal" to 200.0)))
        assertFalse(s.passed)
        assertFalse(s.parseFailure)
        assertTrue(s.failedChecks.any { it.name == "fat_g" })
    }

    @Test fun missingIngredientsAndPanelFail() {
        val s = EvalScoring.scorePackage(pecans, reading(emptyMap(), ingredients = null, panel = false))
        assertTrue(s.failedChecks.any { it.name == "has_panel" })
        assertTrue(s.failedChecks.any { it.name == "ingredients" })
    }

    @Test fun noPanelCaseAcceptsRejectOrEmptyButNotInventedNumbers() {
        val c = EvalCase("b", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "no_panel"))
        val fail = EvalScoring.scorePackageFailure(c, "No label or ingredients found.")
        assertTrue(fail.passed)
        assertFalse(fail.parseFailure)
        assertTrue(EvalScoring.scorePackage(c, reading(emptyMap(), ingredients = "Flaxseeds", panel = false)).passed)
        assertFalse(EvalScoring.scorePackage(c, reading(mapOf("kcal" to 45.0))).passed)
    }

    @Test fun unexpectedFailureOnAReadingCaseIsAParseFailure() {
        val s = EvalScoring.scorePackageFailure(pecans, "Couldn't read the label")
        assertFalse(s.passed)
        assertTrue(s.parseFailure)
    }

    @Test fun caseJsonLoadsNutrientExpectations() {
        val f = File.createTempFile("cases", ".json")
        f.writeText("""{"version":1,"description":"t","passThreshold":0.7,"cases":[{"id":"p","prompt":"package",
            "input":{"image":"images/real/pecans.jpg"},
            "expect":{"outcome":"reading","requirePanel":true,"servingGrams":{"min":27,"max":29},
              "nutrients":{"kcal":{"min":190,"max":210},"carbs_g":{"min":2,"max":6,"required":false}},
              "ingredientsContainsAny":["pecan"]}}]}""")
        val e = EvalCases.loadFile(f).cases.single().expect
        assertTrue(e.requirePanel)
        assertEquals(NutrientExpect(190.0, 210.0), e.nutrients["kcal"])
        assertEquals(NutrientExpect(2.0, 6.0, required = false), e.nutrients["carbs_g"])
        assertEquals(listOf("pecan"), e.ingredientsContainsAny)
        f.delete()
    }

    @Test fun shippedPackageCasesUseKnownNutrientKeysAndExistingImages() {
        val root = listOf(File("../ai-evals"), File("ai-evals")).first { it.isDirectory }
        val cases = EvalCases.load(File(root, "cases"), "default").cases.filter { it.prompt == "package" }
        assertTrue(cases.size >= 6)
        cases.forEach { c ->
            assertTrue(c.id, File(root, c.input.image!!).isFile)
            assertTrue(c.id, c.expect.nutrients.keys.all { it in app.diurna.ai.PackagePrompt.NUTRIENT_KEYS })
        }
    }
}
