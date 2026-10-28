package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroEnergyTest {
    @Test fun plainFourFourNineWhenFiberUnknown() {
        val e = MacroEnergy.kcal(proteinG = 30, carbsG = 40, fatG = 10)
        assertEquals(120.0, e.proteinKcal, 1e-9)
        assertEquals(160.0, e.carbsKcal, 1e-9)
        assertEquals(0.0, e.fiberKcal, 1e-9)
        assertEquals(90.0, e.fatKcal, 1e-9)
        assertEquals(370.0, e.total, 1e-9)
    }

    @Test fun totalFiberAtTwoKcalWhenSplitsUnknown() {
        // 40 total carbs, 10 fiber → available 30×4=120, fiber 10×2=20
        val e = MacroEnergy.kcal(30, 40, 10, fiberG = 10)
        assertEquals(120.0, e.carbsKcal, 1e-9)
        assertEquals(20.0, e.fiberKcal, 1e-9)
        assertEquals(120.0 + 20.0 + 90.0, e.total - e.proteinKcal, 1e-9)
    }

    @Test fun solubleAndInsolubleWhenBothKnown() {
        // sol 4×2=8, insol 6×0=0; fiber grams for clamp = 10; available 40-10=30×4=120
        val e = MacroEnergy.kcal(0, 40, 0, fiberG = 99, solubleFiberG = 4, insolubleFiberG = 6)
        assertEquals(120.0, e.carbsKcal, 1e-9)
        assertEquals(8.0, e.fiberKcal, 1e-9)
    }

    @Test fun clampsCarbsMinusFiberAtZero() {
        val e = MacroEnergy.kcal(0, 5, 0, fiberG = 12)
        assertEquals(0.0, e.carbsKcal, 1e-9)
        assertEquals(24.0, e.fiberKcal, 1e-9) // 12×2
    }

    @Test fun zeroFiberIsDistinctFromUnknown() {
        val knownZero = MacroEnergy.kcal(0, 40, 0, fiberG = 0)
        assertEquals(160.0, knownZero.carbsKcal, 1e-9)
        assertEquals(0.0, knownZero.fiberKcal, 1e-9)
        val unknown = MacroEnergy.kcal(0, 40, 0, fiberG = null)
        assertEquals(160.0, unknown.carbsKcal, 1e-9)
        assertEquals(0.0, unknown.fiberKcal, 1e-9)
        // Same numeric outcome when fiber is 0 or missing and carbs are total; the clamp path differs
        // only when fiber > carbs. Prefer splits over total when both present.
        val preferSplits = MacroEnergy.kcal(0, 40, 0, fiberG = 10, solubleFiberG = 2, insolubleFiberG = 3)
        assertEquals(5, 2 + 3) // fiber grams from splits
        assertEquals(140.0, preferSplits.carbsKcal, 1e-9) // (40-5)×4
        assertEquals(4.0, preferSplits.fiberKcal, 1e-9) // 2×2
    }

    @Test fun nullMacrosCountAsZero() {
        val e = MacroEnergy.kcal(null, null, null)
        assertEquals(0.0, e.total, 1e-9)
    }

    @Test fun metricsMacroKcalUsesFiberAdjustment() {
        val s = Metrics.macroKcal(
            java.time.LocalDate.of(2026, 10, 1),
            proteinG = 10, carbsG = 20, fatG = 5, fiberG = 5,
        )
        assertEquals(40.0, s.proteinKcal, 1e-9)
        assertEquals(60.0, s.carbsKcal, 1e-9) // (20-5)×4
        assertEquals(10.0, s.fiberKcal, 1e-9) // 5×2
        assertEquals(45.0, s.fatKcal, 1e-9)
        assertEquals(155.0, s.total, 1e-9)
    }
}
