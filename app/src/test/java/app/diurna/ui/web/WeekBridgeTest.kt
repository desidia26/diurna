package app.diurna.ui.web

import app.diurna.debug.Store
import app.diurna.debug.Action
import app.diurna.domain.AlarmRules
import app.diurna.ui.WeekUi
import app.diurna.ui.WeekDayUi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeekBridgeTest {
    private val date = LocalDate.of(2026, 11, 2)
    private fun request(method: String = "week.get") = """{"v":1,"id":"test-1","method":"$method"}"""
    @Test fun rejectsUntrustedFramesAndMalformedCommands() {
        val router = WeekBridge({ null }, Store())
        assertNull(router.route(request(), "https://evil.test", true))
        assertNull(router.route(request(), WeekBridge.ORIGIN, false))
        listOf("x", "x".repeat(2049), request("database.read"), request().replace("1,", "2,"),
            request().replace("test-1", "<script>"), request().replace("}", ""","extra":1}"""))
            .forEach { assertEquals("invalid_request", JSONObject(router.route(it, WeekBridge.ORIGIN, true)!!).getString("error")) }
    }
    @Test fun mapsOnlyWeekDataAndLogsEveryCallWithoutPayload() {
        val events = mutableListOf<Action>()
        val ui = WeekUi(date, date, listOf(WeekDayUi(date,
            AlarmRules.morning(date, date.atTime(7, 0), "06:30", null), null, "Lift", "Plan", 123, 400, 1000)))
        val router = WeekBridge({ ui }, Store(reducer = { events.add(it) }))
        val reply = JSONObject(router.route(request(), WeekBridge.ORIGIN, true)!!)
        assertEquals(1, reply.getInt("v"))
        val day = reply.getJSONObject("data").getJSONArray("days").getJSONObject(0)
        assertEquals("2026-11-02", day.getString("date"))
        assertEquals(123, day.getInt("kcal"))
        assertFalse(reply.toString().contains("scannedAt"))
        assertEquals(2, events.size)
        assertEquals(Action.Result.OK, (events.last() as Action.Event).result)
    }
    @Test fun loadingIsExplicitAndReadyDoesNotReadData() {
        val router = WeekBridge({ null }, Store())
        assertTrue(JSONObject(router.route(request(), WeekBridge.ORIGIN, true)!!).isNull("data"))
        assertTrue(JSONObject(router.route(request("client.ready"), WeekBridge.ORIGIN, true)!!).getBoolean("ok"))
    }
    @Test fun onlyOfflineAssetPathsAreAllowed() {
        assertTrue(WeekBridge.allowedAsset(WeekBridge.ORIGIN + "/assets/web/index.html"))
        listOf("https://evil.test/assets/web/index.html", WeekBridge.ORIGIN + "/assets/other.json",
            "file:///assets/web/index.html", WeekBridge.ORIGIN + "/assets/web/../private", WeekBridge.ORIGIN + ":444/assets/web/index.html")
            .forEach { assertFalse(it, WeekBridge.allowedAsset(it)) }
    }
}
