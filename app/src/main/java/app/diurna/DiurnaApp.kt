package app.diurna

import android.app.Application
import android.content.Context
import app.diurna.ai.AiProvider
import app.diurna.ai.AiProviderStore
import app.diurna.ai.ApiKeyStore
import app.diurna.ai.ClaudeCardioReader
import app.diurna.ai.ClaudeFoodEstimator
import app.diurna.ai.CardioReader
import app.diurna.ai.GrokCardioReader
import app.diurna.ai.SelectedCardioReader
import app.diurna.ai.GrokFoodEstimator
import app.diurna.ai.SelectedFoodEstimator
import app.diurna.ai.EstimateRunner
import app.diurna.ai.EstimateWorker
import app.diurna.ai.NetworkMonitor
import app.diurna.alarm.AlarmScheduler
import app.diurna.checkin.CheckInReminder
import app.diurna.data.AppDatabase
import app.diurna.data.Backups
import app.diurna.data.DayFinalizer
import app.diurna.data.DayRepository
import app.diurna.data.NutritionRepository
import app.diurna.data.FoodPhotos
import app.diurna.data.FoodRepository
import app.diurna.data.LibraryRepository
import app.diurna.data.MetricsRepository
import app.diurna.data.PhotoImporter
import app.diurna.ui.SelectedDate
import app.diurna.data.RoutineRepository
import app.diurna.data.SettingsStore
import app.diurna.data.fdc.AminoResolver
import app.diurna.data.fdc.FdcClient
import app.diurna.data.StepCounter
import app.diurna.data.StepSource
import app.diurna.steps.AndroidStepSensor
import app.diurna.steps.StepSampling
import app.diurna.wallpaper.LockWallpaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    val days = DayRepository(db)
    val metrics = MetricsRepository(db)
    val foodPhotos = FoodPhotos(File(context.filesDir, "food-photos"))
    val food = FoodRepository(db, foodPhotos)
    val photoImporter = PhotoImporter(context, foodPhotos)
    val library = LibraryRepository(db)
    val backups = Backups(context, db, foodPhotos)
    val aiProvider = AiProviderStore(context)
    val apiKeys: Map<AiProvider, ApiKeyStore> = AiProvider.entries.associateWith { ApiKeyStore(context, it) }
    val usdaKey = ApiKeyStore.usda(context)
    val fdcClient = FdcClient(apiKey = { usdaKey.get() })
    val aminoResolver = AminoResolver(fdcClient, db.fdcFoodCacheDao())
    val network = NetworkMonitor(context)
    val estimates = EstimateRunner(
        db,
        SelectedFoodEstimator({ aiProvider.get() }, mapOf(
            AiProvider.ANTHROPIC to ClaudeFoodEstimator({ apiKeys.getValue(AiProvider.ANTHROPIC).get() }),
            AiProvider.XAI to GrokFoodEstimator({ apiKeys.getValue(AiProvider.XAI).get() }),
        )),
        photo = foodPhotos::read,
        detailed = { settings.detailedNutrition.first() },
        fdcKeyPresent = { usdaKey.get()?.isNotBlank() == true },
        aminoResolver = aminoResolver,
    )
    /** Reads cardio machines' summary screens for pieces, with whichever provider is selected. */
    val cardioReader: CardioReader = SelectedCardioReader({ aiProvider.get() }, mapOf(
        AiProvider.ANTHROPIC to ClaudeCardioReader({ apiKeys.getValue(AiProvider.ANTHROPIC).get() }),
        AiProvider.XAI to GrokCardioReader({ apiKeys.getValue(AiProvider.XAI).get() }),
    ))
    val selectedDate = SelectedDate()
    val nutrition = NutritionRepository(db, settings.profile)
    val stepSensor = AndroidStepSensor(context)
    val stepCounter = StepCounter(db, stepSensor, settings, bootedAt = { AndroidStepSensor.bootedAt() })
    val steps: StepSource = stepCounter
    val finalizer = DayFinalizer(db, days, nutrition, steps)
    val routine = RoutineRepository(db, days, finalizer)
    val scheduler = AlarmScheduler(context, days, settings)
    val lockWallpaper = LockWallpaper(context, db, days, nutrition, steps, settings)

    init {
        days.onAlarmInputsChanged = { scheduler.reschedule() }
        days.onRatingChanged = { if (days.isRated(it)) CheckInReminder.dismiss(context) }
        food.onPendingEstimates = { EstimateWorker.enqueue(context) }
        lockWallpaper.start(appScope)
    }
}

class DiurnaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PackageRename.cleanUpOnce(this) // before the container schedules anything
        container = AppContainer(this)
        container.appScope.launch {
            container.settings.ensureSecret()
            container.finalizer.catchUp()
            EstimateWorker.enqueue(this@DiurnaApp) // anything logged while offline
            StepSampling.schedule(this@DiurnaApp)
            CheckInReminder.schedule(this@DiurnaApp)
            container.backups.scheduleDaily()
            container.scheduler.reschedule()
            container.food.sweepPhotos() // photos from abandoned drafts or deleted entries
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as DiurnaApp).container
