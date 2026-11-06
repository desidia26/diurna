package app.diurna.domain

import java.time.LocalDate

/**
 * Pure rules for stopping a ringing alarm. The porch QR is the only real dismissal;
 * a test alarm may also be stopped without a scan.
 */
object AlarmDismiss {
    fun matchesPorch(scanned: String, secret: String): Boolean = scanned == secret

    /** Real alarms record a morning scan; test alarms and undated rings do not. */
    fun recordsMorning(test: Boolean, date: LocalDate?): Boolean = !test && date != null
}
