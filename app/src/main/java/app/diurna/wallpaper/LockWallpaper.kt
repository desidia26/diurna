package app.diurna.wallpaper

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.view.Display
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import android.os.Build
import android.util.Log
import android.view.WindowManager
import app.diurna.container
import app.diurna.data.AppDatabase
import app.diurna.data.DayRepository
import app.diurna.data.NutritionRepository
import app.diurna.data.key
import app.diurna.data.StepSource
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import app.diurna.data.SettingsStore
import app.diurna.domain.AlarmRules
import app.diurna.domain.AlarmRules.Morning
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
                wallpapers.setBitmap(draw(snapshot, w, h, fingerprintZone()), null, true, WallpaperManager.FLAG_LOCK)
                lastDrawn = snapshot
                Log.d("DiurnaLock", "lock screen redrawn")
            }
        } catch (e: Exception) {
            Log.w("Diurna", "Lock wallpaper update failed", e)
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
            Log.w("Diurna", "Clearing lock wallpaper failed", e)
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

    /**
     * Where this phone's under-display fingerprint sensor is, from the platform's
     * config_udfps_sensor_props (x, y, radius in the panel's native pixels) when the maker
     * fills it in; otherwise a band measured on a Pixel.
     */
    private fun fingerprintZone(): FingerprintZone = try {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_udfps_sensor_props", "array", "android")
        val props = if (id != 0) res.getIntArray(id) else IntArray(0)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        // The props are in native pixels, which can differ from the resolution in use.
        val nativeHeight = display?.supportedModes?.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
        if (props.size >= 3 && nativeHeight > 0) {
            FingerprintZone.fromSensor(props[1].toFloat(), props[2].toFloat(), nativeHeight.toFloat())
        } else null
    } catch (e: Exception) {
        null
    } ?: FingerprintZone.DEFAULT

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
 * Black-to-deep-purple gradient with the day's lines just above the fingerprint sensor (below
 * the clock and at-a-glance cards) and the week's dots just below it, above the bottom
 * shortcuts. Long lines wrap instead of running off the edge.
 */
