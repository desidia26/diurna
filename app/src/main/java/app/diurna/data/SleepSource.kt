package app.diurna.data

import app.diurna.domain.SleepRules
import java.time.Instant

/**
 * Where sleep comes from (Health Connect for now). Behind an interface so tests use fakes.
 * Returns plain values; everything in them is untrusted and checked by [SleepRules].
 */
interface SleepSource {
    /** The key stored in `sleep_period.source`. */
    val key: String

    /** Sessions and samples between [from] and [to]. Throws when it can't read (no permission, unavailable). */
    suspend fun read(from: Instant, to: Instant): SleepRead
}

data class SleepRead(
    val sessions: List<SleepRules.Session>,
    val hr: List<SleepRules.Sample> = emptyList(),
    val hrv: List<SleepRules.Sample> = emptyList(),
    val breath: List<SleepRules.Sample> = emptyList(),
    val spo2: List<SleepRules.Sample> = emptyList(),
    /** False when a cap cut sessions off, so a missing session may still be there. */
    val complete: Boolean = true,
)
