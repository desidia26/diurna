package app.diurna.ui

import app.diurna.ai.CardioReading
import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: logging cardio from a photo cancelled the AI read when the Day composable left the
 * composition (rotation after the camera, a tab redraw, folding the workout card). The import had
 * already succeeded, so the user saw nothing — no dialog, no error.
 */
class CardioPhotoReadTest {
    private val fallback = CardioPiece()
    private val piece = CardioPiece(distanceM = 687, durationS = 184.0, kcal = 28)
    private val reading = CardioReading(piece, "Race 400 meters")

    @Test fun readFinishesAfterTheCompositionScopeIsCancelled() = runTest {
        val session = CardioPhotoRead()
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        session.start(backgroundScope, setId = 7, style = Cardio.Style.ROW, fallback = fallback) {
            started.complete(Unit)
            unblock.await()
            CardioPhotoResult.Read(reading)
        }
        started.await()
        assertTrue(session.session.value!!.reading)
        // The old rememberCoroutineScope job dies when the row leaves composition.
        val composition = Job()
        composition.cancel()
        assertTrue(session.session.value!!.reading)
        assertNull(session.session.value!!.result)
        unblock.complete(Unit)
        val done = session.session.first { it?.result != null }!!
        assertFalse(done.reading)
        assertEquals(7, done.setId)
        assertEquals(Cardio.Style.ROW, done.style)
        val edit = PhotoAiReads.cardioEditFrom(done.result!!, fallback)
        assertEquals(piece, edit.piece)
        assertEquals("Race 400 meters", edit.note)
        assertTrue(edit.fromPhoto)
    }

    @Test fun problemStillOpensTheDialogWithAMessage() = runTest {
        val session = CardioPhotoRead()
        session.start(backgroundScope, setId = 3, style = Cardio.Style.TIME, fallback = fallback) {
            CardioPhotoResult.Problem(PhotoAiReads.NETWORK_PROBLEM)
        }
        val done = session.session.first { it?.result != null }!!
        val edit = PhotoAiReads.cardioEditFrom(done.result!!, fallback)
        assertEquals(fallback, edit.piece)
        assertEquals(PhotoAiReads.NETWORK_PROBLEM, edit.problem)
        assertTrue(edit.fromPhoto)
    }

    @Test fun dismissDropsAFinishedRead() = runTest {
        val session = CardioPhotoRead()
        session.start(backgroundScope, setId = 1, style = Cardio.Style.ROW, fallback = fallback) {
            CardioPhotoResult.Read(reading)
        }
        session.session.first { it?.result != null }
        session.dismiss()
        assertNull(session.session.value)
    }

    @Test fun aNewReadReplacesAnInFlightOne() = runTest {
        val session = CardioPhotoRead()
        val firstUnblock = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        session.start(backgroundScope, setId = 1, style = Cardio.Style.ROW, fallback = fallback) {
            firstStarted.complete(Unit)
            firstUnblock.await()
            CardioPhotoResult.Read(CardioReading(CardioPiece(distanceM = 1), "first"))
        }
        firstStarted.await()
        session.start(backgroundScope, setId = 2, style = Cardio.Style.ROW, fallback = fallback) {
            CardioPhotoResult.Read(reading)
        }
        firstUnblock.complete(Unit)
        val done = session.session.first { it?.result != null }!!
        assertEquals(2, done.setId)
        assertEquals(piece, (done.result as CardioPhotoResult.Read).reading.piece)
    }
}
