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

    private fun photo(vararg p: PackageReading, questions: List<app.diurna.ai.PackageQuestion> = emptyList()) =
        app.diurna.ai.PackagePhotoReading(p.toList(), questions)

    @Test fun multiProductCaseMatchesEachProductByName() {
        val case = EvalCase("m", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading", minProducts = 2,
            products = listOf(ProductExpect(listOf("pecan"), mapOf("kcal" to NutrientExpect(190.0, 210.0))),
                ProductExpect(listOf("almond"), mapOf("kcal" to NutrientExpect(160.0, 180.0))))))
        val pecan = reading(mapOf("kcal" to 200.0))
        val almond = reading(mapOf("kcal" to 170.0)).copy(productName = "Sliced Almonds", brand = "Mariani")
        assertTrue(EvalScoring.scorePackagePhoto(case, photo(pecan, almond)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(pecan)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(pecan, almond.copy(perServing = mapOf("kcal" to 300.0)))).passed)
    }

    @Test fun eachExpectedProductClaimsADifferentReading() {
        val case = EvalCase("d", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading",
            products = listOf(ProductExpect(listOf("pecan")), ProductExpect(listOf("pecan")))))
        val pecan = reading(mapOf("kcal" to 200.0))
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(pecan)).passed)
        assertTrue(EvalScoring.scorePackagePhoto(case, photo(pecan, pecan)).passed)
    }

    @Test fun aPackageWithoutItsFrontIsFoundByItsIngredients() {
        val case = EvalCase("o", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading",
            products = listOf(ProductExpect(listOf("oat"), mapOf("kcal" to NutrientExpect(145.0, 155.0)), panel = true))))
        val oats = PackageReading(true, "", "", "1/2 cup uncooked (40g)", 40.0, 30.0, mapOf("kcal" to 150.0), "WHOLE GRAIN ROLLED OATS.")
        assertTrue(EvalScoring.scorePackagePhoto(case, photo(oats)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(oats.copy(ingredientsText = "ALMONDS"))).passed)
    }

    @Test fun perProductPanelFlagsAndProductCountBounds() {
        val case = EvalCase("p", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading", minProducts = 2, maxProducts = 2,
            products = listOf(ProductExpect(listOf("flax"), panel = false), ProductExpect(listOf("pecan"), panel = true))))
        val flax = PackageReading(false, "Flax Seeds", "Roots Circle", "", null, null, emptyMap(), null)
        val pecan = reading(mapOf("kcal" to 200.0))
        assertTrue(EvalScoring.scorePackagePhoto(case, photo(flax, pecan)).passed)
        assertFalse("invented flax numbers", EvalScoring.scorePackagePhoto(case, photo(flax.copy(hasPanel = true, perServing = mapOf("kcal" to 45.0)), pecan)).passed)
        assertFalse("pecan panel missing", EvalScoring.scorePackagePhoto(case, photo(flax, pecan.copy(hasPanel = false))).passed)
        assertFalse("too many", EvalScoring.scorePackagePhoto(case, photo(flax, pecan, pecan)).passed)
    }

    @Test fun backgroundItemsMustNotComeBackWithPanelNumbers() {
        val case = EvalCase("b", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading", noPanelForAny = listOf("mango")))
        val pecan = reading(mapOf("kcal" to 200.0))
        val box = PackageReading(false, "Honey Mangos", "", "", null, null, emptyMap(), null)
        assertTrue(EvalScoring.scorePackagePhoto(case, photo(pecan, box)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(pecan, box.copy(hasPanel = true, perServing = mapOf("kcal" to 60.0)))).passed)
    }

    @Test fun questionsAreRequiredOrForbiddenAsTheCaseSays() {
        val q = app.diurna.ai.PackageQuestion(0, "serving_basis", "Dry or prepared?", listOf("Dry"))
        val ask = EvalCase("q", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading", questions = "required", questionTopicsAny = listOf("serving_basis")))
        val none = EvalCase("n", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "reading", questions = "none"))
        val r = reading(mapOf("kcal" to 200.0))
        assertTrue(EvalScoring.scorePackagePhoto(ask, photo(r, questions = listOf(q))).passed)
        assertFalse(EvalScoring.scorePackagePhoto(ask, photo(r)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(ask, photo(r, questions = listOf(q.copy(topic = "other")))).passed)
        assertTrue(EvalScoring.scorePackagePhoto(none, photo(r)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(none, photo(r, questions = listOf(q))).passed)
    }

    @Test fun identityCaseNeedsTheNameAndNoNumbers() {
        val case = EvalCase("i", "package", input = CaseInput(image = "x.jpg"), expect = CaseExpect(outcome = "no_panel", identityContainsAny = listOf("flax")))
        val front = PackageReading(false, "Flax Seeds", "Roots Circle", "", null, null, emptyMap(), null)
        assertTrue(EvalScoring.scorePackagePhoto(case, photo(front)).passed)
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(front.copy(productName = "Seeds"))).passed)
        assertFalse(EvalScoring.scorePackagePhoto(case, photo(front.copy(perServing = mapOf("kcal" to 45.0)))).passed)
    }

    @Test fun shippedRefineCasesHaveAParseablePreviousReadingAndAnAnswer() {
        val root = listOf(File("../ai-evals"), File("ai-evals")).first { it.isDirectory }
        val all = EvalCases.load(File(root, "cases"), "default").cases
        val refine = all.filter { it.prompt == "package_refine" }
        assertTrue(refine.size >= 2)
        assertTrue("a text-only refinement is covered", refine.any { it.input.image == null })
        refine.forEach { c ->
            app.diurna.ai.PackagePrompt.parse(File(root, c.input.previous!!).readText())
            assertTrue(c.id, !c.input.answer.isNullOrBlank())
            c.input.image?.let { assertTrue(c.id, File(root, it).isFile) }
        }
        val pkg = all.filter { it.prompt == "package" }
        assertTrue(pkg.any { it.expect.questions == "required" })
        assertTrue(pkg.any { it.expect.questions == "none" })
        assertTrue(pkg.any { (it.expect.minProducts ?: 0) >= 3 })
        assertTrue(pkg.any { it.expect.identityContainsAny.isNotEmpty() })
        val six = pkg.single { it.id == "package-oatmeal-six-real" }
        assertTrue("five real packages", (six.expect.minProducts ?: 0) >= 5 && six.expect.products.size == 5)
        assertTrue("the background box is checked", six.expect.noPanelForAny.isNotEmpty())
        assertTrue(File(root, six.input.image!!).isFile)
    }
}
