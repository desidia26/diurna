package app.daybook.wallpaper

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.WindowManager
import app.daybook.container
import app.daybook.data.AppDatabase
import app.daybook.data.DayRepository
import app.daybook.data.NutritionRepository
import app.daybook.data.key
import app.daybook.data.StepSource
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import app.daybook.data.SettingsStore
import app.daybook.domain.AlarmRules
import app.daybook.domain.AlarmRules.Morning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Draws today's summary as the lock-screen-only wallpaper. Redraws when the data changes
 * (while the app process is alive) and at 10pm and midnight via a non-waking alarm, which
 * is delivered the next time the screen turns on.
 */
class LockWallpaper(
    private val context: Context,
    private val db: AppDatabase,
    private val days: DayRepository,
    private val nutrition: NutritionRepository,
    private val steps: StepSource,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()
    private var lastDrawn: LockSnapshot? = null
    private val wallpapers = WallpaperManager.getInstance(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) = scope.launch {
        // Targets come from the profile, which lives in settings rather than the database.
        combine(settings.lockWallpaper, db.invalidationTracker.createFlow(*TABLES), settings.profile) { enabled, _, _ -> enabled }
            .debounce(2_000) // typing into a set field writes on every keystroke
            .collectLatest { enabled ->
                if (enabled) {
                    refresh()
                    // Steps change all day without any app event, so redraw on a schedule too.
                    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        STEPS_WORK, ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<LockWallpaperStepsWorker>(15, TimeUnit.MINUTES).build(),
                    )
                } else {
                    WorkManager.getInstance(context).cancelUniqueWork(STEPS_WORK)
                }
            }
    }

    suspend fun refresh() = mutex.withLock {
        if (!settings.lockWallpaper.first()) return@withLock
        try {
            val (w, h) = screenSize()
            val today = LocalDateTime.now().toLocalDate()
            val snapshot = buildSnapshot(
                db, days, targets = nutrition.observeTargets(today).first(),
                // The saved count, not a fresh sensor look: looking writes to the database, which
                // would trigger another redraw. The 15-minute sampler does the looking.
                steps = db.dayDao().get(today.key())?.steps, stepGoal = settings.stepGoal.first(),
            )
            // Setting a wallpaper makes the lock screen flash, so only do it when the picture changes.
            if (snapshot != lastDrawn) {
                wallpapers.setBitmap(draw(snapshot, w, h), null, true, WallpaperManager.FLAG_LOCK)
                lastDrawn = snapshot
                Log.d("DaybookLock", "lock screen redrawn")
            }
        } catch (e: Exception) {
            Log.w("Daybook", "Lock wallpaper update failed", e)
        }
        scheduleNextTick()
    }

    /** Turning it off hands the lock screen back to the home wallpaper. */
    suspend fun disable() = mutex.withLock {
        lastDrawn = null
        alarms.cancel(tickIntent())
        WorkManager.getInstance(context).cancelUniqueWork(STEPS_WORK)
        try {
            wallpapers.clear(WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) {
            Log.w("Daybook", "Clearing lock wallpaper failed", e)
        }
    }

    /**
     * The summary's text changes on its own at the alarm time (upcoming → not scanned), when the
     * ring window ends (→ missed), at 10pm (tomorrow's alarm appears) and at midnight.
     */
    @SuppressLint("MissingPermission") // USE_EXACT_ALARM is granted at install
    private suspend fun scheduleNextTick() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val candidates = mutableListOf(today.atTime(AlarmRules.LOCK_HOUR, 0), today.plusDays(1).atStartOfDay())
        val alarm = days.effectiveAlarm(today)
        if (alarm != AlarmRules.OFF) {
            val alarmAt = today.atTime(LocalTime.parse(alarm))
            candidates += alarmAt
            candidates += alarmAt.plusMinutes(AlarmRules.RING_MINUTES)
        }
        val next = candidates.filter { it.isAfter(now) }.min()
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            alarms.setExact(AlarmManager.RTC, at, tickIntent())
        } catch (e: SecurityException) {
            alarms.set(AlarmManager.RTC, at, tickIntent())
        }
    }

    private fun tickIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, LockWallpaperTickReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private companion object {
        const val STEPS_WORK = "lock-wallpaper-steps"
        val TABLES = arrayOf("day", "day_workout", "logged_exercise", "exercise_set", "food_entry", "routine_day", "workout_template")
    }
}

