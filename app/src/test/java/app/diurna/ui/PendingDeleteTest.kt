package app.diurna.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeleteTest {
    @Test fun aDeleteCommitsOnlyAfterTheUndoWindow() = runTest {
        val committed = mutableListOf<Long>()
        val d = PendingDelete<Long>(backgroundScope, 5_000) { committed += it }
        d.start(7)
        assertEquals(listOf(7L), d.hidden.value)
        assertEquals(7L, d.undoable.value)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(emptyList<Long>(), committed)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(7L), committed)
        assertNull(d.undoable.value)
        assertEquals(emptyList<Long>(), d.hidden.value)
    }

    @Test fun undoBringsItBackAndNothingIsDeleted() = runTest {
        val committed = mutableListOf<Long>()
        val d = PendingDelete<Long>(backgroundScope, 5_000) { committed += it }
        d.start(7)
        d.undo()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(emptyList<Long>(), committed)
        assertEquals(emptyList<Long>(), d.hidden.value)
        assertNull(d.undoable.value)
    }

    @Test fun aSecondDeleteCommitsTheFirstAndOnlyTheLatestCanBeUndone() = runTest {
        val committed = mutableListOf<Long>()
        val d = PendingDelete<Long>(backgroundScope, 5_000) { committed += it }
        d.start(1)
        d.start(2)
        runCurrent()
        assertEquals(listOf(1L), committed)
        assertEquals(2L, d.undoable.value)
        d.undo()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(1L), committed)
    }

    @Test fun aFailedDeleteStopsHidingTheEntry() = runTest {
        val d = PendingDelete<Long>(backgroundScope, 5_000) { error("db gone") }
        d.start(3)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(emptyList<Long>(), d.hidden.value)
    }
}
