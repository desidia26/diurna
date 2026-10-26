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
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt
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
    private var lastDrawn: Pair<LockSnapshot, LockStyle>? = null
    private val wallpapers = WallpaperManager.getInstance(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) = scope.launch {
        // Targets come from the profile, which lives in settings rather than the database.
        combine(settings.lockWallpaper, db.invalidationTracker.createFlow(*TABLES), settings.profile, settings.lockStyle) { enabled, _, _, _ -> enabled }
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
            val drawn = snapshot() to settings.lockStyle.first()
            // Setting a wallpaper makes the lock screen flash, so only do it when the picture changes.
            if (drawn != lastDrawn) {
                wallpapers.setBitmap(draw(drawn.first, w, h, fingerprintZone(), wallpaperZoom(), drawn.second), null, true, WallpaperManager.FLAG_LOCK)
                lastDrawn = drawn
                Log.d("DiurnaLock", "lock screen redrawn")
            }
        } catch (e: Exception) {
            Log.w("Diurna", "Lock wallpaper update failed", e)
        }
        scheduleNextTick()
    }

    /**
     * Today's lock screen with [style], as it looks on screen (so without the zoom allowance),
     * [width] pixels wide, for Setup's preview, and the sensor band to mark on it.
     */
    suspend fun preview(style: LockStyle, width: Int): Pair<Bitmap, FingerprintZone> {
        val (w, h) = screenSize()
        val zone = fingerprintZone()
        val full = draw(snapshot(), w, h, zone, 1f, style)
        val small = Bitmap.createScaledBitmap(full, width, (width.toLong() * h / w).toInt(), true)
        if (small !== full) full.recycle()
        return small to zone
    }

    private suspend fun snapshot(): LockSnapshot {
        val today = LocalDateTime.now().toLocalDate()
        return buildSnapshot(
            db, days, targets = nutrition.observeTargets(today).first(),
            // The saved count, not a fresh sensor look: looking writes to the database, which
            // would trigger another redraw. The 15-minute sampler does the looking.
            steps = db.dayDao().get(today.key())?.steps, stepGoal = settings.stepGoal.first(),
        )
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

    /**
     * The system draws wallpapers enlarged by config_wallpaperMaxScale (1.1 on AOSP and the
     * Pixel) and zooms out to 1.0 only while the shade or recents is open. Measured on Larry's
     * Pixel: the lock-screen picture was 1.1x about its centre.
     */
    private fun wallpaperZoom(): Float = try {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_wallpaperMaxScale", "dimen", "android")
        if (id != 0) res.getFloat(id).takeIf { it in 1f..1.3f } ?: 1f else 1f
    } catch (e: Exception) {
        1f
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
 * Black-to-deep-purple gradient with the day's summary split around the fingerprint sensor:
 * the date and the day's lines (wrapping, centred) just above it, below the clock, cards and a
 * notification or two; the week's mornings below it, above the bottom shortcuts.
 *
 * [zoom] is how much the system enlarges the lock wallpaper about its centre (Pixels show it
 * at 1.1); the canvas is scaled down by it so positions and sizes here are what's seen on screen.
 */
fun draw(
    s: LockSnapshot,
    width: Int,
    height: Int,
    fingerprint: FingerprintZone = FingerprintZone.DEFAULT,
    zoom: Float = 1f,
    style: LockStyle = LockStyle.AUTO,
): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
    })
    c.scale(1f / zoom, 1f / zoom, width / 2f, height / 2f)

    val u = width / 100f // layout unit: 1% of screen width
    val centre = width / 2f
    val textWidth = (84 * u).toInt()
    fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * u
        this.color = color
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (!bold) letterSpacing = 0.02f
    }

    val morningColor = when (s.morningTone) {
        LockSnapshot.Tone.Good -> PURPLE
        LockSnapshot.Tone.Warn -> WARN
        LockSnapshot.Tone.Neutral -> GREY
    }
    class Row(val label: String, val color: Int, val filled: Boolean, val plain: Boolean = false)
    val rows = listOfNotNull(
        Row(s.morningLine, morningColor, s.morningTone != LockSnapshot.Tone.Neutral),
        Row(s.workoutLine, if (s.workoutDone) PURPLE else GREY, s.workoutDone),
        Row(s.foodLine, GREY, false),
        s.stepsLine?.let { Row(it, if (s.stepsReached) PURPLE else GREY, s.stepsReached) },
        s.tomorrowLine?.let { Row(it, PURPLE, false, plain = true) },
    )
    // Formatter built per draw so a language change on the phone is picked up.
    val eyebrow = s.date.format(DateTimeFormatter.ofPattern("EEEE · MMM d", Locale.getDefault())).uppercase()

    /** The date and the day's lines, laid out at a text scale but not yet drawn. */
    class Summary(val k: Float) {
        val eyebrowLayout = wrap(eyebrow, text(3.4f * k, GREY).apply { letterSpacing = 0.12f }, textWidth)
        val layouts = rows.map { row ->
            if (row.plain) return@map wrap(row.label, text(3.6f * k, row.color), textWidth)
            val dot = DotSpan(row.color, row.filled, radius = 1.4f * u * k, stroke = 0.5f * u * k, width = 4.6f * u * k, lift = 1.6f * u * k)
            wrap(row.label, text(4.6f * k, WHITE), textWidth, dot)
        }
        val afterEyebrow = 2.2f * u * k
        val rowGap = 1.8f * u * k
        val height = eyebrowLayout.height + afterEyebrow + layouts.sumOf { it.height } + rowGap * (layouts.size - 1)
    }
    val summaries = HashMap<Float, Summary>()
    fun summary(scale: Float) = summaries.getOrPut(scale) { Summary(scale) }
    // The week's dots: radius, spacing and where the letters sit, at a text scale.
    fun r(k: Float) = 2.2f * u * k
    fun weekHeight(k: Float) = r(k) + 7f * u * k + 1f * u * k

    val place = LockLayout.place(height.toFloat(), fingerprint, 1.2f * u, style, { summary(it).height.toFloat() }, ::weekHeight)
    val sum = summary(place.summaryScale)
    var y = place.summaryTop
    fun drawCentred(layout: StaticLayout) {
        c.withTranslation(centre - textWidth / 2f, y) { layout.draw(this) }
        y += layout.height
    }
    drawCentred(sum.eyebrowLayout)
    y += sum.afterEyebrow
    sum.layouts.forEachIndexed { i, layout ->
        if (i > 0) y += sum.rowGap
        drawCentred(layout)
    }

    // This week's mornings, Monday to Sunday.
    val k = place.weekScale
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.5f * u * k }
    val r = r(k)
    val cy = place.weekTop + r
    val dayGap = 11 * u * k
    val firstX = centre - 3 * dayGap
    s.week.forEachIndexed { i, (date, status) ->
        val cx = firstX + i * dayGap
        when (status) {
            Morning.ON_TIME -> { dot.style = Paint.Style.FILL; dot.color = PURPLE; c.drawCircle(cx, cy, r, dot) }
            Morning.LATE, Morning.MISSED -> { dot.style = Paint.Style.STROKE; dot.color = WARN; c.drawCircle(cx, cy, r, dot) }
            Morning.OFF -> { dot.style = Paint.Style.FILL; dot.color = DIM; c.drawRect(cx - r, cy - 0.3f * u, cx + r, cy + 0.3f * u, dot) }
            Morning.UPCOMING, Morning.WAITING, Morning.PLANNED -> { dot.style = Paint.Style.STROKE; dot.color = DIM; c.drawCircle(cx, cy, r, dot) }
        }
        val isToday = date == s.date
        val letter = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
        val p = text(2.8f * k, if (isToday) WHITE else GREY, bold = isToday).apply { textAlign = Paint.Align.CENTER }
        c.drawText(letter, cx, cy + 7f * u * k, p)
    }
    return bmp
}

/**
 * Centred, wrapping at the " · " between facts so each fact ("P 84/141", "3,170 / 3,070 kcal")
 * stays on one line. A single fact wider than [width] still breaks inside rather than being
 * cut off. [dot] goes inline before the first word, so a row's first line is centred with it.
 */
private fun wrap(label: String, paint: TextPaint, width: Int, dot: DotSpan? = null): StaticLayout {
    val body = label.split(" · ").joinToString("\u00A0· ") { it.replace(' ', '\u00A0') }
    // A no-break space carries the dot, so a line can't break between dot and text.
    val text = if (dot == null) SpannableString(body) else SpannableString("\u00A0" + body).apply {
        setSpan(dot, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .build()
}

/** A row's status dot, drawn in its own slot of text so it moves with the centred line. */
private class DotSpan(
    private val color: Int,
    private val filled: Boolean,
    private val radius: Float,
    private val stroke: Float,
    private val width: Float,
    private val lift: Float,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        return width.roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@DotSpan.color
            style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawCircle(x + stroke + radius, y - lift, radius, p)
    }
}
