package app.diurna.domain

import java.time.DayOfWeek
import java.time.LocalDate

/** Trends over a range of days: weight averages, ratings, and lifting sessions, PRs and volume. */
object Metrics {
    enum class Range(val label: String, val days: Long?) {
        W4("4w", 28), W12("12w", 84), Y1("1y", 365), ALL("All", null);

        /** First day of the range ending [today]; All starts at the first day with any data. */
        fun start(today: LocalDate, firstDate: LocalDate?): LocalDate =
            days?.let { today.minusDays(it - 1) } ?: minOf(firstDate ?: today, today)
    }

    /** The 7-day average needs this many weigh-ins in its window, so one reading never passes for a trend. */
    const val AVG_MIN_POINTS = 3
    private const val AVG_WINDOW_DAYS = 7L

    /**
     * Each day's average of the weigh-ins on it and the 6 days before; null with fewer than
     * [AVG_MIN_POINTS]. [weights] may reach back before [from] so the first days have a full window.
     */
    fun rollingAverage(weights: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<Double?> =
        days(from, to).map { d ->
            val window = (0 until AVG_WINDOW_DAYS).mapNotNull { weights[d.minusDays(it)] }
            if (window.size >= AVG_MIN_POINTS) window.average() else null
        }

    /** Last known value minus the first; null unless two different points exist. */
    fun change(series: List<Double?>): Double? {
        val first = series.indexOfFirst { it != null }
        val last = series.indexOfLast { it != null }
        if (first < 0 || first == last) return null
        return series[last]!! - series[first]!!
    }

    data class DayRating(val date: LocalDate, val mood: Int?, val energy: Int?)
    data class RatingSummary(val mood: Double?, val energy: Double?, val rated: Int, val days: Int)

    /** Averages count rated days only; a day counts as rated when either scale is filled in. */
    fun ratingSummary(ratings: List<DayRating>): RatingSummary = RatingSummary(
        mood = ratings.mapNotNull { it.mood }.takeIf { it.isNotEmpty() }?.average(),
        energy = ratings.mapNotNull { it.energy }.takeIf { it.isNotEmpty() }?.average(),
        rated = ratings.count { it.mood != null || it.energy != null },
        days = ratings.size,
    )

    // ---- Lifting ----

    /** Epley tops out here: past 12 reps it overestimates, so those sets don't count toward e1RM. */
    const val E1RM_MAX_REPS = 12

    /** Estimated one-rep max, weight × (1 + reps/30). One rep is the weight itself. */
    fun e1rm(weight: Double?, reps: Int?): Double? {
        if (weight == null || weight <= 0 || reps == null || reps !in 1..E1RM_MAX_REPS) return null
        return if (reps == 1) weight else weight * (1 + reps / 30.0)
    }

    /** One logged set, with the session it belongs to. */
    data class SetEntry(
        val date: LocalDate,
        val exerciseId: Long,
        val name: String,
        val loggedId: Long,
        val weight: Double?,
        val reps: Int?,
        /** A cardio piece's row; left out of lifting sessions, so never in volume, e1RM or lifting PRs. */
        val cardio: Boolean = false,
    )

    /** One exercise on one day. */
    data class Session(
        val date: LocalDate,
        val exerciseId: Long,
        val name: String,
        val loggedId: Long,
        val sets: List<SetEntry>,
    ) {
        val bestE1rm: Double? = sets.mapNotNull { e1rm(it.weight, it.reps) }.maxOrNull()
        /** Heaviest weight lifted for at least one rep. */
        val topWeight: Double? = sets.filter { (it.reps ?: 0) >= 1 }.mapNotNull { it.weight }.filter { it > 0 }.maxOrNull()
        /** The set behind [topWeight], with the most reps at that weight. */
        val topSet: SetEntry? = topWeight?.let { w -> sets.filter { it.weight == w }.maxByOrNull { it.reps ?: 0 } }
        /** Σ weight × reps; sets missing either count 0. */
        val volume: Double = sets.sumOf { (it.weight ?: 0.0).coerceAtLeast(0.0) * (it.reps ?: 0).coerceAtLeast(0) }
    }

    /**
     * Groups sets by session in the order given, leaving out sessions with no reps filled in (planned,
     * not lifted) and cardio, which has its own sessions in [Cardio].
     */
    fun sessions(sets: List<SetEntry>): List<Session> =
        sets.filter { !it.cardio }.groupBy { it.loggedId }.values
            .filter { s -> s.any { it.reps != null } }
            .map { s -> s.first().let { Session(it.date, it.exerciseId, it.name, it.loggedId, s) } }
            .sortedBy { it.date }

    data class Pr(val session: Session, val e1rm: Boolean, val weight: Boolean)

    /**
     * Sessions that beat every earlier session of the same exercise on best e1RM or top weight.
     * Judged against all history, so pass every session, not just a range. A first session sets the
     * baseline and isn't a PR; a tie isn't one either.
     */
    fun prs(sessions: List<Session>): List<Pr> {
        val bestE1rm = mutableMapOf<Long, Double>()
        val bestWeight = mutableMapOf<Long, Double>()
        val seen = mutableSetOf<Long>()
        val out = mutableListOf<Pr>()
        for (s in sessions.sortedBy { it.date }) {
            val first = seen.add(s.exerciseId)
            val prevE = bestE1rm[s.exerciseId]
            val prevW = bestWeight[s.exerciseId]
            val e = !first && s.bestE1rm != null && (prevE == null || s.bestE1rm > prevE)
            val w = !first && s.topWeight != null && (prevW == null || s.topWeight > prevW)
            if (e || w) out += Pr(s, e, w)
            s.bestE1rm?.let { if (prevE == null || it > prevE) bestE1rm[s.exerciseId] = it }
            s.topWeight?.let { if (prevW == null || it > prevW) bestWeight[s.exerciseId] = it }
        }
        return out
    }

    fun weekOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    data class WeekVolume(val monday: LocalDate, val volume: Double)

    /**
     * Total volume per Monday-to-Sunday week, every week touching [from]..[to], empty weeks included.
     * Each bar is a whole week, even where the range starts mid-week, so the first bar isn't cut short.
     */
    fun weeklyVolume(sessions: List<Session>, from: LocalDate, to: LocalDate): List<WeekVolume> {
        val totals = sessions.filter { it.date in weekOf(from)..to }.groupBy { weekOf(it.date) }.mapValues { (_, s) -> s.sumOf { it.volume } }
        return generateSequence(weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }
            .map { WeekVolume(it, totals[it] ?: 0.0) }.toList()
    }

    data class Usage(val exerciseId: Long, val name: String, val sessions: Int, val last: LocalDate)

    /** Exercises by sessions in the range, ties going to the most recently done. */
    fun mostLogged(sessions: List<Session>, from: LocalDate, to: LocalDate, n: Int = 5): List<Usage> =
        sessions.filter { it.date in from..to }.groupBy { it.exerciseId }
            .map { (id, s) -> Usage(id, s.last().name, s.size, s.maxOf { it.date }) }
            .sortedWith(compareByDescending<Usage> { it.sessions }.thenByDescending { it.last })
            .take(n)

    fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()

    // ---- Macros (kcal from protein / carbs / fat) ----

    /** Absolute stacked kcal, or each bar scaled to 100%. Remembered like [Range]. */
    enum class MacroChartMode(val label: String) { KCAL("kcal"), PERCENT("%") }

    /**
     * One day's (or week's average) calories from each macro. [carbsKcal] is available-carb energy
     * after the fiber adjustment; [fiberKcal] is 0 when fiber is unknown (plain 4/4/9).
     */
    data class MacroStack(
        val date: LocalDate,
        val proteinKcal: Double,
        val carbsKcal: Double,
        val fatKcal: Double,
        val fiberKcal: Double = 0.0,
    ) {
        val total: Double get() = proteinKcal + carbsKcal + fiberKcal + fatKcal
    }

    /** Grams → kcal via [MacroEnergy]. Missing P/C/F count as 0; unknown fiber means no adjustment. */
    fun macroKcal(
        date: LocalDate,
        proteinG: Int?,
        carbsG: Int?,
        fatG: Int?,
        fiberG: Int? = null,
        solubleFiberG: Int? = null,
        insolubleFiberG: Int? = null,
    ): MacroStack {
        val e = MacroEnergy.kcal(proteinG, carbsG, fatG, fiberG, solubleFiberG, insolubleFiberG)
        return MacroStack(date, e.proteinKcal, e.carbsKcal, e.fatKcal, e.fiberKcal)
    }

    /**
     * One stack per day in [from]..[to]. Days with no entry in [byDate] are null (a chart gap).
     * A day present with all-zero macros still counts as a bar (food was logged).
     */
    fun dailyMacroStacks(byDate: Map<LocalDate, MacroStack>, from: LocalDate, to: LocalDate): List<MacroStack?> =
        days(from, to).map { byDate[it] }

    /**
     * Monday-week averages of days that have food. Weeks with no food days are null (gap).
     * Every week touching [from]..[to] is included, same shape as [weeklyVolume].
     */
    fun weeklyMacroAverages(byDate: Map<LocalDate, MacroStack>, from: LocalDate, to: LocalDate): List<MacroStack?> {
        val inWindow = byDate.filterKeys { it in weekOf(from)..to }
        return generateSequence(weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }.map { monday ->
            val days = inWindow.filterKeys { weekOf(it) == monday }.values
            if (days.isEmpty()) null
            else MacroStack(
                date = monday,
                proteinKcal = days.map { it.proteinKcal }.average(),
                carbsKcal = days.map { it.carbsKcal }.average(),
                fatKcal = days.map { it.fatKcal }.average(),
                fiberKcal = days.map { it.fiberKcal }.average(),
            )
        }.toList()
    }

    /**
     * Share of each macro as 0–100. Null input or a zero total → null (gap; no divide-by-zero).
     */
    fun macroPercent(stack: MacroStack?): MacroStack? {
        if (stack == null) return null
        val t = stack.total
        if (t <= 0.0) return null
        return MacroStack(
            date = stack.date,
            proteinKcal = 100.0 * stack.proteinKcal / t,
            carbsKcal = 100.0 * stack.carbsKcal / t,
            fatKcal = 100.0 * stack.fatKcal / t,
            fiberKcal = 100.0 * stack.fiberKcal / t,
        )
    }

    /** Mean of non-null stacks' totals; null when none. */
    fun averageMacroTotal(stacks: List<MacroStack?>): Double? =
        stacks.mapNotNull { it?.total }.takeIf { it.isNotEmpty() }?.average()

    /** Mean of each macro across non-null stacks; null when none. */
    fun averageMacroStack(stacks: List<MacroStack?>): MacroStack? {
        val known = stacks.filterNotNull()
        if (known.isEmpty()) return null
        return MacroStack(
            date = known.last().date,
            proteinKcal = known.map { it.proteinKcal }.average(),
            carbsKcal = known.map { it.carbsKcal }.average(),
            fatKcal = known.map { it.fatKcal }.average(),
            fiberKcal = known.map { it.fiberKcal }.average(),
        )
    }

    // ---- Sub-macros (grams; Detailed nutrition) ----

    /**
     * One day's gram totals from estimated food. P/C/F are always present (0 when missing);
     * optional sub-macros stay null when unknown — never treated as 0 for coverage.
     */
    data class DayMacroGrams(
        val date: LocalDate,
        val proteinG: Int,
        val carbsG: Int,
        val fatG: Int,
        val fiberG: Int? = null,
        val solubleFiberG: Int? = null,
        val insolubleFiberG: Int? = null,
        val sugarsG: Int? = null,
        val addedSugarsG: Int? = null,
        val starchG: Int? = null,
        val satFatG: Int? = null,
        val monoFatG: Int? = null,
        val polyFatG: Int? = null,
        val transFatG: Int? = null,
        val cholesterolMg: Int? = null,
        val omega3G: Int? = null,
        val omega6G: Int? = null,
        val omega9G: Int? = null,
        val hisMg: Int? = null,
        val ileMg: Int? = null,
        val leuMg: Int? = null,
        val lysMg: Int? = null,
        val metMg: Int? = null,
        val pheMg: Int? = null,
        val thrMg: Int? = null,
        val trpMg: Int? = null,
        val valMg: Int? = null,
        val cysMg: Int? = null,
        val tyrMg: Int? = null,
    ) {
        /** Any carb sub-field known (fiber / sugars / starch). */
        val hasCarbDetail: Boolean
            get() = fiberG != null || solubleFiberG != null || insolubleFiberG != null ||
                sugarsG != null || addedSugarsG != null || starchG != null
        /** Any fat subtype known. */
        val hasFatDetail: Boolean
            get() = satFatG != null || monoFatG != null || polyFatG != null || transFatG != null
        val hasOmegaOrChol: Boolean
            get() = omega3G != null || omega6G != null || omega9G != null || cholesterolMg != null
        val hasAminos: Boolean
            get() = hisMg != null || ileMg != null || leuMg != null || lysMg != null ||
                metMg != null || pheMg != null || thrMg != null || trpMg != null ||
                valMg != null || cysMg != null || tyrMg != null
        val hasAnyDetail: Boolean get() = hasCarbDetail || hasFatDetail || hasOmegaOrChol || hasAminos

        fun aminos(): AminoAcids = AminoAcids(
            hisMg, ileMg, leuMg, lysMg, metMg, pheMg, thrMg, trpMg, valMg, cysMg, tyrMg,
        )
    }

    /**
     * Carb gram breakdown for one day (or week average). [otherG] is carbs minus known parts,
     * floored at 0 — never negative. Null when the day has no carb sub-macro data (a chart gap),
     * so a day with carbs but no detail does not look like 100% "other".
     */
    data class CarbStack(
        val date: LocalDate,
        /** Soluble fiber when the split is known; 0 otherwise. */
        val solubleFiberG: Double,
        /** Insoluble fiber when the split is known; 0 otherwise. */
        val insolubleFiberG: Double,
        /** Total fiber shown as one segment when the soluble/insoluble split is unknown. */
        val fiberUnspecifiedG: Double,
        val starchG: Double,
        /** Sugars excluding added, when added is known; otherwise all sugars. */
        val sugarsG: Double,
        /** Added sugars nested under sugars; 0 when unknown. */
        val addedSugarsG: Double,
        val otherG: Double,
        val carbsG: Double,
        val splitFiber: Boolean,
        val showAdded: Boolean,
    ) {
        val total: Double get() = solubleFiberG + insolubleFiberG + fiberUnspecifiedG + starchG + sugarsG + addedSugarsG + otherG
    }

    /** Fat gram breakdown. [otherG] = fat − known subtypes, floored at 0. Null = no fat detail that day. */
    data class FatStack(
        val date: LocalDate,
        val satG: Double,
        val monoG: Double,
        val polyG: Double,
        val transG: Double,
        val otherG: Double,
        val fatG: Double,
    ) {
        val total: Double get() = satG + monoG + polyG + transG + otherG
    }

    /** Compact omega / cholesterol averages over days that reported each value. */
    data class LipidSummary(
        val avgOmega3G: Double?,
        val avgOmega6G: Double?,
        val avgOmega9G: Double?,
        /** Omega-6 ÷ omega-3 over days that have both; null when omega-3 average is 0 or missing. */
        val omega6to3: Double?,
        val avgCholesterolMg: Double?,
        val daysWithOmega: Int,
        val daysWithChol: Int,
        val daysWithFood: Int,
    )

    /** Coverage of detail vs food days — shown so unknown days aren't mistaken for zeros. */
    data class DetailCoverage(val withDetail: Int, val withFood: Int) {
        val label: String get() = when {
            withFood == 0 -> "No food days"
            withDetail == withFood -> if (withFood == 1) "1 day" else "$withFood days"
            else -> "Detail on $withDetail of $withFood days"
        }
    }

    fun carbStack(day: DayMacroGrams): CarbStack? {
        if (!day.hasCarbDetail) return null
        val carbs = day.carbsG.coerceAtLeast(0).toDouble()
        val sol = day.solubleFiberG
        val insol = day.insolubleFiberG
        val fiber = day.fiberG
        val split = sol != null && insol != null
        val soluble: Double
        val insoluble: Double
        val unspecified: Double
        if (split) {
            soluble = sol!!.coerceAtLeast(0).toDouble()
            insoluble = insol!!.coerceAtLeast(0).toDouble()
            unspecified = 0.0
        } else {
            soluble = 0.0
            insoluble = 0.0
            // Prefer explicit total; else sum of whichever split is known.
            unspecified = (fiber ?: listOfNotNull(sol, insol).takeIf { it.isNotEmpty() }?.sum())
                ?.coerceAtLeast(0)?.toDouble() ?: 0.0
        }
        val starch = day.starchG?.coerceAtLeast(0)?.toDouble() ?: 0.0
        val sugarsRaw = day.sugarsG?.coerceAtLeast(0)?.toDouble()
        val addedRaw = day.addedSugarsG?.coerceAtLeast(0)?.toDouble()
        val showAdded = sugarsRaw != null && addedRaw != null
        val added: Double
        val sugars: Double
        if (showAdded) {
            added = addedRaw!!.coerceAtMost(sugarsRaw!!)
            sugars = (sugarsRaw - added).coerceAtLeast(0.0)
        } else {
            added = 0.0
            sugars = sugarsRaw ?: 0.0
        }
        val known = soluble + insoluble + unspecified + starch + sugars + added
        val other = (carbs - known).coerceAtLeast(0.0)
        return CarbStack(
            date = day.date,
            solubleFiberG = soluble,
            insolubleFiberG = insoluble,
            fiberUnspecifiedG = unspecified,
            starchG = starch,
            sugarsG = sugars,
            addedSugarsG = added,
            otherG = other,
            carbsG = carbs,
            splitFiber = split,
            showAdded = showAdded,
        )
    }

    fun fatStack(day: DayMacroGrams): FatStack? {
        if (!day.hasFatDetail) return null
        val fat = day.fatG.coerceAtLeast(0).toDouble()
        val sat = day.satFatG?.coerceAtLeast(0)?.toDouble() ?: 0.0
        val mono = day.monoFatG?.coerceAtLeast(0)?.toDouble() ?: 0.0
        val poly = day.polyFatG?.coerceAtLeast(0)?.toDouble() ?: 0.0
        val trans = day.transFatG?.coerceAtLeast(0)?.toDouble() ?: 0.0
        val other = (fat - sat - mono - poly - trans).coerceAtLeast(0.0)
        return FatStack(day.date, sat, mono, poly, trans, other, fat)
    }

    fun dailyCarbStacks(byDate: Map<LocalDate, DayMacroGrams>, from: LocalDate, to: LocalDate): List<CarbStack?> =
        days(from, to).map { byDate[it]?.let(::carbStack) }

    fun dailyFatStacks(byDate: Map<LocalDate, DayMacroGrams>, from: LocalDate, to: LocalDate): List<FatStack?> =
        days(from, to).map { byDate[it]?.let(::fatStack) }

    fun weeklyCarbAverages(byDate: Map<LocalDate, DayMacroGrams>, from: LocalDate, to: LocalDate): List<CarbStack?> {
        val inWindow = byDate.filterKeys { it in weekOf(from)..to }
            .mapNotNull { (d, g) -> carbStack(g)?.let { d to it } }.toMap()
        return generateSequence(weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }.map { monday ->
            val days = inWindow.filterKeys { weekOf(it) == monday }.values
            if (days.isEmpty()) null else averageCarbStack(days.toList(), monday)
        }.toList()
    }

    fun weeklyFatAverages(byDate: Map<LocalDate, DayMacroGrams>, from: LocalDate, to: LocalDate): List<FatStack?> {
        val inWindow = byDate.filterKeys { it in weekOf(from)..to }
            .mapNotNull { (d, g) -> fatStack(g)?.let { d to it } }.toMap()
        return generateSequence(weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }.map { monday ->
            val days = inWindow.filterKeys { weekOf(it) == monday }.values
            if (days.isEmpty()) null else averageFatStack(days.toList(), monday)
        }.toList()
    }

    fun averageCarbStack(stacks: List<CarbStack>, date: LocalDate = stacks.last().date): CarbStack? {
        if (stacks.isEmpty()) return null
        return CarbStack(
            date = date,
            solubleFiberG = stacks.map { it.solubleFiberG }.average(),
            insolubleFiberG = stacks.map { it.insolubleFiberG }.average(),
            fiberUnspecifiedG = stacks.map { it.fiberUnspecifiedG }.average(),
            starchG = stacks.map { it.starchG }.average(),
            sugarsG = stacks.map { it.sugarsG }.average(),
            addedSugarsG = stacks.map { it.addedSugarsG }.average(),
            otherG = stacks.map { it.otherG }.average(),
            carbsG = stacks.map { it.carbsG }.average(),
            splitFiber = stacks.any { it.splitFiber },
            showAdded = stacks.any { it.showAdded },
        )
    }

    fun averageFatStack(stacks: List<FatStack>, date: LocalDate = stacks.last().date): FatStack? {
        if (stacks.isEmpty()) return null
        return FatStack(
            date = date,
            satG = stacks.map { it.satG }.average(),
            monoG = stacks.map { it.monoG }.average(),
            polyG = stacks.map { it.polyG }.average(),
            transG = stacks.map { it.transG }.average(),
            otherG = stacks.map { it.otherG }.average(),
            fatG = stacks.map { it.fatG }.average(),
        )
    }

    fun carbCoverage(byDate: Map<LocalDate, DayMacroGrams>): DetailCoverage =
        DetailCoverage(byDate.values.count { it.hasCarbDetail }, byDate.size)

    fun fatCoverage(byDate: Map<LocalDate, DayMacroGrams>): DetailCoverage =
        DetailCoverage(byDate.values.count { it.hasFatDetail }, byDate.size)

    fun lipidSummary(byDate: Map<LocalDate, DayMacroGrams>): LipidSummary? {
        if (byDate.isEmpty()) return null
        if (byDate.values.none { it.hasOmegaOrChol }) return null
        val o3 = byDate.values.mapNotNull { it.omega3G?.coerceAtLeast(0)?.toDouble() }
        val o6 = byDate.values.mapNotNull { it.omega6G?.coerceAtLeast(0)?.toDouble() }
        val o9 = byDate.values.mapNotNull { it.omega9G?.coerceAtLeast(0)?.toDouble() }
        val chol = byDate.values.mapNotNull { it.cholesterolMg?.coerceAtLeast(0)?.toDouble() }
        val avg3 = o3.takeIf { it.isNotEmpty() }?.average()
        val avg6 = o6.takeIf { it.isNotEmpty() }?.average()
        val ratio = if (avg3 != null && avg6 != null && avg3 > 0) avg6 / avg3 else null
        return LipidSummary(
            avgOmega3G = avg3,
            avgOmega6G = avg6,
            avgOmega9G = o9.takeIf { it.isNotEmpty() }?.average(),
            omega6to3 = ratio,
            avgCholesterolMg = chol.takeIf { it.isNotEmpty() }?.average(),
            daysWithOmega = byDate.values.count { it.omega3G != null || it.omega6G != null || it.omega9G != null },
            daysWithChol = byDate.values.count { it.cholesterolMg != null },
            daysWithFood = byDate.size,
        )
    }

    /** Average daily amino mg over days that reported any amino; no targets/reference lines. */
    data class AminoSummary(
        val average: AminoAcids,
        val daysWithAminos: Int,
        val daysWithFood: Int,
    ) {
        val coverageLabel: String get() = when {
            daysWithFood == 0 -> "No food days"
            daysWithAminos == daysWithFood -> if (daysWithFood == 1) "1 day" else "$daysWithFood days"
            else -> "Amino data on $daysWithAminos of $daysWithFood days"
        }
    }

    fun aminoSummary(byDate: Map<LocalDate, DayMacroGrams>): AminoSummary? {
        if (byDate.isEmpty()) return null
        val with = byDate.values.filter { it.hasAminos }
        if (with.isEmpty()) return null
        val avg = AminoAcids.average(with.map { it.aminos() }) ?: return null
        return AminoSummary(avg, with.size, byDate.size)
    }
}
