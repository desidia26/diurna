package app.daybook

import android.app.Application
import android.content.Context
import app.daybook.ai.ApiKeyStore
import app.daybook.ai.ClaudeFoodEstimator
import app.daybook.ai.EstimateRunner
import app.daybook.ai.EstimateWorker
import app.daybook.ai.NetworkMonitor
import app.daybook.alarm.AlarmScheduler
import app.daybook.data.AppDatabase
import app.daybook.data.DayFinalizer
import app.daybook.data.DayRepository
import app.daybook.data.NutritionRepository
import app.daybook.data.FoodRepository
import app.daybook.data.LibraryRepository
import app.daybook.ui.SelectedDate
import app.daybook.data.RoutineRepository
import app.daybook.data.SettingsStore
import app.daybook.wallpaper.LockWallpaper
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
    val apiKeys = ApiKeyStore(context)
    val network = NetworkMonitor(context)
    val estimates = EstimateRunner(db, ClaudeFoodEstimator({ apiKeys.get() }))
    val selectedDate = SelectedDate()
    val nutrition = NutritionRepository(db, settings.profile)
    val finalizer = DayFinalizer(db, days, nutrition)
    val routine = RoutineRepository(db, days, finalizer)
    val scheduler = AlarmScheduler(context, days, settings)
    val lockWallpaper = LockWallpaper(context, db, days, nutrition, settings)

    init {
        days.onAlarmInputsChanged = { scheduler.reschedule() }
        food.onPendingEstimates = { EstimateWorker.enqueue(context) }
        lockWallpaper.start(appScope)
    }
}

class DaybookApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.settings.ensureSecret()
            container.finalizer.catchUp()
            EstimateWorker.enqueue(this@DaybookApp) // anything logged while offline
            container.scheduler.reschedule()
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as DaybookApp).container
