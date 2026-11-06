package app.diurna.domain

/** Own one instance per camera binding/retry; no callbacks from a cancelled binding may revive it. */
class LiveScanSession(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private enum class State { LIVE, CAPTURING, FINISHED, CANCELLED }
    private var state = State.LIVE
    private val seen = linkedMapOf<String, Pair<ProductCode, Long>>()
    private var captured = emptyList<ProductCode>()
    private fun expire() { val now = clock(); seen.entries.removeAll { now - it.value.second > 5_000 } }

    @Synchronized fun observe(codes: List<ProductCode>): Boolean {
        if (state != State.LIVE) return false
        expire()
        codes.take(32).filter { it.isValid() }.forEach { code ->
            code.key?.let { key -> if (key in seen || seen.size < 8) seen[key] = code to clock() }
        }
        return seen.isNotEmpty()
    }
    @Synchronized fun beginCapture(): Boolean {
        if (state != State.LIVE) return false
        expire(); captured = seen.values.map { it.first }; state = State.CAPTURING
        return true
    }
    @Synchronized fun cancel() { state = State.CANCELLED; seen.clear(); captured = emptyList() }
    @Synchronized fun isActive() = state == State.LIVE || state == State.CAPTURING
    @Synchronized fun canAnalyze() = state == State.LIVE

    /** Every live-only fallback requires explicit confirmation, even repeated matching reads. */
    @Synchronized fun complete(still: ScanOutcome): ScanResolution? {
        if (state != State.CAPTURING) return null
        state = State.FINISHED
        val photo = when (still) { is ScanOutcome.Decoded -> listOf(still.code); is ScanOutcome.Confirm -> still.codes; else -> emptyList() }
            .filter { it.isValid() }
        val all = (captured + photo).distinctBy { it.key }.take(8)
        val result = when {
            all.isEmpty() -> if (still is ScanOutcome.Decoded || still is ScanOutcome.Confirm) ScanOutcome.Unreadable else still
            still is ScanOutcome.Decoded && all.size == 1 -> ScanOutcome.Decoded(all.single())
            else -> ScanOutcome.Confirm(all)
        }
        val source = when {
            photo.isEmpty() && all.isNotEmpty() -> "live_confirmation"
            photo.isNotEmpty() && captured.any { live -> photo.none { it.key == live.key } } -> "combined_confirmation"
            photo.isNotEmpty() -> "saved_photo"
            else -> "none"
        }
        seen.clear(); captured = emptyList()
        return ScanResolution(result, source)
    }
}

data class ScanResolution(val outcome: ScanOutcome, val source: String)
