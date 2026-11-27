package app.diurna.data

import androidx.room.withTransaction
import app.diurna.domain.SleepJson
import app.diurna.domain.SleepRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Remembers the last successful read (kept in settings, outside the database). */
interface SleepSyncStore {
    suspend fun sleepLastRead(): LocalDate?
    suspend fun setSleepLastRead(date: LocalDate)
}

/**
 * Owns every write to `sleep_period`. A read starts where [SleepRules.readFrom] says, because
 * sources sync late, and stored nights only change while [SleepRules.updatable].
 */
class SleepRepository(
    private val db: AppDatabase,
    private val source: SleepSource,
    private val store: SleepSyncStore,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.sleepDao()
    private val mutex = Mutex()

    data class Result(val added: Int, val updated: Int, val removed: Int)

    fun observe(from: LocalDate, to: LocalDate): Flow<List<SleepPeriodEntity>> = dao.observeRange(from.toString(), to.toString())

    /** Apps that wrote sleep in the last 30 days, most nights first. One window for every screen, so they agree. */
    fun observeOrigins(): Flow<List<SleepOriginCount>> =
        dao.observeOrigins(LocalDateTime.ofInstant(now(), zone()).toLocalDate().minusDays(SleepRules.HISTORY_DAYS).toString())

    /** Whose sleep shows, given the app picked in Setup ([SleepRules.pickOrigin]). */
    fun observeOrigin(chosen: Flow<String?>): Flow<String?> =
        combine(chosen, observeOrigins()) { c, origins -> SleepRules.pickOrigin(c, origins.map { it.origin to it.nights }) }

    /** Reads and stores. Throws what the source throws; nothing is written then. */
    suspend fun sync(): Result = mutex.withLock {
        val z = zone()
        val at = now()
        val today = LocalDateTime.ofInstant(at, z).toLocalDate()
        val from = SleepRules.readFrom(store.sleepLastRead(), dao.newestDate(source.key)?.let(LocalDate::parse), today)
        // A night ending on [from] started the evening before.
        val read = source.read(from.minusDays(1).atStartOfDay(z).toInstant(), at)
        val nights = SleepRules.nights(read.sessions, read.hr, read.hrv, read.breath, read.spo2, z)
            .filter { !it.date.isBefore(from) }
        val fetchedAt = at.toEpochMilli()
        var added = 0; var updated = 0; var removed = 0
        db.withTransaction {
            for (n in nights) {
                val existing = dao.bySourceId(source.key, n.sourceId)
                val row = entity(n, fetchedAt)
                when {
                    existing == null -> { dao.insert(row); added++ }
                    SleepRules.updatable(LocalDate.parse(existing.date), today) -> { dao.update(row.copy(id = existing.id)); updated++ }
                }
            }
            // A night the source no longer has (deleted, or merged into another) goes too, while it can
            // still change. Not after a capped read: a session left out isn't gone.
            if (read.complete) {
                val seen = nights.map { it.sourceId }.toSet()
                dao.range(from.toString(), today.toString())
                    .filter { it.source == source.key && it.sourceId !in seen && SleepRules.updatable(LocalDate.parse(it.date), today) }
                    .forEach { dao.delete(it.id); removed++ }
            }
        }
        store.setSleepLastRead(today)
        Result(added, updated, removed)
    }

    private fun entity(n: SleepRules.Night, fetchedAt: Long) = SleepPeriodEntity(
        source = source.key,
        origin = n.origin,
        sourceId = n.sourceId,
        date = n.date.toString(),
        type = n.type.key,
        bedStart = n.bedStart.format(Stamp),
        wokeAt = n.wokeAt?.format(Stamp),
        bedEnd = n.bedEnd.format(Stamp),
        timeInBedS = n.timeInBedS,
        totalSleepS = n.totalSleepS,
        awakeS = n.awakeS,
        lightS = n.lightS,
        deepS = n.deepS,
        remS = n.remS,
        latencyS = n.latencyS,
        efficiency = n.efficiency,
        awakenings = n.awakenings,
        avgHr = n.avgHr,
        lowestHr = n.lowestHr,
        avgHrv = n.avgHrv,
        avgBreath = n.avgBreath,
        avgSpo2 = n.avgSpo2,
        stages = SleepJson.stages(n.stages),
        hrSamples = SleepJson.points(n.hr),
        hrvSamples = SleepJson.points(n.hrv),
        fetchedAt = fetchedAt,
    )

    companion object {
        val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
