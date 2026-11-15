package app.diurna.data

import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.ai.PackageReading
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.fdc.FdcBrandedFood
import app.diurna.domain.PackageMerge
import app.diurna.domain.ProductCode
import app.diurna.domain.ScanOutcome
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * One photo of a package → everything the app can learn from it. The barcode is decoded on the
 * phone and looked up (Open Food Facts via [lookup], USDA branded via [fdc]) while the photo goes
 * to the selected AI provider for the label and ingredients ([reader]); then [PackageMerge] picks
 * label > USDA branded > OFF per field and flags disagreements.
 *
 * The caller deletes the photo before calling: only the bytes passed here exist, and only for
 * the AI call. [log] gets outcomes and counts for the debug log, never product text or codes.
 */
class OnePhotoCapture(
    private val lookup: suspend (String, Boolean) -> ScanResult,
    private val fdc: suspend (String) -> FdcBrandedFood?,
    /** Null when no provider can read photos (no key / images off). */
    private val reader: (suspend (ByteArray) -> PackageReading)?,
    private val online: () -> Boolean,
    private val log: (name: String, result: String, details: Map<String, String>) -> Unit = { _, _, _ -> },
) {
    sealed interface LabelRead {
        data class Read(val reading: PackageReading) : LabelRead
        /** The AI saw no Nutrition Facts panel; any ingredients it read still count. */
        data class NoPanel(val reading: PackageReading) : LabelRead
        /** Not attempted: [reason] is `offline` or `no_key`. */
        data class Skipped(val reason: String, val message: String) : LabelRead
        data class Failed(val message: String) : LabelRead
        data object NoPhoto : LabelRead
    }

    sealed interface CodeState {
        /** [off] is null when the lookup itself threw; [fdc] is null when USDA had nothing or no key. */
        data class Read(val code: String, val off: ScanResult?, val fdc: FdcBrandedFood?) : CodeState
        /** The photo held candidates the decoder couldn't verify; the person picks one. */
        data class Choose(val codes: List<ProductCode>) : CodeState
        data object None : CodeState
    }

    data class Capture(val code: CodeState, val label: LabelRead) {
        val barcode: String? get() = (code as? CodeState.Read)?.code

        val inputs: List<PackageMerge.Input> get() = buildList {
            when (label) {
                is LabelRead.Read -> add(labelMergeInput(label.reading))
                is LabelRead.NoPanel -> label.reading.ingredientsText?.let { add(labelMergeInput(label.reading.copy(perServing = emptyMap()))) }
                else -> {}
            }
            (code as? CodeState.Read)?.let { r ->
                r.fdc?.let { add(fdcMergeInput(it)) }
                offDraft(r.off)?.offSnapshotJson?.let(OffSnapshot::decode)?.let(::offMergeInput)?.let(::add)
            }
        }

        val merged: PackageMerge.Merged get() = PackageMerge.merge(inputs)

        /** Label name (with brand), then the library / OFF / USDA name; blank when none. */
        val suggestedName: String get() {
            val reading = (label as? LabelRead.Read)?.reading ?: (label as? LabelRead.NoPanel)?.reading
            reading?.let { r ->
                val name = r.productName.trim()
                if (name.isNotEmpty()) return listOf(r.brand.trim(), name).filter { it.isNotEmpty() }.joinToString(" ")
            }
            val r = code as? CodeState.Read ?: return ""
            offDraft(r.off)?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return r.fdc?.description?.trim().orEmpty()
        }

        /** The label wasn't read but could be: offer a read later (another photo, once online / keyed). */
        val labelLater: Boolean get() = label is LabelRead.Skipped || label is LabelRead.Failed
    }

    /**
     * [photo] goes to the AI (null when there is no picture to send); [decode] reads the barcode
     * on the phone. [fallbackCode] stands in when the photo has no readable barcode, e.g. a label
     * re-read for a line that already has one.
     */
    suspend fun run(photo: ByteArray?, decode: suspend () -> ScanOutcome, fallbackCode: String? = null): Capture = coroutineScope {
        val label = async { readLabel(photo) }
        val outcome = try { decode() } catch (e: CancellationException) { throw e } catch (_: Exception) { ScanOutcome.Unreadable }
        log("onephoto.decode", when (outcome) {
            is ScanOutcome.Decoded -> "decoded"
            is ScanOutcome.Confirm -> "confirm"
            ScanOutcome.Unreadable -> "none"
            ScanOutcome.TextModelNotReady -> "text_model_not_ready"
        }, (outcome as? ScanOutcome.Confirm)?.let { mapOf("candidates" to it.codes.size.toString()) } ?: emptyMap())
        val code = when {
            outcome is ScanOutcome.Decoded -> lookUp(outcome.code.digits, outcome.code.upcE)
            outcome is ScanOutcome.Confirm && fallbackCode == null -> CodeState.Choose(outcome.codes)
            fallbackCode != null -> lookUp(fallbackCode, false)
            else -> CodeState.None
        }
        done(Capture(code, label.await()))
    }

    /** The person picked one of the [CodeState.Choose] candidates. */
    suspend fun withCode(prev: Capture, code: ProductCode): Capture = done(prev.copy(code = lookUp(code.digits, code.upcE)))

    /** A live-scan fallback already looked the code up; add USDA and merge it in. */
    suspend fun withScan(prev: Capture, result: ScanResult): Capture {
        val code = offDraft(result)?.barcode ?: return prev
        log("onephoto.lookup", lookupDiagnostic(result).first, mapOf("via" to "live_scan"))
        return done(prev.copy(code = CodeState.Read(code, result, usda(code))))
    }

    /** A label read after the first one was skipped or failed. */
    suspend fun withLabel(prev: Capture, photo: ByteArray): Capture = done(prev.copy(label = readLabel(photo)))

    private fun done(c: Capture): Capture {
        val m = c.merged
        log("onephoto.merge", if (m.hasNutrition) "ok" else "no_nutrition", mapOf(
            "sources" to c.inputs.joinToString(",") { it.source.wire },
            "fields" to m.per100g.size.toString(),
            "disagreements" to m.per100g.values.count { it.disagreements.isNotEmpty() }.toString(),
            "flags" to m.flags.map { it.name.lowercase() }.sorted().joinToString(","),
        ))
        return c
    }

    private suspend fun lookUp(digits: String, upcE: Boolean): CodeState.Read = coroutineScope {
        val code = normalizeBarcode(digits, upcE) ?: digits
        val usda = async { usda(code) }
        val off = try {
            lookup(digits, upcE).also { log("onephoto.lookup", lookupDiagnostic(it).first, lookupDiagnostic(it).second) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("onephoto.lookup", "failed", mapOf("exception" to e.javaClass.simpleName)); null
        }
        CodeState.Read(code, off, usda.await())
    }

    private suspend fun usda(code: String): FdcBrandedFood? = try {
        fdc(code).also { log("onephoto.fdc", if (it == null) "none" else "found", emptyMap()) }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log("onephoto.fdc", "failed", mapOf("exception" to e.javaClass.simpleName)); null
    }

    private suspend fun readLabel(photo: ByteArray?): LabelRead {
        val r = when {
            photo == null -> LabelRead.NoPhoto
            reader == null -> LabelRead.Skipped("no_key", NO_KEY)
            !online() -> LabelRead.Skipped("offline", OFFLINE)
            else -> try {
                val reading = reader.invoke(photo)
                if (reading.hasPanel) LabelRead.Read(reading) else LabelRead.NoPanel(reading)
            } catch (e: CancellationException) { throw e } catch (e: NoApiKey) {
                LabelRead.Skipped("no_key", e.message ?: NO_KEY)
            } catch (_: EstimateRetryable) {
                LabelRead.Skipped("offline", OFFLINE)
            } catch (_: IOException) {
                LabelRead.Skipped("offline", OFFLINE)
            } catch (e: EstimateFailed) {
                LabelRead.Failed(e.message ?: "Couldn't read the label")
            } catch (e: Exception) {
                LabelRead.Failed("Couldn't read the label (${e.javaClass.simpleName})")
            }
        }
        log("onephoto.label", when (r) {
            is LabelRead.Read -> "read"
            is LabelRead.NoPanel -> "no_panel"
            is LabelRead.Skipped -> r.reason
            is LabelRead.Failed -> "failed"
            LabelRead.NoPhoto -> "no_photo"
        }, (r as? LabelRead.Read)?.let { mapOf("fields" to it.reading.perServing.size.toString(),
            "ingredients" to (it.reading.ingredientsText != null).toString()) } ?: emptyMap())
        return r
    }

    companion object {
        const val NO_KEY = "Add an AI key in Setup to read the label."
        const val OFFLINE = "Offline, so the label wasn't read."

        private fun offDraft(r: ScanResult?): MealEntity? = when (r) {
            is ScanResult.Known -> r.meal
            is ScanResult.Found -> r.draft
            is ScanResult.Incomplete -> r.draft
            is ScanResult.NotFound -> r.draft
            is ScanResult.Failed -> r.draft
            null -> null
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
