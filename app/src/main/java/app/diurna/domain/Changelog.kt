package app.diurna.domain

/**
 * What changed, in words for the person using the app, shown once after an update and kept in
 * Setup. Each entry is its own file in assets/changelog, named `yyyy-MM-dd-short-name.md`: the
 * first line is the title, the rest the body. One file per change means parallel PRs never edit
 * the same file, and the name is the id, so there's nothing to number. What's been shown is a set
 * of names, so an entry that merges late still shows up.
 */
object Changelog {
    data class Entry(val id: String, val date: String, val title: String, val body: String)

    private val FILE_NAME = Regex("""(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md""")

    /** The entry in [text], or null if [fileName] isn't an entry's name or the text has no title. */
    fun parse(fileName: String, text: String): Entry? {
        val date = FILE_NAME.matchEntire(fileName)?.groupValues?.get(1) ?: return null
        val lines = text.trim().lines()
        val title = lines.firstOrNull()?.trim().orEmpty()
        val body = lines.drop(1).joinToString(" ") { it.trim() }.trim()
        if (title.isEmpty() || body.isEmpty()) return null
        return Entry(fileName.removeSuffix(".md"), date, title, body)
    }

    /** Newest first: names start with the date, and same-day entries keep a stable order. */
    fun newestFirst(entries: List<Entry>): List<Entry> = entries.sortedByDescending { it.id }

    fun unseen(entries: List<Entry>, seen: Set<String>): List<Entry> = newestFirst(entries.filter { it.id !in seen })

    /**
     * What counts as seen when nothing is recorded yet: on a fresh install everything (there's
     * nothing to catch up on); after an update from before the changelog existed, nothing.
     */
    fun initialSeen(entries: List<Entry>, freshInstall: Boolean): Set<String> =
        if (freshInstall) entries.map { it.id }.toSet() else emptySet()

    /**
     * Before entries were files they were numbered 1 to 16, and installs stored the highest number
     * shown. These are those entries' names, in the old order.
     */
    private val NUMBERED = listOf(
        "2026-10-27-view-metrics-from-day",
        "2026-10-28-macros-on-metrics",
        "2026-10-28-detailed-nutrition",
        "2026-10-28-detailed-nutrition-from-ai-estimates",
        "2026-10-28-carbs-and-fats-on-metrics",
        "2026-10-28-amino-acids",
        "2026-10-28-fill-in-detail-for-past-entries",
        "2026-10-28-recipes",
        "2026-10-28-fill-a-meal-from-its-label",
        "2026-10-28-restaurant-meals",
        "2026-10-28-barcodes-stay-with-what-you-log",
        "2026-10-28-several-photos-per-meal",
        "2026-10-28-import-a-recipe-from-a-link",
        "2026-10-28-use-any-openai-compatible-ai",
        "2026-10-30-export-and-import-everything",
        "2026-10-31-the-alarm-rings-again",
    )

    fun seenFromNumber(highest: Int): Set<String> = NUMBERED.take(highest.coerceIn(0, NUMBERED.size)).toSet()
}
