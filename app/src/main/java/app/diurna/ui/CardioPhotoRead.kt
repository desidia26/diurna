package app.diurna.ui

import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * An in-flight or finished cardio photo read, kept outside the composable that started it.
 *
 * [PieceRow] used to launch the AI call in [rememberCoroutineScope]. Returning from the camera,
 * rotating the phone, or folding the workout card disposes that scope and cancelled the read
 * with no dialog and no error — the photo had already been imported. This session lives as long
 * as the [CoroutineScope] passed to [start], which should be the Day ViewModel's scope.
 */
class CardioPhotoRead {
    data class Session(
        val setId: Long,
        val style: Cardio.Style,
        val fallback: CardioPiece,
        val reading: Boolean,
        val result: CardioPhotoResult? = null,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session
    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
        setId: Long,
        style: Cardio.Style,
        fallback: CardioPiece,
        read: suspend () -> CardioPhotoResult,
    ) {
        job?.cancel()
        _session.value = Session(setId, style, fallback, reading = true)
        val started = _session.value
        job = scope.launch {
            try {
                val result = read()
                if (_session.value === started) {
                    _session.value = Session(setId, style, fallback, reading = false, result = result)
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        _session.value = null
    }
}
