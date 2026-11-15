package app.diurna.data

import app.diurna.ai.PackagePhotoReading
import app.diurna.ai.PackagePrompt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One interpretation decision, in order. [product] is the product index it's about, if any. */
data class Decision(val seq: Int, val kind: String, val text: String, val product: Int? = null)

/**
 * One AI interpretation of the photo. Revisions are never changed once added: a refinement adds
 * a new one ([origin] = refine, with the person's [answer] and the revision it was [basedOn]).
 */
data class Revision(val reading: PackagePhotoReading, val origin: String, val answer: String? = null, val basedOn: Int? = null)

/**
 * The capture's decision log: which sources were consulted and what they returned, which value
 * won each field and why, disagreements, identity guesses, the AI's questions, the person's
 * answers and edits. Append-only and ordered; stored with the ingredient (`decision_log_json`).
 */
data class DecisionLog(
    val entries: List<Decision> = emptyList(),
    val revisions: List<Revision> = emptyList(),
    /** Index of the revision in use, or -1 when the label wasn't read. */
    val current: Int = -1,
) {
    val reading: PackagePhotoReading? get() = revisions.getOrNull(current)?.reading
    val canRefine: Boolean get() = revisions.size < MAX_REVISIONS

    fun add(kind: String, text: String, product: Int? = null): DecisionLog {
        if (entries.size > MAX_ENTRIES) return this
        val e = if (entries.size == MAX_ENTRIES) Decision(entries.size + 1, TRUNCATED, "Later decisions not recorded (log full)")
        else Decision(entries.size + 1, kind, text.take(MAX_TEXT), product)
        return copy(entries = entries + e)
    }

    fun addRevision(reading: PackagePhotoReading, origin: String, answer: String? = null): DecisionLog {
        if (!canRefine) return add(REVISION, "Not added: at most $MAX_REVISIONS interpretations")
        val r = Revision(reading, origin, answer?.take(PackagePrompt.MAX_ANSWER), basedOn = current.takeIf { it >= 0 })
        val idx = revisions.size
        val text = when (origin) {
            ORIGIN_REFINE -> "Interpretation ${idx + 1}: refined from ${r.basedOn?.plus(1) ?: "nothing"} with “${r.answer.orEmpty()}”"
            else -> "Interpretation ${idx + 1}: AI read of the photo (${reading.products.size} product(s), ${reading.questions.size} question(s))"
        }
        return copy(revisions = revisions + r, current = idx).add(REVISION, text)
    }

    fun revert(to: Int): DecisionLog {
        if (to !in revisions.indices || to == current) return this
        return copy(current = to).add(REVERT, "Back to interpretation ${to + 1}")
    }

    fun toJson(): String {
        val o = JSONObject().put("v", 1).put("current", current)
        o.put("revisions", JSONArray().apply {
            revisions.forEach { r ->
                put(JSONObject().put("origin", r.origin).put("reading", JSONObject(PackagePrompt.toJson(r.reading))).apply {
                    r.answer?.let { put("answer", it) }
                    r.basedOn?.let { put("based_on", it) }
                })
            }
        })
        o.put("entries", JSONArray().apply {
            entries.forEach { e -> put(JSONObject().put("seq", e.seq).put("kind", e.kind).put("text", e.text).apply { e.product?.let { put("product", it) } }) }
        })
        return o.toString()
    }

    companion object {
        const val MAX_REVISIONS = 8
        const val MAX_ENTRIES = 400
        private const val MAX_TEXT = 400

        const val ORIGIN_READ = "ai_read"
        const val ORIGIN_REFINE = "refine"

        // Entry kinds.
        const val SOURCE = "source"
        const val DECODE = "decode"
        const val MATCH = "match"
        const val GUESS = "guess"
        const val QUESTION = "question"
        const val ANSWER = "answer"
        const val REVISION = "revision"
        const val REVERT = "revert"
        const val FIELD = "field"
        const val DISAGREEMENT = "disagreement"
        const val EDIT = "edit"
        const val SAVE = "save"
        const val TRUNCATED = "truncated"

        const val PRECEDENCE = "precedence: you > label > USDA branded > Open Food Facts"

        fun fromJson(json: String?): DecisionLog? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val revs = o.optJSONArray("revisions") ?: JSONArray()
                val revisions = (0 until revs.length()).map { i ->
                    val r = revs.getJSONObject(i)
                    Revision(
                        PackagePrompt.parse(r.getJSONObject("reading").toString()),
                        r.optString("origin", ORIGIN_READ),
                        if (r.has("answer")) r.getString("answer") else null,
                        if (r.has("based_on")) r.getInt("based_on") else null,
                    )
                }
                val es = o.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until es.length()).map { i ->
                    val e = es.getJSONObject(i)
                    Decision(e.getInt("seq"), e.getString("kind"), e.getString("text"), if (e.has("product")) e.getInt("product") else null)
                }
                DecisionLog(entries, revisions, o.optInt("current", -1).takeIf { it in revisions.indices } ?: -1)
            } catch (_: JSONException) { null } catch (_: app.diurna.ai.EstimateFailed) { null }
        }
    }
}

/** Human-readable differences between two interpretations, product by product. */
fun diffReadings(a: PackagePhotoReading, b: PackagePhotoReading): List<String> {
    val out = mutableListOf<String>()
    if (a.products.size != b.products.size) out += "products: ${a.products.size} → ${b.products.size}"
    for (i in 0 until maxOf(a.products.size, b.products.size)) {
        val p = a.products.getOrNull(i); val q = b.products.getOrNull(i)
        val label = (q ?: p)!!.let { listOf(it.brand, it.productName).filter(String::isNotBlank).joinToString(" ").ifEmpty { "product ${i + 1}" } }
        if (p == null) { out += "$label: added"; continue }
        if (q == null) { out += "$label: removed"; continue }
        fun ch(name: String, x: Any?, y: Any?) { if (x != y) out += "$label: $name ${x ?: "unknown"} → ${y ?: "unknown"}" }
        ch("name", p.productName, q.productName)
        ch("brand", p.brand, q.brand)
        ch("serving", p.servingText, q.servingText)
        ch("serving grams", p.servingGrams, q.servingGrams)
        for (k in (p.perServing.keys + q.perServing.keys).distinct()) ch(app.diurna.ui.factLabel(k), p.perServing[k], q.perServing[k])
        if (p.ingredientsText != q.ingredientsText) out += "$label: ingredients changed"
    }
    val qa = a.questions.map { it.text }; val qb = b.questions.map { it.text }
    if (qa != qb) out += "questions: ${qa.size} → ${qb.size}"
    return out
}
