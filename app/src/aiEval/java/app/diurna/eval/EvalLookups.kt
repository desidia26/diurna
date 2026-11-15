package app.diurna.eval

import app.diurna.ai.KnownFacts
import app.diurna.ai.PackageTwoPass
import app.diurna.data.fdc.FdcClient
import app.diurna.data.knownFromFdc
import app.diurna.data.knownFromOffJson
import app.diurna.data.normalizeBarcode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject

/**
 * Product-database answers for the harness, from responses recorded once (ai-evals/lookups.json),
 * never the network: the same inputs give the same pass-2 context on every run and in CI. They go
 * through the app's own parsers and converters ([knownFromFdc], [knownFromOffJson]), as on the phone.
 */
class EvalLookups(file: File) : PackageTwoPass.Lookups {
    private val root = ObjectMapper().readTree(file)
    /** Every lookup asked, in order: "code:…" / "name:…" with hit counts, for the report. */
    val asked = ConcurrentLinkedQueue<String>()

    override suspend fun byCode(digits: String): List<KnownFacts> {
        val code = normalizeBarcode(digits) ?: return emptyList()
        val rec = root["byCode"]?.get(code)
        val out = if (rec == null) emptyList() else listOfNotNull(
            rec["fdc"]?.takeIf { it.isObject }?.let { FdcClient.parseBranded(JSONObject(it.toString())) }?.let { knownFromFdc(it, byName = false) },
            rec["off"]?.takeIf { it.isTextual }?.let { knownFromOffJson(it.asText(), byName = false) },
        )
        asked += "code:$code=${out.size}"
        return out
    }

    override suspend fun byName(query: String): List<KnownFacts> {
        val q = query.lowercase()
        val rec = root["byName"]?.firstOrNull { it["match"].asText() in q }
        val out = rec?.get("fdc")?.take(2)?.mapNotNull { f -> FdcClient.parseBranded(JSONObject(f.toString()))?.let { knownFromFdc(it, byName = true) } }.orEmpty()
        asked += "name:${query.take(40)}=${out.size}"
        return out
    }
}
