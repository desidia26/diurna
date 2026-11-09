package app.diurna.domain

/** Prevent double selection/lookup; explicit failures can be retried, success is terminal. */
class ScanLookupGate {
    private var busy = false
    private var terminal = false
    @Synchronized fun begin(code: ProductCode): Boolean {
        if (busy || terminal || !code.isValid()) return false
        busy = true; return true
    }
    @Synchronized fun finish(success: Boolean): Boolean {
        val deliver = busy && !terminal
        busy = false; terminal = terminal || success
        return deliver
    }
    @Synchronized fun cancel() { busy = false; terminal = true }
}