/** Redraws the lock screen every 15 minutes so the step count stays current. */
class LockWallpaperStepsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.lockWallpaper.refresh()
        return Result.success()
    }
}

class LockWallpaperTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                c.finalizer.catchUp()
                c.lockWallpaper.refresh()
            } finally { pending.finish() }
        }
    }
}

// ---- Drawing ----

private const val BG_TOP = 0xFF0E0E11.toInt()
private const val BG_BOTTOM = 0xFF1C1430.toInt()
private const val WHITE = 0xFFECECF1.toInt()
private const val GREY = 0xFF9A9AA6.toInt()
private const val DIM = 0xFF3A3A44.toInt()
private const val PURPLE = 0xFFA78BFA.toInt()
private const val WARN = 0xFFF0A868.toInt()


/**
 * Black-to-deep-purple gradient with the summary in the lower middle: below where the
 * clock and notifications usually sit, above the bottom shortcuts.
 */
fun draw(s: LockSnapshot, width: Int, height: Int): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
    })

    val u = width / 100f // layout unit: 1% of screen width
    val left = 9 * u
    fun text(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * u
        this.color = color
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (!bold) letterSpacing = 0.02f
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    var y = height * 0.60f
    // Formatter built per draw so a language change on the phone is picked up.
    val eyebrow = DateTimeFormatter.ofPattern("EEEE · MMM d", Locale.getDefault())
    c.drawText(s.date.format(eyebrow).uppercase(), left, y, text(3.4f, GREY).apply { letterSpacing = 0.12f })
    y += 9 * u

    fun line(label: String, color: Int, filled: Boolean) {
        dot.color = color
        dot.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        dot.strokeWidth = 0.5f * u
        c.drawCircle(left + 1.4f * u, y - 1.6f * u, 1.4f * u, dot)
        c.drawText(label, left + 5.5f * u, y, text(4.6f, WHITE))
        y += 8 * u
    }
    val morningColor = when (s.morningTone) {
        LockSnapshot.Tone.Good -> PURPLE
        LockSnapshot.Tone.Warn -> WARN
        LockSnapshot.Tone.Neutral -> GREY
    }
    line(s.morningLine, morningColor, s.morningTone != LockSnapshot.Tone.Neutral)
    line(s.workoutLine, if (s.workoutDone) PURPLE else GREY, s.workoutDone)
    line(s.foodLine, GREY, false)
    s.stepsLine?.let { line(it, if (s.stepsReached) PURPLE else GREY, s.stepsReached) }

    // This week's mornings, Monday to Sunday.
    y += 3 * u
    val gap = 11 * u
    s.week.forEachIndexed { i, (date, status) ->
        val cx = left + 1.4f * u + i * gap
        val cy = y
        val r = 2.2f * u
        dot.strokeWidth = 0.5f * u
        when (status) {
            Morning.ON_TIME -> { dot.style = Paint.Style.FILL; dot.color = PURPLE; c.drawCircle(cx, cy, r, dot) }
            Morning.LATE, Morning.MISSED -> { dot.style = Paint.Style.STROKE; dot.color = WARN; c.drawCircle(cx, cy, r, dot) }
            Morning.OFF -> { dot.style = Paint.Style.FILL; dot.color = DIM; c.drawRect(cx - r, cy - 0.3f * u, cx + r, cy + 0.3f * u, dot) }
            Morning.UPCOMING, Morning.WAITING, Morning.PLANNED -> { dot.style = Paint.Style.STROKE; dot.color = DIM; c.drawCircle(cx, cy, r, dot) }
        }
        val isToday = date == s.date
        val letter = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
        val p = text(2.8f, if (isToday) WHITE else GREY, bold = isToday).apply { textAlign = Paint.Align.CENTER }
        c.drawText(letter, cx, cy + 7 * u, p)
    }
    y += 16 * u

    s.tomorrowLine?.let { c.drawText(it, left, y, text(3.6f, PURPLE)) }
    return bmp
}
