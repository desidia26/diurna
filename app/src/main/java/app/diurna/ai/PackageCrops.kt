package app.diurna.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Close-up reads of a photo of several packages. Read whole, a counter photo of five or six
 * packages comes back with misread panel lines, invented numbers and one bag listed twice, and the
 * model's own boxes are too loose to crop by. So an on-device object detector (ML Kit's bundled
 * localizer on the phone, the same model's recorded boxes in the eval harness) finds each package,
 * [plan] turns its boxes into crops, each crop is read on its own, and [merge] puts the readings
 * back together as one row per package. Fewer than two packages found means the photo is read
 * whole, as before. Pure geometry, merging and orchestration; detecting, cutting and the AI calls
 * are the caller's ([Source], [read]'s reader).
 */
object PackageCrops {
    /** A box smaller than this fraction of the photo is a detail (a sticker, a barcode), not a package. */
    const val MIN_AREA = 0.02
    /** Boxes overlapping more than this (intersection over union) are the same package. */
    const val SAME_IOU = 0.5
    /** A box with at least this much of its area inside another box is part of it. */
    const val INSIDE = 0.8
    /** Each crop reaches this far past its box on every side, as a fraction of the box's size. */
    const val PAD = 0.04
    /** At most this many packages are read one by one (ML Kit's detector finds at most five). */
    const val MAX_CROPS = 5
    /** At most this many crop reads in flight at once. */
    const val MAX_PARALLEL = 4

    /** A rectangle of the upright photo, as fractions 0–1 of its width and height. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
        val area: Double get() = max(0.0, width) * max(0.0, height)
        val centerX: Double get() = (left + right) / 2
        val centerY: Double get() = (top + bottom) / 2

        fun clamped() = Box(left.coerceIn(0.0, 1.0), top.coerceIn(0.0, 1.0), right.coerceIn(0.0, 1.0), bottom.coerceIn(0.0, 1.0))

        fun intersection(o: Box): Double = max(0.0, min(right, o.right) - max(left, o.left)) * max(0.0, min(bottom, o.bottom) - max(top, o.top))

        fun iou(o: Box): Double = intersection(o).let { i -> if (i <= 0) 0.0 else i / (area + o.area - i) }

        /** The fraction of this box that lies inside [o]. */
        fun insideOf(o: Box): Double = if (area <= 0) 0.0 else intersection(o) / area

        /** The crop for this box: [PAD] of its size added on each side, kept inside the photo. */
        fun padded(pad: Double = PAD) = Box(left - width * pad, top - height * pad, right + width * pad, bottom + height * pad).clamped()

        /** Left, top, right, bottom in pixels of a [w]×[h] image, at least 1 px each way and inside it. */
        fun pixels(w: Int, h: Int): IntArray {
            val l = (left * w).roundToInt().coerceIn(0, w - 1)
            val t = (top * h).roundToInt().coerceIn(0, h - 1)
            val r = (right * w).roundToInt().coerceIn(l + 1, w)
            val b = (bottom * h).roundToInt().coerceIn(t + 1, h)
            return intArrayOf(l, t, r, b)
        }

