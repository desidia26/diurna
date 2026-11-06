package app.diurna.ui.web

import app.diurna.debug.Action
import app.diurna.debug.Screen
import app.diurna.debug.Store
import app.diurna.ui.WeekUi
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** A deliberately closed, read-only v1 protocol. No settings, SQL, keys or arbitrary action names. */
class WeekBridge(private val week: () -> WeekUi?, private val actions: Store,
                 private val ready: () -> Unit = {}, private val failed: () -> Unit = {}) {
    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val URL = "$ORIGIN/assets/web/index.html"
        fun allowedAsset(url: String): Boolean = runCatching {
            val u = URI(url)
            u.scheme == "https" && u.rawAuthority == "appassets.androidplatform.net" &&
                u.rawPath.startsWith("/assets/web/") && !u.rawPath.contains("%") &&
                !u.rawPath.split('/').any { it == ".." || it == "." } && u.rawQuery == null && u.rawFragment == null
        }.getOrDefault(false)
    }

    fun route(raw: String, origin: String, mainFrame: Boolean): String? {
        val correlation = UUID.randomUUID().toString()
        val started = System.nanoTime()
        var result = Action.Result.FAILED
        actions.dispatch(Action.Event(Screen.WEEK, "web.bridge", correlation))
        try {
            if (origin != ORIGIN || !mainFrame) return null
            var id = "invalid"
            return try {
                require(raw.length <= 2048)
                val j = JSONObject(raw)
                require(j.keys().asSequence().toSet() == setOf("v", "id", "method"))
                require(j.get("v") == 1)
                id = j.getString("id")
                require(id.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
                val response = JSONObject().put("v", 1).put("id", id)
                when (j.getString("method")) {
                    "week.get" -> response.put("data", week()?.toWebJson() ?: JSONObject.NULL)
                    "client.ready" -> { ready(); response.put("ok", true) }
                    "client.failed" -> { failed(); response.put("ok", true) }
                    else -> error("unsupported")
                }
                result = Action.Result.OK
                response.toString()
            } catch (_: Exception) {
                JSONObject().put("v", 1).put("id", id.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,40}")) } ?: "invalid")
                    .put("error", "invalid_request").toString()
            }
        } finally {
            actions.dispatch(Action.Event(Screen.WEEK, "web.bridge", correlation, result,
                (System.nanoTime() - started) / 1_000_000))
        }
    }
}

fun WeekUi.toWebJson(): JSONObject = JSONObject().put("start", start.toString()).put("today", today.toString())
    .put("onTime", onTime).put("scoredCount", scoredCount).put("days", JSONArray(days.take(7).map { d ->
        JSONObject().put("date", d.date.toString()).put("morning", d.morning.status.name)
            .put("workoutName", d.workoutName?.take(200) ?: JSONObject.NULL)
            .put("plannedName", d.plannedName?.take(200) ?: JSONObject.NULL)
            .put("kcal", d.kcal ?: JSONObject.NULL).put("steps", d.steps ?: JSONObject.NULL).put("stepGoal", d.stepGoal)
    }))
