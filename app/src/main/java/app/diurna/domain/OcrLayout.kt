package app.diurna.domain

/** Geometry only; recognizer text is never placed in diagnostic events. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Join digit-only fragments on the same physical baseline, not arbitrary successive OCR lines.
 * UPC outer digits can be emitted in separate ML Kit blocks. Never repair letters or check digits.
 * Results ALWAYS require human confirmation, because geometry/checksums are not identity proof.
 */
fun ocrProductCodesFromLayout(input: List<OcrLine>): List<ProductCode> {
    val lines = input.take(64)
    val direct = ocrProductCodes(lines.map { it.text })
    val fragments = lines.filter { it.text.length <= 32 && it.text.matches(Regex("[0-9 \\t-]+")) &&
        it.right > it.left && it.bottom > it.top }.sortedBy { it.left }
    val assembled = mutableListOf<ProductCode>()
    for ((index, first) in fragments.withIndex()) {
        var last = first
        var text = first.text
        for (next in fragments.drop(index + 1)) {
            val h = maxOf(first.bottom - first.top, next.bottom - next.top)
            val overlap = minOf(first.bottom,next.bottom) - maxOf(first.top,next.top)
            if (overlap < h * .35 || minOf(first.bottom-first.top,next.bottom-next.top) < h * .5) continue
            val gap = next.left - last.right
            if (gap < 0 || gap > h * 2.5) continue
            text += " " + next.text
            if (text.count { it in '0'..'9' } > 13) break
            assembled += ocrProductCodes(listOf(text))
            last = next
        }
    }
    return (direct + assembled).filter { it.isValid() }.distinctBy { it.key }.take(8)
}
