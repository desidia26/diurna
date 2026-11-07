package app.diurna.data

import androidx.room.withTransaction
import app.diurna.data.LibraryRepository.Result
import app.diurna.domain.DailyMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

fun DailyMetricEntity.def() = DailyMetrics.Def(
    id = id, name = name, kind = DailyMetrics.Kind.of(kind), scaleMax = scaleMax ?: DailyMetrics.DEFAULT_STEPS,
    words = DailyMetrics.parseWords(words), unit = unit, archived = archived,
)

fun DailyMetricValueEntity.value() = DailyMetrics.Value(value, text)

/** A metric in Setup's list, with how many days have a value (which locks its kind and steps). */
data class DailyMetricRow(val def: DailyMetrics.Def, val days: Int)

/** The check-in's metrics: their definitions (Setup) and each day's values (Day screen). */
class DailyMetricRepository(private val db: AppDatabase) {
    private val dao = db.dailyMetricDao()
    private val days = db.dayDao()

    /** Called after any of a day's values changes. */
    var onValueChanged: suspend (LocalDate) -> Unit = {}

    val definitions: Flow<List<DailyMetrics.Def>> = dao.observeAll().map { all -> all.map { it.def() } }

    val rows: Flow<List<DailyMetricRow>> = combine(dao.observeAll(), dao.observeCounts()) { all, counts ->
        val byId = counts.associate { it.metricId to it.days }
        all.map { DailyMetricRow(it.def(), byId[it.id] ?: 0) }
    }

    fun observeValues(date: LocalDate): Flow<Map<Long, DailyMetrics.Value>> =
        dao.observeValues(date.key()).map { rows -> rows.associate { it.metricId to it.value() } }

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DailyMetricValueEntity>> =
        dao.observeValuesRange(from.key(), to.key())

    /** Scale step, number or 1/0, cleaned to fit the metric; null or a misfit clears it. */
    suspend fun setValue(date: LocalDate, metricId: Long, value: Double?) {
        val def = dao.get(metricId)?.def() ?: return
        // A text metric's row holds its text; a stray number must not clear it.
        if (def.kind == DailyMetrics.Kind.TEXT) return
        write(date, metricId, DailyMetrics.cleanValue(def, value)?.let { DailyMetricValueEntity(date.key(), metricId, value = it) })
    }

    suspend fun setText(date: LocalDate, metricId: Long, text: String?) {
        val def = dao.get(metricId)?.def() ?: return
        if (def.kind != DailyMetrics.Kind.TEXT) return
        write(date, metricId, DailyMetrics.cleanText(text)?.let { DailyMetricValueEntity(date.key(), metricId, text = it) })
    }

    private suspend fun write(date: LocalDate, metricId: Long, row: DailyMetricValueEntity?) {
        db.withTransaction {
            days.insertIgnore(DayEntity(date.key()))
            if (row == null) dao.deleteValue(date.key(), metricId) else dao.upsertValue(row)
        }
        onValueChanged(date)
    }

    /** Active metrics still waiting for [date]'s value; empty when the check-in is done. */
    suspend fun missing(date: LocalDate): List<DailyMetrics.Def> =
        DailyMetrics.missing(dao.all().map { it.def() }, dao.values(date.key()).associate { it.metricId to it.value() })

    suspend fun isComplete(date: LocalDate): Boolean = missing(date).isEmpty()

    suspend fun add(def: DailyMetrics.Def): Result = db.withTransaction {
        problem(def, exceptId = null)?.let { return@withTransaction Result.Problem(it) }
        dao.insert(entity(def, position = dao.maxPosition() + 1))
        Result.Ok
    }

    /** Renames and rewords; the kind and step count change only while nothing is logged. */
    suspend fun update(def: DailyMetrics.Def): Result = db.withTransaction {
        val old = dao.get(def.id) ?: return@withTransaction Result.Problem("That metric is gone.")
        val logged = dao.valueCount(def.id) > 0
        val oldDef = old.def()
        if (logged && (def.kind != oldDef.kind || (def.kind == DailyMetrics.Kind.SCALE && def.scaleMax != oldDef.scaleMax))) {
            return@withTransaction Result.Problem("It has values logged, so its type and steps stay as they are.")
        }
        problem(def, exceptId = def.id)?.let { return@withTransaction Result.Problem(it) }
        dao.update(entity(def, position = old.position).copy(id = old.id, key = old.key, archived = old.archived))
        Result.Ok
    }

    private suspend fun problem(def: DailyMetrics.Def, exceptId: Long?): String? =
        DailyMetrics.problem(def, dao.all().filter { it.id != exceptId }.map { it.name })

    private fun entity(def: DailyMetrics.Def, position: Int): DailyMetricEntity {
        val scale = def.kind == DailyMetrics.Kind.SCALE
        return DailyMetricEntity(
            name = DailyMetrics.cleanLine(def.name),
            kind = def.kind.key,
            scaleMax = def.scaleMax.takeIf { scale },
            words = DailyMetrics.joinWords(def.words).takeIf { scale },
            unit = def.unit?.let(DailyMetrics::cleanLine)?.ifEmpty { null }.takeIf { def.kind == DailyMetrics.Kind.NUMBER },
            position = position,
        )
    }

    /** Archiving can finish today's check-in, so the reminder gets a look. */
    suspend fun setArchived(id: Long, archived: Boolean, today: LocalDate) {
        dao.setArchived(id, archived)
        onValueChanged(today)
    }

    /** Removes the metric and every day's value for it. */
    suspend fun delete(id: Long, today: LocalDate) {
        db.withTransaction {
            dao.deleteValues(id)
            dao.delete(id)
        }
        onValueChanged(today)
    }

    /** Swaps with the neighbor above or below; nothing at the ends. */
    suspend fun move(id: Long, up: Boolean) = db.withTransaction {
        val all = dao.all()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return@withTransaction
        val j = if (up) i - 1 else i + 1
        if (j !in all.indices) return@withTransaction
        // Rewrites every position, so ties or gaps left by deletes can't make a swap a no-op.
        val order = all.toMutableList().apply { add(j, removeAt(i)) }
        order.forEachIndexed { pos, m -> if (m.position != pos) dao.setPosition(m.id, pos) }
    }
}
