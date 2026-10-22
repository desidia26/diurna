package app.diurna

import android.app.Application
import android.content.Context
import app.diurna.ai.ApiKeyStore
import app.diurna.ai.ClaudeFoodEstimator
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
import app.diurna.data.FoodRepository
import app.diurna.data.LibraryRepository
import app.diurna.ui.SelectedDate
import app.diurna.data.RoutineRepository
import app.diurna.data.SettingsStore
import app.diurna.data.StepCounter
import app.diurna.data.StepSource
import app.diurna.steps.AndroidStepSensor
import app.diurna.steps.StepSampling
import app.diurna.wallpaper.LockWallpaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    val days = DayRepository(db)
    val food = FoodRepository(db)
    val library = LibraryRepository(db)
    val backups = Backups(context, db)
    val apiKeys = ApiKeyStore(context)
    val network = NetworkMonitor(context)
    val estimates = EstimateRunner(db, ClaudeFoodEstimator({ apiKeys.get() }))
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
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as DiurnaApp).container
