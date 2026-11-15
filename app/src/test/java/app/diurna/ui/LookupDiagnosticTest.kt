package app.diurna.ui

import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.MealEntity
import app.diurna.data.OffProblem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Debug reports name the lookup's actual outcome instead of one "not_found" for everything. */
class LookupDiagnosticTest {
    private val blank = MealEntity(name = "Secret snack", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, barcode = "0012345678905")

    @Test fun eachMissHasItsOwnOutcome() {
        assertEquals("not_found" to mapOf("spellings_tried" to "2"),
            lookupDiagnostic(ScanResult.NotFound(blank, listOf("0012345678905", "012345678905"))))
        assertEquals("incomplete" to mapOf("problem" to "no_nutrition"),
            lookupDiagnostic(ScanResult.Incomplete(blank, OffProblem.NO_NUTRITION)))
        assertEquals("unreadable" to emptyMap<String, String>(),
            lookupDiagnostic(ScanResult.Failed("x", blank, reason = "unreadable")))
        assertEquals("unreachable" to emptyMap<String, String>(), lookupDiagnostic(ScanResult.Failed("x", blank)))
        assertEquals("found" to emptyMap<String, String>(), lookupDiagnostic(ScanResult.Found(blank)))
    }

    @Test fun detailsNeverCarryProductText() {
        val (_, details) = lookupDiagnostic(ScanResult.Incomplete(blank, OffProblem.NO_CALORIES))
        org.junit.Assert.assertTrue(details.values.none { it.contains("Secret") })
    }
}
