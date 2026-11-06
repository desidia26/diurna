package app.diurna.ui.web

/** Debug-only gate for the experimental Svelte Week preview. */
object SvelteWeekGate {
    fun active(debugEnabled: Boolean, preferenceEnabled: Boolean): Boolean =
        debugEnabled && preferenceEnabled

    fun toggleVisible(debugEnabled: Boolean): Boolean = debugEnabled
}
