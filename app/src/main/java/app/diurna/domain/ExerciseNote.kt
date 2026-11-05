package app.diurna.domain

/** An exercise's standing note: a few short lines to read mid-workout (seat height, cues, an injury to work around). */
object ExerciseNote {
    const val MAX_CHARS = 200
    const val MAX_LINES = 6

    /**
     * What gets stored: line endings normalized, trailing spaces and runs of blank lines dropped,
     * capped at [MAX_LINES] lines and [MAX_CHARS] characters. Null when nothing is left, which clears the note.
     */
    fun clean(raw: String): String? {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }
        val kept = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank() && (kept.isEmpty() || kept.last().isBlank())) continue
            kept += line
        }
        return kept.take(MAX_LINES).joinToString("\n").trim().take(MAX_CHARS).trimEnd().ifEmpty { null }
    }
}
