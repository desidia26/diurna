package app.diurna.data.fdc

import app.diurna.domain.AminoAcids
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FdcClientTest {
    @Test fun parseAminoProfileMapsNutrientIdsAndConvertsGToMg() {
        val food = JSONObject(
            """
            {
              "fdcId": 175254,
              "description": "Lentils, mature seeds, cooked, boiled, without salt",
              "foodNutrients": [
                {"nutrient": {"id": 1210, "name": "Tryptophan"}, "amount": 0.08},
                {"nutrient": {"id": 1214, "name": "Lysine"}, "amount": 0.624},
                {"nutrient": {"id": 1221, "name": "Histidine"}, "amount": 0.251},
                {"nutrient": {"id": 1216, "name": "Cystine"}, "amount": 0.117},
                {"nutrient": {"id": 1003, "name": "Protein"}, "amount": 9.02}
              ]
            }
            """.trimIndent(),
        )
        val p = FdcClient.parseAminoProfile(food)!!
        assertEquals(175254, p.fdcId)
        assertEquals(80, p.aminos.trpMg) // 0.08 g → 80 mg
        assertEquals(624, p.aminos.lysMg)
        assertEquals(251, p.aminos.hisMg)
        assertEquals(117, p.aminos.cysMg)
        assertNull(p.aminos.ileMg)
    }

    @Test fun parseReturnsNullWhenNoAminoNutrients() {
        val food = JSONObject(
            """{"fdcId": 1, "description": "Branded", "foodNutrients": [
              {"nutrient": {"id": 1008}, "amount": 100}
            ]}""",
        )
        assertNull(FdcClient.parseAminoProfile(food))
    }

    @Test fun scaleByGrams() {
        val profile = AminoProfile(
            1, "tofu",
            AminoAcids(lysMg = 1000, hisMg = 500), // per 100g
        )
        val scaled = profile.forGrams(50.0)
        assertEquals(500, scaled.lysMg)
        assertEquals(250, scaled.hisMg)
    }

    @Test fun searchTopAndProfileUseTransportAndRequireKey() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val transport = FdcTransport { path, key ->
            calls += path to key
            when {
                path.startsWith("/fdc/v1/foods/search") ->
                    """{"foods":[{"fdcId":99,"description":"Tofu, firm"}]}"""
                path == "/fdc/v1/food/99" ->
                    """{"fdcId":99,"description":"Tofu, firm","foodNutrients":[
                      {"nutrient":{"id":1214},"amount":0.5}
                    ]}"""
                else -> null
            }
        }
        val client = FdcClient({ "secret-key" }, transport)
        val hit = client.searchTop("tofu")!!
        assertEquals(99, hit.fdcId)
        val profile = client.aminoProfile(99)!!
        assertEquals(500, profile.aminos.lysMg)
        assertTrue(calls.all { it.second == "secret-key" })
        assertTrue(calls[0].first.contains("dataType=Foundation"))
        assertNull(FdcClient({ null }, transport).searchTop("tofu"))
        assertNull(FdcClient({ " " }, transport).aminoProfile(99))
    }

    @Test fun nutrientIdMapCoversElevenAminos() {
        assertEquals(11, FdcClient.NUTRIENT_TO_FIELD.size)
        assertEquals("trp", FdcClient.NUTRIENT_TO_FIELD[1210])
        assertEquals("cys", FdcClient.NUTRIENT_TO_FIELD[1216])
        assertEquals("his", FdcClient.NUTRIENT_TO_FIELD[1221])
    }
}