fun draw(s: LockSnapshot, width: Int, height: Int, fingerprint: FingerprintZone = FingerprintZone.DEFAULT): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
    })

    val u = width / 100f // layout unit: 1% of screen width
    val left = 9 * u
    val textLeft = left + 5.5f * u // rows hang past their dot
    val textWidth = (width - left - textLeft).toInt().coerceAtLeast(1)
    fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * u
        this.color = color
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (!bold) letterSpacing = 0.02f
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    val morningColor = when (s.morningTone) {
        LockSnapshot.Tone.Good -> PURPLE
        LockSnapshot.Tone.Warn -> WARN
        LockSnapshot.Tone.Neutral -> GREY
    }
    class Row(val label: String, val color: Int, val filled: Boolean)
    val rows = listOfNotNull(
        Row(s.morningLine, morningColor, s.morningTone != LockSnapshot.Tone.Neutral),
        Row(s.workoutLine, if (s.workoutDone) PURPLE else GREY, s.workoutDone),
        Row(s.foodLine, GREY, false),
        s.stepsLine?.let { Row(it, if (s.stepsReached) PURPLE else GREY, s.stepsReached) },
    )
    // Formatter built per draw so a language change on the phone is picked up.
    val eyebrow = s.date.format(DateTimeFormatter.ofPattern("EEEE · MMM d", Locale.getDefault())).uppercase()

    /** The eyebrow, the rows and tomorrow's alarm, laid out at a text scale but not yet drawn. */
    class Summary(val scale: Float) {
        val eyebrowPaint = text(3.4f * scale, GREY).apply { letterSpacing = 0.12f }
        val eyebrowAscent = -eyebrowPaint.fontMetrics.ascent
        val layouts = rows.map { wrap(it.label, text(4.6f * scale, WHITE), textWidth) }
        val tomorrow = s.tomorrowLine?.let { wrap(it, text(3.6f * scale, PURPLE), width - 2 * left.toInt()) }
        // Baseline to baseline: eyebrow to first row, and between single-line rows, as before.
        val firstGap = 9 * u * scale - layouts.firstOrNull().baseline0()
        val rowGap = 8 * u * scale - layouts.firstOrNull().lineHeight0()
        val height = eyebrowAscent + firstGap + layouts.sumOf { it.height } +
            rowGap * (layouts.size - 1).coerceAtLeast(0) +
            (tomorrow?.let { 3 * u * scale + it.height } ?: 0f)
    }
    val summaries = HashMap<Float, Summary>()
    fun summary(scale: Float) = summaries.getOrPut(scale) { Summary(scale) }
    fun weekHeight(scale: Float) = 10.5f * u * scale // dots plus the letters under them

    val place = LockLayout.place(height.toFloat(), fingerprint, 3 * u, { summary(it).height }, ::weekHeight)
    val sm = summary(place.scale)

    var y = place.summaryTop + sm.eyebrowAscent
    c.drawText(eyebrow, left, y, sm.eyebrowPaint)
    y += sm.firstGap
    rows.zip(sm.layouts).forEach { (row, layout) ->
        val baseline = y + layout.getLineBaseline(0)
        dot.color = row.color
        dot.style = if (row.filled) Paint.Style.FILL else Paint.Style.STROKE
        dot.strokeWidth = 0.5f * u * place.scale
        c.drawCircle(left + 1.4f * u, baseline - 1.6f * u * place.scale, 1.4f * u * place.scale, dot)
        c.withTranslation(textLeft, y) { layout.draw(this) }
        y += layout.height + sm.rowGap
    }
    sm.tomorrow?.let { t ->
        y += 3 * u * place.scale - sm.rowGap
        c.withTranslation(left, y) { t.draw(this) }
    }

    // This week's mornings, Monday to Sunday.
    val ws = if (place.weekBelowZone) 1f else place.scale
    val gap = 11 * u
    val r = 2.2f * u * ws
    val cy = place.weekTop + r + 0.3f * u
    s.week.forEachIndexed { i, (date, status) ->
        val cx = left + 1.4f * u + i * gap
        dot.strokeWidth = 0.5f * u * ws
        when (status) {
            Morning.ON_TIME -> { dot.style = Paint.Style.FILL; dot.color = PURPLE; c.drawCircle(cx, cy, r, dot) }
            Morning.LATE, Morning.MISSED -> { dot.style = Paint.Style.STROKE; dot.color = WARN; c.drawCircle(cx, cy, r, dot) }
            Morning.OFF -> { dot.style = Paint.Style.FILL; dot.color = DIM; c.drawRect(cx - r, cy - 0.3f * u, cx + r, cy + 0.3f * u, dot) }
            Morning.UPCOMING, Morning.WAITING, Morning.PLANNED -> { dot.style = Paint.Style.STROKE; dot.color = DIM; c.drawCircle(cx, cy, r, dot) }
        }
        val isToday = date == s.date
        val letter = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
        val p = text(2.8f * ws, if (isToday) WHITE else GREY, bold = isToday).apply { textAlign = Paint.Align.CENTER }
        c.drawText(letter, cx, cy + 7 * u * ws, p)
    }
    return bmp
}

/**
 * Wraps at the " · " between facts, keeping each fact ("P 84/141", "3,170 / 3,070 kcal") on
 * one line, so a wrapped line still reads as whole pieces. A single fact wider than the
 * screen still breaks inside rather than being cut off.
 */
private fun wrap(label: String, paint: TextPaint, width: Int): StaticLayout {
    val text = label.split(" · ").joinToString("\u00A0· ") { it.replace(' ', '\u00A0') }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setIncludePad(false)
        .build()
}

private fun StaticLayout?.baseline0(): Float = this?.getLineBaseline(0)?.toFloat() ?: 0f
private fun StaticLayout?.lineHeight0(): Float = this?.let { (it.getLineBottom(0) - it.getLineTop(0)).toFloat() } ?: 0f
