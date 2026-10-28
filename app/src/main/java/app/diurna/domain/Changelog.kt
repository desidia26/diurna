package app.diurna.domain

/**
 * What changed, in words for the person using the app, shown once after an update and kept in
 * Setup. Ids, not versions: the version is the commit count, unknown when an entry is written.
 * Add an entry in the same change as the feature, with the next id.
 */
object Changelog {
    data class Entry(val id: Int, val date: String, val title: String, val body: String)

    val entries: List<Entry> = listOf(
        Entry(
            1, "2026-10-27", "View metrics from Day",
            "An exercise's ⋮ menu on Day now has View metrics, which opens its chart and records. Back returns to Day.",
        ),
        Entry(
            2, "2026-10-28", "Macros on Metrics",
            "A Macros card shows your calories over time, split into protein, carbs and fat. Switch between calories and percentages; in calories, a dashed line marks your target.",
        ),
        Entry(
            3, "2026-10-28", "Detailed nutrition",
            "Turn on Detailed nutrition in Setup → Library to add fiber, sugars, starch and the kinds of fat to saved meals. Barcode scans fill them in when the label has them, and Day's food entries can expand to show them. Once fiber is logged, the Macros card shows it separately.",
        ),
        Entry(
            4, "2026-10-28", "Use any OpenAI-compatible AI",
            "Setup → Food estimates has a new OpenAI-compatible option, for services like OpenRouter or a model you run yourself: enter its address, the model name and a key if it needs one. Claude and Grok can also point at a different address. Nothing changes unless you set one up.",
        ),
    )

    val latest: Int get() = entries.maxOf { it.id }

    /** Entries newer than [seen], newest first. */
    fun since(seen: Int): List<Entry> = entries.filter { it.id > seen }.sortedByDescending { it.id }

    /**
     * Where to start counting when nothing has been recorded yet: a fresh install has nothing to
     * catch up on, while an update from before the changelog existed should see every entry.
     */
    fun initialSeen(freshInstall: Boolean): Int = if (freshInstall) latest else 0
}
