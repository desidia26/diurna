package app.diurna.domain

import app.diurna.domain.SleepRules.Point
import app.diurna.domain.SleepRules.StageSpan
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The stage and sample lists on `sleep_period`, as small JSON arrays of arrays. Written by hand
 * so domain code needs no JSON library; reading skips anything malformed instead of failing.
 */
object SleepJson {
    fun stages(list: List<StageSpan>): String? =
        if (list.isEmpty()) null else list.joinToString(",", "[", "]") { "[${it.startS},${it.endS},${it.code}]" }

    fun points(list: List<Point>): String? =
        if (list.isEmpty()) null else list.joinToString(",", "[", "]") { "[${it.offsetS},${num(it.value)}]" }

    private fun num(v: Double): String =
        BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun parseStages(json: String?): List<StageSpan> = rows(json).mapNotNull { r ->
        if (r.size != 3) return@mapNotNull null
        val a = r[0].toIntOrNull(); val b = r[1].toIntOrNull(); val c = r[2].toIntOrNull()
        if (a == null || b == null || c == null) null else StageSpan(a, b, c)
    }

    fun parsePoints(json: String?): List<Point> = rows(json).mapNotNull { r ->
        if (r.size != 2) return@mapNotNull null
        val a = r[0].toIntOrNull(); val b = r[1].toDoubleOrNull()
        if (a == null || b == null) null else Point(a, b)
    }

    private fun rows(json: String?): List<List<String>> {
        val s = json?.trim() ?: return emptyList()
        if (!s.startsWith("[[") || !s.endsWith("]]")) return emptyList()
        return s.substring(2, s.length - 2).split("],[").map { row -> row.split(',').map { it.trim() } }
    }
}
