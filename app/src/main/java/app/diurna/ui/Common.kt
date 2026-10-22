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
abstract class ScreenViewModel : ViewModel() {
    protected fun io(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    protected fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}

/** Activity-scoped ViewModel built from the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(c: AppContainer, crossinline make: (AppContainer) -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { make(c) } })
