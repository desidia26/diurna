package app.diurna.data

import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.ai.PackagePhotoReading
import app.diurna.ai.PackageQuestion
import app.diurna.ai.PackageReader
import app.diurna.ai.PackageReading
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.fdc.FdcBrandedFood
import app.diurna.domain.PackageMerge
import app.diurna.domain.PackageMerge.Source
import app.diurna.domain.ProductCode
import app.diurna.domain.ScanOutcome
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One photo of one or more packages → everything the app can learn from it. Barcodes are decoded
 * on the phone and looked up (Open Food Facts via [lookup], USDA branded via [fdcByCode]) while
 * the photo goes to the selected AI ([reader]) for each package's label, ingredients and
 * identity. Barcodes are matched to the AI's products (printed digits, then position), and each
 * row is merged label > USDA branded > OFF. A product known only by name gets database guesses
 * ([fdcSearch], [offSearch]) that the person must confirm; a guess never fills values by itself.
 *
 * Every step is appended to the capture's [DecisionLog]. The caller deletes the photo before
 * calling. [log] gets outcomes and counts for the debug action log, never product text or codes.
 */
class OnePhotoCapture(
    private val lookup: suspend (String, Boolean) -> ScanResult,
    private val fdcByCode: suspend (String) -> FdcBrandedFood?,
    /** Null when no provider is set up. */
    private val reader: PackageReader?,
    private val online: () -> Boolean,
    private val fdcSearch: suspend (String) -> List<FdcBrandedFood> = { emptyList() },
    private val offSearch: suspend (String) -> String? = { null },
    private val log: (name: String, result: String, details: Map<String, String>) -> Unit = { _, _, _ -> },
) {
    sealed interface LabelStatus {
        data object Read : LabelStatus
        /** Not attempted: [reason] is `offline` or `no_key`. */
        data class Skipped(val reason: String, val message: String) : LabelStatus
        data class Failed(val message: String) : LabelStatus
        data object NoPhoto : LabelStatus
    }

    /** What the phone decoded: the outcome, and each verified code's horizontal centre (0–1) by [ProductCode.key]. */
    data class PhotoCodes(val outcome: ScanOutcome, val positions: Map<String, Double> = emptyMap())

    /** A looked-up barcode. [off] is null when the lookup threw; [fdc] null when USDA had nothing. */
    data class CodeRead(val code: String, val centerX: Double?, val off: ScanResult?, val fdc: FdcBrandedFood?)

    /** A database record found by the product's name: only used once the person picks it. */
    data class Candidate(val ref: String, val source: Source, val title: String, val input: PackageMerge.Input, val barcode: String?)

    /** One package in the photo: the AI's reading of it, its barcode, and any name guesses. */
    data class Row(
        val key: String,
        val product: Int?,
        val reading: PackageReading?,
        val code: CodeRead?,
        val matchedBy: String,
        val candidates: List<Candidate>,
        val picked: Candidate?,
    ) {
        val isGuess: Boolean get() = picked != null

        val inputs: List<PackageMerge.Input> get() = buildList {
            reading?.let { r ->
                if (r.perServing.isNotEmpty() || r.ingredientsText != null) add(labelMergeInput(r))
            }
            code?.let { c ->
                c.fdc?.let { add(fdcMergeInput(it)) }
                offDraft(c.off)?.offSnapshotJson?.let(OffSnapshot::decode)?.let(::offMergeInput)?.let(::add)
            }
            picked?.let { add(it.input) }
        }

        val merged: PackageMerge.Merged get() = PackageMerge.merge(inputs)

        val suggestedName: String get() {
            reading?.let { r ->
                val name = r.productName.trim()
                if (name.isNotEmpty()) return listOf(r.brand.trim(), name).filter { it.isNotEmpty() }.joinToString(" ")
            }
            code?.let { c ->
                offDraft(c.off)?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                c.fdc?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return picked?.title.orEmpty()
        }

        /** Known only by name: nothing on the label or from a barcode to count from. */
        val needsIdentity: Boolean get() = reading?.hasIdentity == true && reading.perServing["kcal"] == null &&
            (code == null || (code.fdc == null && offDraft(code.off)?.offSnapshotJson?.let(OffSnapshot::decode)?.lookupOk != true))
    }

    data class Capture(
        val label: LabelStatus,
        val log: DecisionLog = DecisionLog(),
        val codes: List<CodeRead> = emptyList(),
        /** Unverified candidates from the photo; the person picks one. */
        val choose: List<ProductCode> = emptyList(),
        /** Name guesses by row key. */
        val guesses: Map<String, List<Candidate>> = emptyMap(),
        /** Confirmed guesses by row key. */
        val picked: Map<String, String> = emptyMap(),
        /** The last action's problem, e.g. a failed refinement. */
        val problem: String? = null,
    ) {
        val reading: PackagePhotoReading? get() = log.reading
        val questions: List<PackageQuestion> get() = reading?.questions.orEmpty()
        val labelLater: Boolean get() = label is LabelStatus.Skipped || label is LabelStatus.Failed

        val rows: List<Row> get() {
            val products = reading?.products.orEmpty()
            val (matches, why) = matchCodes(products, codes)
            val out = mutableListOf<Row>()
            products.forEachIndexed { i, p ->
                val ci = matches[i]
                val key = "p$i"
                val cands = guesses[key].orEmpty()
                out += Row(key, i, p, ci?.let { codes[it] }, why[i].orEmpty(), cands, cands.firstOrNull { it.ref == picked[key] })
            }
            codes.forEachIndexed { j, c ->
                if (j !in matches.values) {
                    val key = "c${c.code}"
                    val cands = guesses[key].orEmpty()
                    out += Row(key, null, null, c, "barcode only", cands, cands.firstOrNull { it.ref == picked[key] })
                }
            }
            if (out.isEmpty()) out += Row("empty", null, null, null, "", emptyList(), null)
            return out
        }

        fun note(kind: String, text: String, product: Int? = null) = copy(log = log.add(kind, text, product))
    }

    suspend fun run(photo: ByteArray?, decode: suspend () -> PhotoCodes, fallbackCode: String? = null): Capture = coroutineScope {
        val label = async { readLabel(photo) }
        val codes = try { decode() } catch (e: CancellationException) { throw e } catch (_: Exception) { PhotoCodes(ScanOutcome.Unreadable) }
        val o = codes.outcome
        log("onephoto.decode", when (o) {
            is ScanOutcome.Decoded -> "decoded"
            is ScanOutcome.Confirm -> if (o.codes.all { it.key in codes.positions }) "multiple" else "confirm"
            ScanOutcome.Unreadable -> "none"
            ScanOutcome.TextModelNotReady -> "text_model_not_ready"
        }, (o as? ScanOutcome.Confirm)?.let { mapOf("candidates" to it.codes.size.toString()) } ?: emptyMap())
        // Several codes the barcode detector itself verified are several packages; OCR-only candidates need a human pick.
        val verified: List<ProductCode> = when {
            o is ScanOutcome.Decoded -> listOf(o.code)
            o is ScanOutcome.Confirm && o.codes.size > 1 && o.codes.all { it.key in codes.positions } -> o.codes
            else -> emptyList()
        }
        val choose = if (o is ScanOutcome.Confirm && verified.isEmpty() && fallbackCode == null) o.codes else emptyList()
        var dlog = DecisionLog().add(DecisionLog.DECODE, when {
            verified.size == 1 -> "Barcode decoded on the phone: ${verified[0].key ?: verified[0].digits}"
            verified.size > 1 -> "${verified.size} barcodes decoded on the phone: ${verified.joinToString { it.key ?: it.digits }}"
            choose.isNotEmpty() -> "Barcode digits uncertain: ${choose.size} candidates for the person to pick"
            fallbackCode != null -> "No barcode in the photo; using the line's barcode $fallbackCode"
            else -> "No barcode in the photo"
        })
        val toLook = verified.map { it.digits to it.upcE }.ifEmpty { listOfNotNull(fallbackCode?.let { it to false }) }
        val looked = toLook.take(MAX_CODES).map { (d, e) -> async { lookUp(d, e, codes.positions[normalizeBarcode(d, e) ?: d]) } }.awaitAll()
        val (status, reading) = label.await()
        looked.forEach { dlog = logCode(dlog, it) }
        dlog = logLabel(dlog, status, reading)
        val first = Capture(status, dlog, looked, choose)
        done(identify(first.withMatchLog()))
    }

    /** The person picked one of the [Capture.choose] candidates. */
    suspend fun withCode(prev: Capture, code: ProductCode): Capture {
        val read = lookUp(code.digits, code.upcE, null)
        val c = prev.copy(codes = prev.codes + read, choose = emptyList(), log = logCode(prev.log.add(DecisionLog.EDIT, "Picked barcode ${read.code}"), read))
        return done(identify(c.withMatchLog()))
    }

    /** A live-scan fallback already looked the code up; add USDA and merge it in. */
    suspend fun withScan(prev: Capture, result: ScanResult): Capture {
        val code = offDraft(result)?.barcode ?: return prev
        log("onephoto.lookup", lookupDiagnostic(result).first, mapOf("via" to "live_scan"))
        val read = CodeRead(code, null, result, usda(code))
        val c = prev.copy(codes = prev.codes.filter { it.code != code } + read,
            log = logCode(prev.log.add(DecisionLog.EDIT, "Barcode $code from a live scan"), read))
        return done(identify(c.withMatchLog()))
    }

    /** A label read after the first one was skipped or failed: it becomes an AI interpretation. */
    suspend fun withLabel(prev: Capture, photo: ByteArray): Capture {
        val (status, reading) = readLabel(photo)
        val c = prev.copy(label = if (reading == null) status else LabelStatus.Read, log = logLabel(prev.log, status, reading))
        return done(identify(c.withMatchLog()))
    }

    /**
     * A refinement: the current interpretation plus the person's [answer] (and [photo] only if it
     * is still in memory) becomes a new revision. Earlier revisions are kept unchanged.
     */
    suspend fun refine(prev: Capture, photo: ByteArray?, answer: String): Capture {
        val current = prev.reading ?: return prev.copy(problem = "There is no AI reading to refine yet.")
        val r = reader ?: return prev.copy(problem = NO_KEY)
        if (!prev.log.canRefine) return prev.copy(problem = "No more refinements for this photo.")
        if (!online()) return prev.copy(problem = OFFLINE)
        val asked = prev.log.add(DecisionLog.ANSWER, "Person: “${answer.take(200)}”" + if (photo == null) " (refined from the stored reading; photo already deleted)" else "")
        return try {
            val next = r.refine(photo, current, answer)
            log("onephoto.refine", "ok", mapOf("with_photo" to (photo != null).toString(), "products" to next.products.size.toString(), "questions" to next.questions.size.toString()))
            var l = asked.addRevision(next, DecisionLog.ORIGIN_REFINE, answer)
            diffReadings(current, next).take(30).forEach { l = l.add(DecisionLog.REVISION, "Changed: $it") }
            next.questions.forEach { q -> l = l.add(DecisionLog.QUESTION, "AI asks: ${q.text}", q.productIndex) }
            done(identify(prev.copy(log = l, problem = null, guesses = emptyMap(), picked = emptyMap()).withMatchLog()))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val msg = labelProblem(e).second
            log("onephoto.refine", "failed", mapOf("exception" to e.javaClass.simpleName))
            prev.copy(log = asked.add(DecisionLog.REVISION, "Refinement failed: $msg; kept interpretation ${prev.log.current + 1}"), problem = msg)
        }
    }

    /** Use an earlier interpretation again; nothing is deleted. Guesses are looked up again by [identifyAgain]. */
    fun revert(prev: Capture, to: Int): Capture {
        return prev.copy(log = prev.log.revert(to), guesses = emptyMap(), picked = emptyMap(), problem = null)
    }

    suspend fun identifyAgain(prev: Capture): Capture = done(identify(prev))

    /** Confirm a name guess for [rowKey] ([ref] null clears it). */
    fun pick(prev: Capture, rowKey: String, ref: String?): Capture {
        val row = prev.rows.firstOrNull { it.key == rowKey } ?: return prev
        val cand = ref?.let { r -> row.candidates.firstOrNull { it.ref == r } }
        val picked = if (cand == null) prev.picked - rowKey else prev.picked + (rowKey to cand.ref)
        val text = if (cand == null) "Guess cleared for ${row.suggestedName.ifEmpty { rowKey }}"
            else "Guess confirmed by the person: ${cand.title} (${cand.source.wire}) for ${row.suggestedName.ifEmpty { rowKey }}"
        return prev.copy(picked = picked, log = prev.log.add(DecisionLog.GUESS, text, row.product))
    }

    private fun Capture.withMatchLog(): Capture {
        var l = log
        rows.forEach { row ->
            if (row.code != null && row.reading != null) {
                l = l.add(DecisionLog.MATCH, "Barcode ${row.code.code} → “${row.suggestedName}” (${row.matchedBy})", row.product)
            } else if (row.reading != null && codes.isNotEmpty()) {
                l = l.add(DecisionLog.MATCH, "No barcode matched “${row.suggestedName}”", row.product)
            }
        }
        return copy(log = l)
    }

    private suspend fun identify(c: Capture): Capture = coroutineScope {
        val todo = c.rows.filter { it.needsIdentity && it.key !in c.guesses }
        if (todo.isEmpty() || !online()) return@coroutineScope c
        val found = todo.map { row -> async { row to candidatesFor(row.reading!!) } }.awaitAll()
        var l = c.log
        found.forEach { (row, cands) ->
            val r = row.reading!!
            l = l.add(DecisionLog.GUESS, "Identified from the package front: “${row.suggestedName}”" + (if (r.sizeText.isNotEmpty()) ", ${r.sizeText}" else "") +
                "; ${cands.size} database match(es)" + (if (cands.isEmpty()) "" else ": " + cands.joinToString("; ") { "${it.title} (${it.source.wire})" }) +
                ". Not used until confirmed.", row.product)
        }
        log("onephoto.identify", "done", mapOf("rows" to found.size.toString(), "candidates" to found.sumOf { it.second.size }.toString()))
        c.copy(log = l, guesses = c.guesses + found.associate { (row, cands) -> row.key to cands })
    }

    private suspend fun candidatesFor(r: PackageReading): List<Candidate> = coroutineScope {
        val q = listOf(r.brand.trim(), r.productName.trim()).filter { it.isNotEmpty() }.joinToString(" ").take(120)
        val usda = async {
            try { fdcSearch(q).take(3) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        val off = async {
            try { offSearch(q)?.let { parseOffSearch(it) }.orEmpty() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        val a = usda.await().mapNotNull { f ->
            val input = fdcMergeInput(f).takeIf { PackageMerge.merge(listOf(it)).hasNutrition } ?: return@mapNotNull null
            Candidate("fdc:${f.fdcId}", Source.FDC_BRANDED, listOfNotNull(f.brand, f.description).joinToString(" ").take(80), input, f.gtin)
        }
        val b = off.await().mapNotNull { (code, json) ->
            val snap = parseOffSnapshot(json) ?: return@mapNotNull null
            val input = offMergeInput(snap)?.takeIf { PackageMerge.merge(listOf(it)).hasNutrition } ?: return@mapNotNull null
            val name = listOfNotNull(offBrand(json), offProductName(json)).joinToString(" ").ifEmpty { "Open Food Facts product" }
            Candidate("off:${code ?: name.hashCode()}", Source.OFF, name.take(80), input, code)
        }
        a + b
    }

    private fun done(c: Capture): Capture {
        c.rows.forEach { row ->
            val m = row.merged
            log("onephoto.merge", if (m.hasNutrition) "ok" else "no_nutrition", mapOf(
                "sources" to row.inputs.joinToString(",") { it.source.wire },
                "fields" to m.per100g.size.toString(),
                "disagreements" to m.per100g.values.count { it.disagreements.isNotEmpty() }.toString(),
                "flags" to m.flags.map { it.name.lowercase() }.sorted().joinToString(","),
                "guess" to row.isGuess.toString(),
            ))
        }
        return c
    }

    private suspend fun lookUp(digits: String, upcE: Boolean, centerX: Double?): CodeRead = coroutineScope {
        val code = normalizeBarcode(digits, upcE) ?: digits
        val usda = async { usda(code) }
        val off = try {
            lookup(digits, upcE).also { log("onephoto.lookup", lookupDiagnostic(it).first, lookupDiagnostic(it).second) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("onephoto.lookup", "failed", mapOf("exception" to e.javaClass.simpleName)); null
        }
        CodeRead(code, centerX, off, usda.await())
    }

    private suspend fun usda(code: String): FdcBrandedFood? = try {
        fdcByCode(code).also { log("onephoto.fdc", if (it == null) "none" else "found", emptyMap()) }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log("onephoto.fdc", "failed", mapOf("exception" to e.javaClass.simpleName)); null
    }

    private fun logCode(l: DecisionLog, c: CodeRead): DecisionLog {
        val off = when (val r = c.off) {
            is ScanResult.Known -> "in your library"
            is ScanResult.Found -> "found (${r.draft.name.take(60)})"
            is ScanResult.Incomplete -> "has the product but ${r.problem.message.lowercase().removeSuffix(".")}"
            is ScanResult.NotFound -> "not found"
            is ScanResult.Failed -> "lookup failed"
            null -> "lookup failed"
        }
        val usda = c.fdc?.let { "found (${it.description.take(60)})" } ?: "no match or no key"
        return l.add(DecisionLog.SOURCE, "Open Food Facts for ${c.code}: $off").add(DecisionLog.SOURCE, "USDA branded for ${c.code}: $usda")
    }

    private fun logLabel(l: DecisionLog, status: LabelStatus, reading: PackagePhotoReading?): DecisionLog {
        var out = when (status) {
            LabelStatus.Read -> l
            LabelStatus.NoPhoto -> l.add(DecisionLog.SOURCE, "AI label read: no photo")
            is LabelStatus.Skipped -> l.add(DecisionLog.SOURCE, "AI label read skipped: ${status.message}")
            is LabelStatus.Failed -> l.add(DecisionLog.SOURCE, "AI label read failed: ${status.message}")
        }
        if (reading != null) {
            out = out.addRevision(reading, DecisionLog.ORIGIN_READ)
            reading.products.forEachIndexed { i, p ->
                out = out.add(DecisionLog.SOURCE, "AI read product ${i + 1}: “${listOf(p.brand, p.productName).filter(String::isNotBlank).joinToString(" ").ifEmpty { "unnamed" }}”, " +
                    (if (p.hasPanel) "panel with ${p.perServing.size} value(s) per ${p.servingText.ifEmpty { "serving" }}" else "no panel") +
                    (if (p.ingredientsText != null) ", ingredients" else "") + (if (p.sizeText.isNotEmpty()) ", size ${p.sizeText}" else ""), i)
            }
            reading.questions.forEach { q -> out = out.add(DecisionLog.QUESTION, "AI asks: ${q.text}", q.productIndex) }
        }
        return out
    }

    private suspend fun readLabel(photo: ByteArray?): Pair<LabelStatus, PackagePhotoReading?> {
        val r: Pair<LabelStatus, PackagePhotoReading?> = when {
            photo == null -> LabelStatus.NoPhoto to null
            reader == null -> LabelStatus.Skipped("no_key", NO_KEY) to null
            !online() -> LabelStatus.Skipped("offline", OFFLINE) to null
            else -> try {
                LabelStatus.Read to reader.read(photo)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val (reason, msg) = labelProblem(e)
                (if (reason == null) LabelStatus.Failed(msg) else LabelStatus.Skipped(reason, msg)) to null
            }
        }
        val (s, reading) = r
        log("onephoto.label", when (s) {
            LabelStatus.Read -> "read"
            is LabelStatus.Skipped -> s.reason
            is LabelStatus.Failed -> "failed"
            LabelStatus.NoPhoto -> "no_photo"
        }, reading?.let { mapOf("products" to it.products.size.toString(), "panels" to it.products.count { p -> p.hasPanel }.toString(),
            "questions" to it.questions.size.toString()) } ?: emptyMap())
        return r
    }

    /** (skip reason or null for a failure, message). */
    private fun labelProblem(e: Exception): Pair<String?, String> = when (e) {
        is NoApiKey -> "no_key" to (e.message ?: NO_KEY)
        is EstimateRetryable, is IOException -> "offline" to OFFLINE
        is EstimateFailed -> null to (e.message ?: "Couldn't read the label")
        else -> null to "Couldn't read the label (${e.javaClass.simpleName})"
    }

    companion object {
        const val NO_KEY = "Add an AI key in Setup to read the label."
        const val OFFLINE = "Offline, so the label wasn't read."
        private const val MAX_CODES = 8
        /** Products and barcodes this far apart (fraction of the photo width) aren't paired by position. */
        private const val POSITION_SLACK = 0.2

        private fun offDraft(r: ScanResult?): MealEntity? = when (r) {
            is ScanResult.Known -> r.meal
            is ScanResult.Found -> r.draft
            is ScanResult.Incomplete -> r.draft
            is ScanResult.NotFound -> r.draft
            is ScanResult.Failed -> r.draft
            null -> null
        }

        private fun offBrand(json: String): String? = try {
            org.json.JSONObject(json).optJSONObject("product")?.optString("brands")?.split(',')?.firstOrNull()?.trim()?.ifEmpty { null }?.take(40)
        } catch (_: org.json.JSONException) { null }

        /**
         * Product index → code index, and why: printed digits first, then the only package with
         * the only barcode, then nearest horizontal position within [POSITION_SLACK].
         */
        fun matchCodes(products: List<PackageReading>, codes: List<CodeRead>): Pair<Map<Int, Int>, Map<Int, String>> {
            val m = mutableMapOf<Int, Int>(); val why = mutableMapOf<Int, String>()
            products.forEachIndexed { i, p ->
                val printed = p.printedBarcode?.let { normalizeBarcode(it) } ?: return@forEachIndexed
                val j = codes.indexOfFirst { it.code == printed }
                if (j >= 0 && j !in m.values) { m[i] = j; why[i] = "printed digits match" }
            }
            if (products.size == 1 && codes.size == 1 && m.isEmpty()) { m[0] = 0; why[0] = "only package and only barcode" }
            val pairs = products.indices.filter { it !in m && products[it].centerX != null }.flatMap { i ->
                codes.indices.filter { it !in m.values && codes[it].centerX != null }.map { j -> Triple(i, j, abs(products[i].centerX!! - codes[j].centerX!!)) }
            }.filter { it.third <= POSITION_SLACK }.sortedBy { it.third }
            for ((i, j, _) in pairs) if (i !in m && j !in m.values) { m[i] = j; why[i] = "position in the photo" }
            return m to why
        }

        /** Same outcome names as the scanner's lookup diagnostics. */
        fun lookupDiagnostic(result: ScanResult): Pair<String, Map<String, String>> = when (result) {
            is ScanResult.Known -> "known" to emptyMap()
            is ScanResult.Found -> "found" to emptyMap()
            is ScanResult.NotFound -> "not_found" to mapOf("spellings_tried" to result.tried.size.toString())
            is ScanResult.Incomplete -> "incomplete" to mapOf("problem" to result.problem.wire)
            is ScanResult.Failed -> result.reason to emptyMap()
        }
    }
}
