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

    @Test fun parseNutritionProfileMapsMacrosAndAminos() {
        val food = JSONObject(
            """
            {
              "fdcId": 175254,
              "description": "Lentils, cooked",
              "foodNutrients": [
                {"nutrient": {"id": 1008}, "amount": 116},
                {"nutrient": {"id": 1003}, "amount": 9.02},
                {"nutrient": {"id": 1005}, "amount": 20.1},
                {"nutrient": {"id": 1004}, "amount": 0.38},
                {"nutrient": {"id": 1079}, "amount": 7.9},
                {"nutrient": {"id": 1214}, "amount": 0.624}
              ]
            }
            """.trimIndent(),
        )
        val p = FdcClient.parseNutritionProfile(food)!!
        assertEquals(175254, p.fdcId)
        assertEquals(116, p.kcalPer100)
        assertEquals(9, p.per100.proteinG)
        assertEquals(20, p.per100.carbsG)
        assertEquals(8, p.per100.fiberG)
        assertEquals(624, p.per100.aminos.lysMg)
        val half = p.forGrams(50.0)
        assertEquals(5, half.proteinG) // 9*0.5 rounds to 5
        assertEquals(312, half.aminos.lysMg)
    }

    @Test fun searchReturnsMultipleHits() = runBlocking {
        val transport = FdcTransport { path, _ ->
            if (path.startsWith("/fdc/v1/foods/search"))
                """{"foods":[{"fdcId":1,"description":"A"},{"fdcId":2,"description":"B"}]}"""
            else null
        }
        val hits = FdcClient({ "k" }, transport).search("x", pageSize = 10)
        assertEquals(2, hits.size)
        assertEquals(1, hits[0].fdcId)
        assertTrue(FdcClient({ null }, transport).search("x").isEmpty())
    }
}
