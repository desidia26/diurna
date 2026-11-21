package app.diurna.data.density

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeLookupsTest {
    @Test fun resultsLandOnTheCorrectIndexEvenWhenALaterItemFinishesFirst() = runTest {
        val order = mutableListOf<Int>()
        val results = arrayOfNulls<String>(3)
        RecipeLookups.forEachParallel(
            items = listOf("slow", "fast", "mid"),
            concurrency = 3,
            onEach = { index, _, result ->
                order += index
                results[index] = result
            },
        ) { item ->
            delay(when (item) { "slow" -> 50; "fast" -> 5; else -> 20 })
            item.uppercase()
        }
        assertEquals(listOf("SLOW", "FAST", "MID").toList(), results.toList())
        assertTrue("later items must be able to finish first, got $order", order.indexOf(0) > order.indexOf(1))
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test fun neverRunsMoreThanTheConcurrencyLimitAtOnce() = runTest {
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        RecipeLookups.forEachParallel(
            items = (1..12).toList(),
            concurrency = 3,
            onEach = { _, _, _ -> },
        ) { item ->
            val n = current.incrementAndGet()
            peak.accumulateAndGet(n, Math::max)
            delay(10)
            current.decrementAndGet()
            item
        }
        assertEquals(0, current.get())
        assertEquals(3, peak.get())
    }

    @Test fun eightLookupsAtFourWideTakeTwoRounds() = runTest {
        RecipeLookups.forEachParallel(
            items = (1..8).toList(),
            concurrency = 4,
            onEach = { _, _, _ -> },
        ) { item ->
            delay(100)
            item
        }
        assertEquals(200, currentTime)
    }

    @Test fun cancellingStopsWorkThatHasNotFinished() = runTest {
        val started = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            RecipeLookups.forEachParallel(
                items = (1..8).toList(),
                concurrency = 2,
                onEach = { _, _, _ -> finished.incrementAndGet() },
            ) { item ->
                if (started.incrementAndGet() == 2) ready.complete(Unit)
                delay(10_000)
                item
            }
        }
        ready.await()
        job.cancelAndJoin()
        assertEquals(2, started.get())
        assertEquals(0, finished.get())
    }

    @Test fun mapParallelPreservesInputOrder() = runTest {
        val out = RecipeLookups.mapParallel(listOf(3, 1, 2), concurrency = 3) { n ->
            delay((4 - n) * 10L)
            n * 10
        }
        assertEquals(listOf(30, 10, 20), out)
        assertEquals(30, currentTime)
    }

    @Test fun defaultConcurrencyIsThePublishedCap() {
        assertEquals(4, RecipeLookups.MAX_PARALLEL)
    }
}
