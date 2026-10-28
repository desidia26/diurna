package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AminoAcidsTest {
    @Test fun scaleAndSumPreserveNulls() {
        val a = AminoAcids(lysMg = 100, hisMg = null, metMg = 40, cysMg = 10)
        assertEquals(AminoAcids(lysMg = 50, metMg = 20, cysMg = 5), a.scale(0.5))
        assertEquals(50, a.metCysMg)
        val sum = AminoAcids.sum(listOf(a, AminoAcids(lysMg = 20, ileMg = 5)))
        assertEquals(120, sum.lysMg)
        assertEquals(5, sum.ileMg)
        assertNull(sum.hisMg)
        assertTrue(sum.hasAny)
        assertFalse(AminoAcids().hasAny)
    }

    @Test fun averageIgnoresUnknownFields() {
        val avg = AminoAcids.average(
            listOf(
                AminoAcids(lysMg = 100, hisMg = 10),
                AminoAcids(lysMg = 200),
                AminoAcids(),
            ),
        )!!
        assertEquals(150, avg.lysMg)
        assertEquals(10, avg.hisMg)
        assertNull(avg.ileMg)
    }

    @Test fun primaryBarsUsePairsNotIndividuals() {
        val a = AminoAcids(metMg = 40, cysMg = 10, pheMg = 50, tyrMg = 20, lysMg = 100)
        val primary = AminoAcids.PRIMARY_BARS.associate { it.first to it.second(a) }
        assertEquals(50, primary["Met+Cys"])
        assertEquals(70, primary["Phe+Tyr"])
        assertEquals(100, primary["Lys"])
        assertNull(primary["Met"])
        assertNull(primary["Phe"])
        val detail = AminoAcids.PAIR_DETAIL.associate { it.first to it.second(a) }
        assertEquals(40, detail["Met"])
        assertEquals(10, detail["Cys"])
        assertEquals(50, detail["Phe"])
        assertEquals(20, detail["Tyr"])
    }
}
