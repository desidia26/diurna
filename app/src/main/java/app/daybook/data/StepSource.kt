package app.daybook.data

import java.time.LocalDate

/**
 * Where a day's step count comes from (Health Connect on the phone). Behind an interface so
 * tests use fake counts. Null when there's no data or no permission.
 */
interface StepSource {
    suspend fun stepsOn(date: LocalDate): Long?
}
