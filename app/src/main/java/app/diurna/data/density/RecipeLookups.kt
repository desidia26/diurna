package app.diurna.data.density

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded-parallel ingredient lookups after a recipe import.
 *
 * Each imported volume/`each` line used to run USDA → built-in list → AI, then nutrition,
 * and the next line waited for that to finish. A typical recipe therefore made many sequential
 * network calls. These helpers run at most [MAX_PARALLEL] lookups at once, apply each result as
 * it finishes (not in input order), and cancel outstanding work when the caller is cancelled.
 * Identical in-flight queries are coalesced by [InFlight] inside [IngredientDensityResolver].
 */
object RecipeLookups {
    /**
     * At most this many USDA / AI lookups in flight. Same cap as package-photo crop reads:
     * enough to hide per-call latency, low enough for typical FDC (1,000 requests/hour) and
     * AI-provider burst limits.
     */
    const val MAX_PARALLEL = 4

    /**
     * Runs [lookup] for each item, at most [concurrency] at a time. [onEach] is invoked as soon
     * as that item's lookup finishes, with the item's original [items] index, so a later item
     * can land first. [onEach] may run concurrently for different items; a caller that mutates
     * a list should tolerate that (Compose state updates on Main are serialized).
     */
    suspend fun <T, R> forEachParallel(
        items: List<T>,
        concurrency: Int = MAX_PARALLEL,
        onEach: suspend (index: Int, item: T, result: R) -> Unit,
        lookup: suspend (T) -> R,
    ) {
        if (items.isEmpty()) return
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            items.mapIndexed { index, item ->
                async {
                    val result = gate.withPermit { lookup(item) }
                    onEach(index, item, result)
                }
            }.awaitAll()
        }
    }

    /**
     * Like [forEachParallel], but returns results in input order (not completion order).
     * Used by batch callers such as the background density worker.
     */
    suspend fun <T, R> mapParallel(
        items: List<T>,
        concurrency: Int = MAX_PARALLEL,
        transform: suspend (T) -> R,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        return coroutineScope {
            items.map { item ->
                async { gate.withPermit { transform(item) } }
            }.awaitAll()
        }
    }
}

/**
 * Single-flight: concurrent [join] calls with the same key share one [block]. The first caller
 * runs it; later callers await that result. The entry is dropped when the work finishes so a
 * later miss can retry (the Room cache is what persists a hit).
 *
 * If the leader is cancelled, waiters that are themselves still active retry and may become the
 * new leader, so closing one caller does not strand the others.
 */
internal class InFlight<V> {
    private val flight = ConcurrentHashMap<String, CompletableDeferred<V>>()

    suspend fun join(key: String, block: suspend () -> V): V {
        while (true) {
            val created = CompletableDeferred<V>()
            val existing = flight.putIfAbsent(key, created)
            if (existing != null) {
                try {
                    return existing.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val value = block()
                created.complete(value)
                return value
            } catch (e: CancellationException) {
                created.cancel(e)
                throw e
            } catch (t: Throwable) {
                created.completeExceptionally(t)
                throw t
            } finally {
                flight.remove(key, created)
            }
        }
    }
}