        override fun toString() = "x ${pct(left)}–${pct(right)}%, y ${pct(top)}–${pct(bottom)}%"
        private fun pct(v: Double) = (v * 100).roundToInt()
    }

    /** Finds the packages in the photo being read and cuts one out (null if it can't be cut). */
    interface Source {
        /** The detector's boxes in the upright photo, in any order. */
        suspend fun detect(): List<Box>
        /** [box] cut from the full-resolution photo, sized for the AI ([cropTarget]). */
        suspend fun cut(box: Box): ByteArray?
    }

    /** Why the whole photo was read instead of crops. */
    enum class Fallback { FEW_BOXES, DETECTOR_FAILED, CROPS_FAILED }

    /**
     * The packages to read one by one, top row first and left to right within a row, or null when
     * fewer than two remain (one package, or nothing found: read the photo whole). Tiny boxes are
     * ignored; a box around two or more others is a group and dropped in favour of its parts; a
     * box inside one other box is a detail of it and dropped; of near-duplicates the larger stays.
     */
    fun plan(detected: List<Box>): List<Box>? {
        var boxes = detected.map { it.clamped() }.filter { it.area >= MIN_AREA }
        boxes = boxes.filter { b -> boxes.count { o -> o !== b && o.insideOf(b) >= INSIDE } < 2 }
        boxes = boxes.filter { b -> boxes.none { o -> o !== b && b.insideOf(o) >= INSIDE && o.area > b.area } }
        val kept = mutableListOf<Box>()
        for (b in boxes.sortedByDescending { it.area }) if (kept.none { it.iou(b) > SAME_IOU || (b.insideOf(it) >= INSIDE) }) kept += b
        if (kept.size < 2) return null
        return readingOrder(kept.take(MAX_CROPS))
    }

    /** Rows by vertical centre (a box joins a row when its centre lies within the row's first box), each left to right. */
    private fun readingOrder(boxes: List<Box>): List<Box> {
        val rows = mutableListOf<MutableList<Box>>()
        for (b in boxes.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && b.centerY in row[0].top..row[0].bottom) row += b else rows += mutableListOf(b)
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    /**
     * How to cut [box] out of a [w]×[h] photo so its long side is at most [maxEdge] px (never
     * upscaled): the size to decode the whole photo at, then the crop in that size's pixels.
     * Returns targetWidth, targetHeight, left, top, right, bottom.
     */
    fun cropTarget(w: Int, h: Int, box: Box, maxEdge: Int): IntArray {
        val long = maxOf(box.width * w, box.height * h)
        val s = if (long <= maxEdge) 1.0 else maxEdge / long
        val tw = (w * s).roundToInt().coerceAtLeast(1)
        val th = (h * s).roundToInt().coerceAtLeast(1)
        return intArrayOf(tw, th) + box.pixels(tw, th)
    }

    /** One row per package; [origins] is the box each row was read from, by index. */
    data class Merged(val reading: PackagePhotoReading, val origins: List<Box>)

    /**
     * Each crop's package is the product nearest the crop's centre (a crop can show the edge of a
     * neighbour), placed at the box's centre so barcodes in the photo still pair with it. The same
     * product read from two crops (same printed barcode, or same name with matching calories) is
     * one row, keeping the read with more panel values. A crop that failed or saw nothing is skipped.
     */
    fun merge(reads: List<Pair<Box, PackagePhotoReading?>>): Merged {
        val rows = mutableListOf<PackageReading>()
        val origins = mutableListOf<Box>()
        val questions = mutableListOf<PackageQuestion>()
        val notes = linkedSetOf<String>()
        for ((box, r) in reads) {
            if (r == null || r.products.isEmpty()) continue
            val pick = r.products.indices.minWith(compareBy<Int> { abs((r.products[it].centerX ?: 0.8) - 0.5) }.thenByDescending { r.products[it].perServing.size })
            val p = r.products[pick].copy(centerX = box.centerX)
            val same = rows.indexOfFirst { sameProduct(it, p) }
            val at = if (same < 0) { rows += p; origins += box; rows.lastIndex } else {
                if (richness(p) > richness(rows[same])) { rows[same] = p; origins[same] = box }
                same
            }
            r.questions.filter { it.productIndex == pick || it.productIndex == null }.forEach { q ->
                if (questions.none { it.productIndex == at && it.text == q.text }) questions += q.copy(productIndex = at)
            }
            r.note.trim().takeIf { it.isNotEmpty() }?.let { notes += it }
        }
        return Merged(PackagePhotoReading(rows, questions, notes.joinToString(" ").take(500)), origins)
    }

    private fun richness(p: PackageReading) = p.perServing.size * 4 + (if (p.hasPanel) 2 else 0) + (if (p.ingredientsText != null) 1 else 0)

    private fun identity(p: PackageReading) = "${p.brand} ${p.productName}".lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun sameProduct(a: PackageReading, b: PackageReading): Boolean {
        val ca = a.printedBarcode?.filter(Char::isDigit).orEmpty()
        val cb = b.printedBarcode?.filter(Char::isDigit).orEmpty()
        if (ca.length >= 8 && cb.length >= 8) return ca == cb
        if (identity(a).isEmpty() || identity(a) != identity(b)) return false
        val ka = a.perServing["kcal"]; val kb = b.perServing["kcal"]
        return ka == null || kb == null || abs(ka - kb) <= 0.1 * max(ka, kb)
    }

    /** What [read] did: the reading, how many boxes the detector gave, the crops read, and why it fell back (null: it didn't). */
    data class Outcome(
        val reading: PackagePhotoReading,
        val detected: Int,
        val crops: List<Box>,
        val read: Int,
        val fallback: Fallback?,
        /** Per row of [reading], the crop it was read from (null when the photo was read whole). */
        val origins: List<Box?>,
        /** AI calls made. */
        val calls: Int,
    )

    /**
     * Detect, plan, read each crop with [closeUp] (else [reader]; at most [MAX_PARALLEL] at once) and merge; or read [whole] when
     * fewer than two packages are found, the detector fails, or every crop read fails. An error from
     * the whole-photo read is the caller's, as it was before crops.
     */
    suspend fun read(
        source: Source,
        whole: ByteArray,
        closeUp: (suspend (ByteArray) -> PackagePhotoReading)? = null,
        reader: suspend (ByteArray) -> PackagePhotoReading,
    ): Outcome = coroutineScope {
        val readCrop = closeUp ?: reader
        val detected = try { source.detect() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val plan = detected?.let(::plan)
        suspend fun wholeRead(why: Fallback, crops: List<Box>, calls: Int): Outcome {
            val r = reader(whole)
            return Outcome(r, detected?.size ?: 0, crops, 0, why, r.products.map { null }, calls + 1)
        }
        if (plan == null) return@coroutineScope wholeRead(if (detected == null) Fallback.DETECTOR_FAILED else Fallback.FEW_BOXES, emptyList(), 0)
        val gate = Semaphore(MAX_PARALLEL)
        val reads = plan.map { box ->
            async {
                gate.withPermit {
                    val bytes = try { source.cut(box.padded()) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    box to bytes?.let { b -> try { readCrop(b) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                }
            }
        }.awaitAll()
        val read = reads.count { it.second?.products?.isNotEmpty() == true }
        if (read == 0) return@coroutineScope wholeRead(Fallback.CROPS_FAILED, plan, plan.size)
        val m = merge(reads)
        Outcome(m.reading, detected.size, plan, read, null, m.origins, plan.size)
    }
}
