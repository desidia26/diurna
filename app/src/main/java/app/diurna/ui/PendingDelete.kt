package app.diurna.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Delete with Undo, without a soft-delete column: the item is hidden at once and only really
 * deleted when the window ends or the next delete starts. If the app dies inside the window the
 * item survives, which is the safe way round.
 */
class PendingDelete<T>(
    private val scope: CoroutineScope,
    private val windowMs: Long = 5_000,
    private val commit: suspend (T) -> Unit,
) {
    private val _hidden = MutableStateFlow<List<T>>(emptyList())
    /** Items to leave out of lists: waiting for the window, or being deleted right now. */
    val hidden: StateFlow<List<T>> = _hidden
    private val _undoable = MutableStateFlow<T?>(null)
    /** The one Undo would bring back. */
    val undoable: StateFlow<T?> = _undoable
    private var timer: Job? = null

    fun start(item: T) {
        timer?.cancel()
        _undoable.value?.let(::release)
        _hidden.update { it + item }
        _undoable.value = item
        timer = scope.launch {
            delay(windowMs)
            if (_undoable.compareAndSet(item, null)) release(item)
        }
    }

    fun undo() {
        val item = _undoable.value ?: return
        timer?.cancel()
        _undoable.value = null
        _hidden.update { it - item }
    }

    // Stays hidden until the delete has landed, so the row doesn't flash back; a failed delete shows it again.
    private fun release(item: T) {
        scope.launch {
            try { commit(item) } catch (_: Exception) {} finally { _hidden.update { it - item } }
        }
    }
}
