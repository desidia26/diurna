package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhoAminoRefsTest {
    @Test fun scaleLbUsesWhoTable23Rates() {
        // 100 kg → 220.462 lb; Lys 30 mg/kg → 3000 mg
        val lb = 100.0 / 0.45359237
        val s = WhoAminoRefs.scaleLb(lb)!!
        assertEquals(1000, s.hisMg)
        assertEquals(2000, s.ileMg)
        assertEquals(3900, s.leuMg)
        assertEquals(3000, s.lysMg)
        assertEquals(1500, s.metCysMg)
        assertEquals(1000, s.metMg)
        assertEquals(400, s.cysMg)
        assertEquals(2500, s.pheTyrMg)
        assertEquals(1500, s.thrMg)
        assertEquals(400, s.trpMg)
        assertEquals(2600, s.valMg)
        assertEquals(1500, s.forPrimaryLabel("Met+Cys"))
        assertEquals(2500, s.forPrimaryLabel("Phe+Tyr"))
    }

    @Test fun noWeightHidesRefs() {
        assertNull(WhoAminoRefs.scaleLb(null))
        assertNull(WhoAminoRefs.scaleLb(0.0))
        assertNull(WhoAminoRefs.scaleLb(-1.0))
        assertNull(WhoAminoRefs.scaleKg(null))
        assertNull(WhoAminoRefs.scaleKg(0.0))
    }

    @Test fun pairSumsMatchTable23() {
        // Table 23: Met 10 + Cys 4 = 14, but the published Met+Cys total is 15
        // (oxidative-loss text 10.4+4.1≈14.5, rounded). We keep the table as printed.
        assertEquals(10, WhoAminoRefs.MET_MG_PER_KG)
        assertEquals(4, WhoAminoRefs.CYS_MG_PER_KG)
        assertEquals(15, WhoAminoRefs.MET_CYS_MG_PER_KG)
        assertEquals(14, WhoAminoRefs.MET_MG_PER_KG + WhoAminoRefs.CYS_MG_PER_KG)
        // Phe+Tyr is a pair total only in Table 23 (no separate Phe/Tyr rows).
        assertEquals(25, WhoAminoRefs.PHE_TYR_MG_PER_KG)
    }
}
