package app.diurna.data

/**
 * Whether an edit to a food entry gets a new AI estimate, and why (for the debug log: a fixed
 * reason token and photo counts, never text or photo names).
 */
internal object EditReestimate {
    enum class Reason(val token: String) { TEXT("text"), PHOTOS("photos"), TEXT_AND_PHOTOS("text_and_photos") }

    data class Decision(val reason: Reason, val added: Int, val removed: Int, val moved: Int) {
        fun details(): Map<String, String> = mapOf(
            "reason" to reason.token,
            "photos_added" to added.toString(),
            "photos_removed" to removed.toString(),
            "photos_moved" to moved.toString(),
        )
    }

    /** [before]/[after] are the entry's photos in gallery order. Null: keep the current numbers. */
    fun decide(old: FoodEntryEntity, newText: String, before: List<String>, after: List<String>): Decision? {
        val textChanged = newText != old.text
        // Every photo informs the estimate, so any change to the set or its order re-estimates an AI
        // entry. A saved meal's numbers are exact; its photos are just for the record.
        val photosChanged = before.distinct() != after.distinct() && old.source == FoodEntryEntity.SOURCE_AI
        val reason = when {
            textChanged && photosChanged -> Reason.TEXT_AND_PHOTOS
            textChanged -> Reason.TEXT
            photosChanged -> Reason.PHOTOS
            else -> return null
        }
        val b = before.distinct(); val a = after.distinct()
        val kept = a.filter { it in b }
        return Decision(reason, added = (a - b.toSet()).size, removed = (b - a.toSet()).size,
            moved = kept.withIndex().count { (i, name) -> b.filter { it in a }.getOrNull(i) != name })
    }
}
