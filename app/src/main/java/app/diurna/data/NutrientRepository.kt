package app.diurna.data

import androidx.room.withTransaction
import app.diurna.domain.DailyMicros
import app.diurna.domain.MicronutrientProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.diurna.data.fdc.FdcClient
import org.json.JSONObject

/**
 * Extensible micronutrient rows ([nutrient_value]) for meals, food entries, recipe lines, and
 * FDC foods. Missing ≠ zero. OFF backfill only fills owners that still have no rows.
 */
class NutrientRepository(private val db: AppDatabase) {
    private val dao = db.nutrientValueDao()
    private val food = db.foodDao()
    private val meals = db.mealDao()

    suspend fun profile(ownerKind: String, ownerId: Long): MicronutrientProfile =
        NutrientValues.toProfile(dao.forOwner(ownerKind, ownerId))

    suspend fun replace(
        ownerKind: String,
        ownerId: Long,
        profile: MicronutrientProfile,
        source: String,
    ) {
        db.withTransaction {
            dao.deleteOwner(ownerKind, ownerId)
            if (profile.hasAny) {
                dao.upsertAll(
                    NutrientValues.toRows(ownerKind, ownerId, profile, source),
                )
            }
        }
    }

    /** Per-basis OFF micros onto a meal; no-op when the snapshot has none. */
    suspend fun syncMealFromOff(mealId: Long, snap: OffSnapshot?) {
        if (mealId <= 0L || snap == null) return
        val profile = OffMicrosBackfill.profileFromSnapshot(snap) ?: return
        // Immutable once present: don't overwrite an existing meal profile.
        if (dao.forOwner(NutrientOwner.MEAL, mealId).isNotEmpty()) return
        replace(NutrientOwner.MEAL, mealId, profile, NutrientSource.OFF)
    }

    /**
     * Meal rows remain per OFF basis. Entry rows are amounts actually eaten, derived from the
     * entry's frozen snapshot (or the meal snapshot for legacy entries with none), never by
     * treating a cached per-basis meal profile as per-serving. Existing entry rows are not backfilled
     * over; only this explicit log/servings edit replaces them.
     */
    suspend fun copyMealToEntry(mealId: Long, entryId: Long, servings: Double) {
        if (entryId <= 0L || servings <= 0 || !servings.isFinite()) return
        val entry = food.get(entryId) ?: return
        val meal = meals.get(mealId)
        val mealSnap = OffSnapshot.decode(meal?.offSnapshotJson)
        syncMealFromOff(mealId, mealSnap)
        val snap = OffSnapshot.decode(entry.offSnapshotJson) ?: mealSnap ?: return
        val eaten = OffMicrosBackfill.profileForGrams(snap, snap.servingGrams() * servings) ?: return
        replace(NutrientOwner.FOOD_ENTRY, entryId, eaten, NutrientSource.OFF)
    }

    /** Explicit servings edit after the saved meal was deleted; keep units/source and missingness. */
    suspend fun scaleEntry(entryId: Long, factor: Double) {
        if (!factor.isFinite() || factor <= 0) return
        db.withTransaction {
            val rows = dao.forOwner(NutrientOwner.FOOD_ENTRY, entryId).mapNotNull { row ->
                app.diurna.domain.NutrientRegistry.sanitize(row.amount * factor)?.let { row.copy(amount = it) }
            }
            dao.deleteOwner(NutrientOwner.FOOD_ENTRY, entryId)
            dao.upsertAll(rows)
        }
    }

    /** Persist FDC per-100g micros for an fdcId; scale onto a recipe ingredient when [grams] set. */
    suspend fun syncFromFdcFood(fdcId: Int, foodJson: JSONObject, ingredientId: Long? = null, grams: Double? = null) {
        val per100 = FdcClient.parseMicronutrients(foodJson) ?: return
        if (dao.forOwner(NutrientOwner.FDC_FOOD, fdcId.toLong()).isEmpty()) {
            replace(NutrientOwner.FDC_FOOD, fdcId.toLong(), per100, NutrientSource.FDC)
        }
        if (ingredientId != null && grams != null && grams > 0) {
            replace(
                NutrientOwner.RECIPE_INGREDIENT,
                ingredientId,
                per100.scale(grams / 100.0),
                NutrientSource.FDC,
            )
        }
    }

    /**
     * One-shot: for meals and food entries with a usable OFF snapshot and no nutrient rows yet,
     * write micros from [OffSnapshot.rawJson]. Safe to call repeatedly.
     */
    suspend fun backfillFromOffSnapshots() {
        for (meal in meals.all()) {
            if (dao.forOwner(NutrientOwner.MEAL, meal.id).isNotEmpty()) continue
            val snap = OffSnapshot.decode(meal.offSnapshotJson) ?: continue
            val profile = OffMicrosBackfill.profileFromSnapshot(snap) ?: continue
            replace(NutrientOwner.MEAL, meal.id, profile, NutrientSource.OFF)
        }
        // Entries: prefer scaling by servings when linked to a meal; else serving grams from snapshot.
        // Use a broad date window via all entries that have snapshots — query via raw if needed.
        backfillEntries()
    }

    private suspend fun backfillEntries() {
        // Walk recent-ish history in chunks by scanning meals' linked entries is incomplete;
        // use food_entry rows that still carry off_snapshot_json.
        val entries = food.withOffSnapshot()
        for (entry in entries) {
            if (dao.forOwner(NutrientOwner.FOOD_ENTRY, entry.id).isNotEmpty()) continue
            val mealId = entry.mealId
            if (mealId != null) {
                copyMealToEntry(mealId, entry.id, entry.servings)
                continue
            }
            val snap = OffSnapshot.decode(entry.offSnapshotJson) ?: continue
            val grams = snap.servingGrams() * entry.servings
            val profile = OffMicrosBackfill.profileForGrams(snap, grams) ?: continue
            replace(NutrientOwner.FOOD_ENTRY, entry.id, profile, NutrientSource.OFF)
        }
    }

    suspend fun daily(date: String): DailyMicros? {
        val entries = food.forDate(date)
        if (entries.isEmpty()) return null
        val ids = entries.map { it.id }
        val rows = if (ids.isEmpty()) emptyList() else dao.forOwners(NutrientOwner.FOOD_ENTRY, ids)
        val byOwner = rows.groupBy { it.ownerId }
        val profiles = entries.map { e ->
            NutrientValues.toProfile(byOwner[e.id].orEmpty())
        }
        return DailyMicros.forEntries(profiles)
    }

    /** Sum of daily profiles over [from]..[to] (inclusive keys), with entry-level completeness. */
    suspend fun rangeTotals(from: String, to: String): DailyMicros? {
        val entries = food.forDateRange(from, to)
        if (entries.isEmpty()) return null
        val rows = dao.forOwners(NutrientOwner.FOOD_ENTRY, entries.map { it.id })
        val byOwner = rows.groupBy { it.ownerId }
        val profiles = entries.map { e -> NutrientValues.toProfile(byOwner[e.id].orEmpty()) }
        return DailyMicros.forEntries(profiles)
    }

    fun observeDaily(date: String): Flow<DailyMicros?> =
        food.observe(date).map { entries ->
            if (entries.isEmpty()) return@map null
            val rows = dao.forOwners(NutrientOwner.FOOD_ENTRY, entries.map { it.id })
            val byOwner = rows.groupBy { it.ownerId }
            DailyMicros.forEntries(entries.map { NutrientValues.toProfile(byOwner[it.id].orEmpty()) })
        }
}

