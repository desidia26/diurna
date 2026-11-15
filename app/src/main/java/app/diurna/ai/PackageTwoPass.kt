package app.diurna.ai

import app.diurna.ai.PackageCrops.Box
import app.diurna.domain.PackageMerge
import app.diurna.domain.ProductCode
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Two passes per package, so the label read knows what the databases already say:
 *  1. identity only (brand, name, variant, printed barcode) from each package's close-up crop, or
 *     the whole photo when the package finder sees fewer than two — a short, cheap reply;
 *  2. lookups: each barcode (printed, or decoded on the phone inside that package's box) in USDA
 *     branded and Open Food Facts; with no barcode hit, a name search (a guess at the product);
 *  3. the label read of each crop with those records as quoted data, each value marked with its
 *     source: the panel fields stay what the label shows, [PackageReading.guessed] holds estimates
 *     for what nobody has. The app then compares each panel number with the barcode-matched
 *     records per serving ([conflicts]); the model's own comparison proved unreliable.
 * The readings then merge as [PackageCrops.merge] does; the app's merge applies label > USDA
 * branded > Open Food Facts and keeps the disagreements. A pass that fails degrades to less
 * context, never to no reading: no identity → pass 2 without records; every pass 2 failing →
 * the single-pass whole-photo read.
 */
object PackageTwoPass {
    /** Lookups for pass 2; each returns nothing rather than throwing. Codes are digits as printed or decoded. */
    interface Lookups {
        suspend fun byCode(digits: String): List<KnownFacts>
        suspend fun byName(query: String): List<KnownFacts>
    }

    /** One package (crop) or the whole photo ([box] null), through both passes. */
    data class Target(
        val box: Box?,
        val identities: List<PackageIdentity>,
        val identifyFailed: Boolean,
        val known: List<KnownProduct>,
        val reading: PackagePhotoReading?,
    )

    data class Outcome(
        /** The merged reading and crop details, as a single-pass read reports them. */
        val result: PackageCrops.Outcome,
        val targets: List<Target>,
        /** Milliseconds in pass 1, the lookups and pass 2. */
        val identifyMs: Long,
        val lookupMs: Long,
        val readMs: Long,
        /** True when every pass 2 failed and the photo was read whole in one pass. */
        val singlePassFallback: Boolean,
    )

    private suspend fun <T> quiet(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** A barcode worth looking up: 8–14 digits with a valid check digit. */
    fun usableCode(digits: String?): String? = digits?.filter(Char::isDigit)?.takeIf { it.length in 8..14 && ProductCode(it).isValid() }

    suspend fun read(
        source: PackageCrops.Source,
        whole: ByteArray,
        reader: PackageReader,
        lookups: Lookups,
        /** Codes the phone decoded, with each one's horizontal centre in the photo. */
        decoded: suspend () -> List<Pair<String, Double?>> = { emptyList() },
    ): Outcome = coroutineScope {
        val detected = quiet { source.detect() }
        val plan = detected?.let(PackageCrops::plan)
        val gate = Semaphore(PackageCrops.MAX_PARALLEL)
        // The image for each target: crops cut from the full photo, or the whole photo.
        val cut = plan?.map { box -> async { box to gate.withPermit { quiet { source.cut(box.padded()) } } } }?.awaitAll()?.filter { it.second != null }
        val images: List<Pair<Box?, ByteArray>> = if (cut.isNullOrEmpty()) listOf(null to whole) else cut.map { it.first to it.second!! }
        val cropped = images.first().first != null
        var calls = 0

        // Pass 1: identity.
        var t0 = System.nanoTime()
        val ids = images.map { (box, bytes) ->
            async { gate.withPermit { quiet { reader.identify(bytes, closeUp = box != null) } } }
        }.awaitAll()
        calls += images.size
        val identifyMs = ms(t0)

        // Lookups, each key once.
        t0 = System.nanoTime()
        val codes = quiet { decoded() }.orEmpty()
        val memo = HashMap<String, Deferred<List<KnownFacts>>>()
        fun once(key: String, block: suspend () -> List<KnownFacts>) = synchronized(memo) { memo.getOrPut(key) { async { quiet { block() }.orEmpty() } } }
        val known = images.mapIndexed { i, (box, _) ->
            val found = ids[i].orEmpty()
            val picked = if (box == null) found else listOfNotNull(found.minByOrNull { abs((it.centerX ?: 0.8) - 0.5) })
            val here = codes.filter { (_, x) -> box != null && x != null && x in box.left..box.right && plan!!.count { x in it.left..it.right } == 1 }.map { it.first }
                .ifEmpty { if (box == null && picked.size == 1 && codes.size == 1) listOf(codes[0].first) else emptyList() }
            picked.map { id ->
                async {
                    val code = (listOfNotNull(usableCode(id.printedBarcode)) + here.mapNotNull(::usableCode)).distinct().take(2)
                    val byCode = code.map { c -> once("code:$c") { lookups.byCode(c) } }.awaitAll().flatten()
                    val facts = byCode.ifEmpty { id.query.takeIf { it.isNotBlank() }?.let { q -> once("name:${q.lowercase()}") { lookups.byName(q) }.await() }.orEmpty() }
                    KnownProduct(id, code.firstOrNull(), facts.take(PromptLimits.FACTS))
                }
            }.awaitAll()
        }
        val lookupMs = ms(t0)

        // Pass 2: the label read with what's known.
        t0 = System.nanoTime()
        val reads = images.mapIndexed { i, (box, bytes) ->
            async { gate.withPermit { quiet { reader.readWithKnown(bytes, closeUp = box != null, known = known[i]) } } }
        }.awaitAll()
        calls += images.size
        val readMs = ms(t0)
        val compared = reads.mapIndexed { i, r -> r?.let { withConflicts(it, known[i], images[i].first != null) } }
        val targets = images.mapIndexed { i, (box, _) -> Target(box, ids[i].orEmpty(), ids[i] == null, known[i], compared[i]) }

        val ok = compared.count { it?.products?.isNotEmpty() == true }
        val result = when {
            ok == 0 -> {
                calls += 1
                val r = reader.read(whole)
                PackageCrops.Outcome(r, detected?.size ?: 0, plan.orEmpty(), 0, PackageCrops.Fallback.CROPS_FAILED, r.products.map { null }, calls)
            }
            cropped -> {
                val m = PackageCrops.merge(images.mapIndexed { i, (box, _) -> box!! to compared[i] })
                PackageCrops.Outcome(m.reading, detected!!.size, images.map { it.first!! }, ok, null, m.origins, calls)
            }
            else -> PackageCrops.Outcome(compared[0]!!, detected?.size ?: 0, emptyList(), 0,
                if (detected == null) PackageCrops.Fallback.DETECTOR_FAILED else PackageCrops.Fallback.FEW_BOXES, compared[0]!!.products.map { null }, calls)
        }
        Outcome(result, targets, identifyMs, lookupMs, readMs, ok == 0)
    }

    /**
     * Each product's panel numbers against the record its barcode matched, per serving: a value
     * outside [PackageMerge.disagree]'s tolerance is a conflict, both numbers kept. Name-search
     * records are guesses about which product it is, so they're never compared; nor is anything
     * without a printed serving weight.
     */
    fun conflicts(p: PackageReading, k: KnownProduct?): List<LabelConflict> {
        val g = p.servingGrams?.takeIf { it > 0 } ?: return emptyList()
        val facts = k?.facts.orEmpty().filter { !it.byName }
        return p.perServing.flatMap { (key, label) ->
            facts.mapNotNull { f ->
                val known = f.per100g[key]?.let { Math.round(it * g) / 100.0 } ?: return@mapNotNull null
                LabelConflict(key, label, known, f.source).takeIf { PackageMerge.disagree(key, label, known) }
            }
        }
    }

    /** Replaces whatever conflicts the model sent with the app's own comparison. */
    private fun withConflicts(r: PackagePhotoReading, known: List<KnownProduct>, cropped: Boolean): PackagePhotoReading {
        val byCode = known.filter { it.barcode != null && it.facts.any { f -> !f.byName } }
        val centre = r.products.minByOrNull { abs((it.centerX ?: 0.8) - 0.5) }
        return r.copy(products = r.products.map { p ->
            val code = usableCode(p.printedBarcode)
            val k = byCode.firstOrNull { code != null && it.barcode?.let(::usableCode)?.trimStart('0') == code.trimStart('0') }
                // A crop's record belongs to the package in the middle of it; a whole photo's only to its only product.
                ?: byCode.singleOrNull()?.takeIf { if (cropped) p === centre else r.products.size == 1 }
            p.copy(conflicts = conflicts(p, k))
        })
    }

    private object PromptLimits { const val FACTS = PackagePrompt.MAX_KNOWN_FACTS }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
}
