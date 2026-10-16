package app.daybook.ui

import app.daybook.AppContainer
import app.daybook.data.SettingsStore
import app.daybook.steps.HealthConnectSteps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

val TEST_FAILED: LocalDateTime = LocalDateTime.MIN

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModel(private val c: AppContainer) : ScreenViewModel() {
    val porchSecret = c.settings.porchSecret.state(null)
    val porchConfirmed = c.settings.porchConfirmed.state(false)
    val armed = c.scheduler.state
    val lockWallpaper = c.settings.lockWallpaper.state(false)

    suspend fun isPorchCode(code: String): Boolean = code == c.settings.secret()
    fun confirmPorchCode() = io { c.settings.setConfirmed(); c.scheduler.reschedule() }
    fun replacePorchCode() = io { c.settings.newSecret(); c.scheduler.reschedule() }
    fun refreshAlarm() = io { c.scheduler.reschedule() }

    /** When the pending test alarm rings; null if none, or [TEST_FAILED] if it couldn't be set. */
    val testAlarmAt = MutableStateFlow<LocalDateTime?>(null)
    fun testAlarm() { testAlarmAt.value = c.scheduler.scheduleTest() ?: TEST_FAILED }

    // Targets
    val profileDraft = c.settings.profileDraft.state(SettingsStore.ProfileDraft())
    // Follows the date, so leaving the app open past midnight shows the new day's targets.
    val todayTargets = minuteTicker.map { it.toLocalDate() }.distinctUntilChanged()
        .flatMapLatest { c.nutrition.observeTargets(it) }.state(null)
    fun saveProfile(d: SettingsStore.ProfileDraft) = io {
        c.finalizer.catchUp() // finished days keep the targets they had
        c.settings.saveProfile(d)
    }

    // Steps
    val stepGoal = c.settings.stepGoal.state(10_000)
    val stepsStatus = MutableStateFlow<HealthConnectSteps.Status?>(null)
    val todaySteps = MutableStateFlow<Long?>(null)
    fun stepPermissions(): Set<String> = c.steps.permissionsToRequest()
    /** Re-checks permission and today's count; run on resume and after the permission dialog. */
    fun refreshSteps() = io {
        stepsStatus.value = c.steps.status()
        todaySteps.value = c.steps.stepsOn(java.time.LocalDate.now())
        if (stepsStatus.value != HealthConnectSteps.Status.NEEDS_PERMISSION) c.finalizer.catchUp() // fills recent days
    }
    fun setStepGoal(goal: Int) = io { c.settings.setStepGoal(goal) }

    // Food estimates
    val apiKeyLastFour = c.apiKeys.lastFour
    fun setApiKey(key: String) = io {
        if (key.isBlank()) return@io
        c.apiKeys.set(key)
        c.food.retryFailedEstimates() // entries that failed for lack of a key
    }
    fun clearApiKey() = io { c.apiKeys.clear() }

    fun setLockWallpaper(enabled: Boolean) = io {
        c.settings.setLockWallpaper(enabled) // turning on: the wallpaper's own collector redraws
        if (!enabled) c.lockWallpaper.disable()
    }
}
