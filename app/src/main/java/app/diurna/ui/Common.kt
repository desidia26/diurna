package app.diurna.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.diurna.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** Re-evaluates time-based state (locks, "today", morning status) once a minute. */
val minuteTicker: Flow<LocalDateTime> = flow {
    while (true) {
        emit(LocalDateTime.now())
        delay(60_000)
    }
}

/** The day the Day and Week tabs are looking at; shared so picking a day in Week opens it in Day. */
class SelectedDate {
    val value = MutableStateFlow(LocalDate.now())
    fun shift(days: Long) { value.value = value.value.plusDays(days) }
    fun goTo(d: LocalDate) { value.value = d }
}

/** Base for the screen ViewModels: fire-and-forget writes and a shorthand for exposing flows. */
abstract class ScreenViewModel(private val actionStore: app.diurna.debug.Store? = null) : ViewModel() {
    protected fun io(name: String = "screen.write", block: suspend () -> Unit) {
        val id = java.util.UUID.randomUUID().toString()
        val screen = runCatching { app.diurna.debug.Screen.valueOf(javaClass.simpleName.removeSuffix("ViewModel").uppercase()) }.getOrDefault(app.diurna.debug.Screen.BACKGROUND)
        fun record(result: app.diurna.debug.Action.Result, latency: Long? = null) {
            actionStore?.dispatch(app.diurna.debug.Action.Event(screen, name, id, result, latency))
        }
        record(app.diurna.debug.Action.Result.STARTED)
        viewModelScope.launch {
            val start = System.nanoTime()
            try { block(); record(app.diurna.debug.Action.Result.OK, (System.nanoTime() - start) / 1_000_000) }
            catch (e: kotlin.coroutines.cancellation.CancellationException) { record(app.diurna.debug.Action.Result.CANCELLED); throw e }
            catch (e: Exception) { record(app.diurna.debug.Action.Result.FAILED); throw e }
        }
    }

    protected fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}

/** Activity-scoped ViewModel built from the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(c: AppContainer, crossinline make: (AppContainer) -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { make(c) } })
