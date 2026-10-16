package app.daybook.data

import java.time.LocalDate

/**
 * Where a day's step count comes from. Behind an interface so tests use fake counts.
 * Null when there's no count for that day.
 */
interface StepSource {
    suspend fun stepsOn(date: LocalDate): Long?
}

/** Placeholder until the phone's step sensor is wired in: no counts yet. */
object NoSteps : StepSource {
    override suspend fun stepsOn(date: LocalDate): Long? = null
}
