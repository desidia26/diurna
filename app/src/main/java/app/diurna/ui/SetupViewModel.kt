package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.ai.AiProvider
import app.diurna.ai.BackfillWorkState
import app.diurna.ai.DetailBackfill
import app.diurna.ai.DetailBackfillRunner
import app.diurna.ai.DetailBackfillWorker
import app.diurna.ai.GatewaySettings
import app.diurna.data.SettingsStore
import app.diurna.wallpaper.LockStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
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
    fun setStepGoal(goal: Int) = io { c.settings.setStepGoal(goal) }
    val stepSensorPresent get() = c.stepSensor.present()
    val stepsPermitted = MutableStateFlow(false)
    val todaySteps = MutableStateFlow<Long?>(null)
    /** Re-checks permission and takes a look; run on resume and after the permission prompt. */
    fun refreshSteps() = io {
        stepsPermitted.value = c.stepSensor.permitted()
        todaySteps.value = c.steps.stepsOn(java.time.LocalDate.now())
    }

    // Backups
    val lastBackup = c.backups.last
    fun backupNow() = io { c.backups.backupNow() }

    // Food estimates
    val aiProvider = c.aiProvider.selected
    val gateway = c.gateway.snapshot
    fun apiKeyLastFour(provider: AiProvider) = c.apiKeys.getValue(provider).lastFour
    fun setAiProvider(provider: AiProvider) = io {
        if (provider == c.aiProvider.get()) return@io
        c.aiProvider.set(provider)
        // Entries that failed on the old provider (e.g. no key) get a try on the new one.
        if (providerReady(provider)) c.food.retryFailedEstimates()
    }
    fun setApiKey(provider: AiProvider, key: String) = io {
        if (key.isBlank()) return@io
        c.apiKeys.getValue(provider).set(key)
        if (provider == c.aiProvider.get()) c.food.retryFailedEstimates() // entries that failed for lack of a key
    }
    fun clearApiKey(provider: AiProvider) = io { c.apiKeys.getValue(provider).clear() }

    // USDA FoodData Central (amino acids)
    val usdaKeyLastFour = c.usdaKey.lastFour
    fun setUsdaKey(key: String) = io {
        if (key.isBlank()) return@io
        c.usdaKey.set(key)
    }
    fun clearUsdaKey() = io { c.usdaKey.clear() }

    fun saveGateway(transform: (GatewaySettings) -> GatewaySettings) = io {
        c.gateway.update(transform)
        if (providerReady(c.aiProvider.get())) c.food.retryFailedEstimates()
    }

    private fun providerReady(provider: AiProvider): Boolean = when (provider) {
        AiProvider.OPENAI_COMPAT -> {
            val g = c.gateway.get()
            g.openAiBaseUrl.isNotBlank() && g.openAiModel.isNotBlank()
        }
        else -> c.apiKeys.getValue(provider).get() != null
    }

    val lockStyle = c.settings.lockStyle.state(LockStyle.AUTO)
    fun setLockStyle(style: LockStyle) = io { c.settings.setLockStyle(style) } // the wallpaper's collector redraws
    suspend fun lockPreview(style: LockStyle) = withContext(Dispatchers.Default) { c.lockWallpaper.preview(style, 360) }

    fun setLockWallpaper(enabled: Boolean) = io {
        c.settings.setLockWallpaper(enabled) // turning on: the wallpaper's own collector redraws
        if (!enabled) c.lockWallpaper.disable()
    }

    // Detail backfill (sub-macros / aminos for past entries)
    val backfillState = DetailBackfillWorker.observe(c.appContext).state(BackfillWorkState.Idle as BackfillWorkState)
    val backfillPreview = MutableStateFlow<DetailBackfill.Preview?>(null)
    var backfillRange: DetailBackfill.Range = DetailBackfill.Range.lastDays(LocalDate.now())
        private set

    fun setBackfillRange(range: DetailBackfill.Range) {
        backfillRange = range
        refreshBackfillPreview()
    }

    fun refreshBackfillPreview() = io {
        val runner = DetailBackfillRunner(
            db = c.db,
            estimator = c.foodEstimator,
            photo = c.foodPhotos::read,
            fdcKeyPresent = { c.usdaKey.get()?.isNotBlank() == true },
            aminoResolver = c.aminoResolver,
        )
        backfillPreview.value = runner.preview(backfillRange.fromKey(), backfillRange.toKey())
    }

    fun startBackfill() {
        DetailBackfillWorker.enqueue(c.appContext, backfillRange.fromKey(), backfillRange.toKey())
    }

    fun cancelBackfill() {
        DetailBackfillWorker.cancel(c.appContext)
    }
}
