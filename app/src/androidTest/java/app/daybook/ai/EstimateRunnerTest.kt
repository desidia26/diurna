package app.daybook.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.daybook.data.TestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EstimateRunnerTest {
    private val t = TestDb()

    /** Scripted estimator: answers from [replies] by text, or throws what's queued in [failNext]. */
    private class Fake : FoodEstimator {
        override val model = "fake-model"
        val calls = mutableListOf<String>()
        var failNext: Exception? = null
        var beforeReturn: suspend () -> Unit = {}
        override suspend fun estimate(text: String): FoodEstimate {
            calls += text
            failNext?.let { failNext = null; throw it }
            beforeReturn()
            return FoodEstimate(text.length * 10, text.length * 20, 1, 2, 3, "note for $text")
        }
    }

    private val fake = Fake()
    private val runner = EstimateRunner(t.db, fake)

    @After fun tearDown() = t.close()

    private suspend fun entries() = t.food.observe(t.mon).first()

    @Test fun estimatesEveryPendingEntry() = runTest {
        t.food.add(t.mon, "eggs")
        t.food.add(t.mon, "burrito")
        assertEquals(EstimateRunner.Outcome.DONE, runner.runPending())
        val e = entries().first { it.text == "eggs" }
        assertEquals(40, e.kcalLow)
        assertEquals(80, e.kcalHigh)
        assertEquals("note for eggs", e.estimateNote)
        assertEquals("fake-model", e.estimateModel)
        assertNotNull(e.estimatedAt)
        assertEquals(2, fake.calls.size)
        // Nothing left: a second run makes no calls.
        runner.runPending()
        assertEquals(2, fake.calls.size)
    }

    @Test fun networkTroubleLeavesItPendingForRetry() = runTest {
        t.food.add(t.mon, "eggs")
        fake.failNext = IOException("offline")
        assertEquals(EstimateRunner.Outcome.RETRY, runner.runPending())
        assertNull(entries().single().estimatedAt)
        assertNull(entries().single().estimateError)
        assertEquals(EstimateRunner.Outcome.DONE, runner.runPending())
        assertNotNull(entries().single().estimatedAt)
    }

    @Test fun lastAttemptTurnsTemporaryTroubleIntoAVisibleFailure() = runTest {
        t.food.add(t.mon, "eggs")
        t.food.add(t.mon, "toast")
        fake.failNext = EstimateRetryable()
        assertEquals(EstimateRunner.Outcome.DONE, runner.runPending(giveUp = true))
        assertEquals("every waiting entry says so", listOf(EstimateErrors.GAVE_UP, EstimateErrors.GAVE_UP), entries().map { it.estimateError })
        t.food.retryFailedEstimates()
        runner.runPending()
        assertNotNull(entries().first().estimatedAt)
    }

    @Test fun unexpectedErrorFailsThatEntryAndCarriesOn() = runTest {
        t.food.add(t.mon, "eggs")
        t.food.add(t.mon, "toast")
        fake.failNext = IllegalStateException("bug")
        assertEquals(EstimateRunner.Outcome.DONE, runner.runPending())
        val (eggs, toast) = entries().sortedBy { it.id }
        assertEquals("Unexpected error (IllegalStateException). Tap Retry.", eggs.estimateError)
        assertNotNull("the next entry still got estimated", toast.estimatedAt)
    }

    @Test fun permanentFailureIsShownAndNotRetriedUntilAsked() = runTest {
        t.food.add(t.mon, "eggs")
        fake.failNext = NoApiKey()
        runner.runPending()
        assertEquals("Add an Anthropic API key in Setup to estimate food", entries().single().estimateError)
        runner.runPending()
        assertEquals("no retry on its own", 1, fake.calls.size)

        t.food.retryFailedEstimates() // e.g. after adding a key
        runner.runPending()
        assertNull(entries().single().estimateError)
        assertNotNull(entries().single().estimatedAt)
    }

    @Test fun editingTextReEstimatesButChangingTimeDoesNot() = runTest {
        t.food.add(t.mon, "burrito")
        runner.runPending()
        val id = entries().single().id

        t.food.edit(id, "burrito", "13:15")
        assertEquals("13:15", entries().single().time)
        assertNotNull("time-only edit keeps the estimate", entries().single().estimatedAt)

        t.food.edit(id, "burrito, double chicken", "13:15")
        assertNull("text edit clears it", entries().single().kcalLow)
        runner.runPending()
        assertEquals(230, entries().single().kcalLow)
    }

    @Test fun editDuringAnEstimateDoesNotKeepTheStaleNumbers() = runTest {
        t.food.add(t.mon, "rice")
        val id = entries().single().id
        // The text changes while the call for "rice" is in flight.
        fake.beforeReturn = { fake.beforeReturn = {}; t.food.edit(id, "rice and beans", "12:00") }
        runner.runPending()
        val e = entries().single()
        assertEquals("rice and beans", e.text)
        assertEquals("estimated from the new text", 140, e.kcalLow)
        assertEquals(listOf("rice", "rice and beans"), fake.calls)
    }

    @Test fun apiKeyRoundTripsThroughTheKeystore() {
        val keys = ApiKeyStore(InstrumentationRegistry.getInstrumentation().targetContext)
        keys.clear()
        assertNull(keys.get())
        keys.set("  sk-ant-test-1234567890abcd ")
        assertEquals("sk-ant-test-1234567890abcd", keys.get())
        assertEquals("abcd", keys.lastFour.value)
        keys.clear()
        assertNull(keys.get())
        assertNull(keys.lastFour.value)
    }
}
