package app.diurna.debug

/**
 * What a food edit did to the entry's photos, for debug reports: counts only, never file names or
 * contents. A report where the photos "go away" shows `photos_removed` the user didn't ask for.
 */
object FoodEditLogging {
    const val OPEN = "food.edit_open"
    const val PHOTOS = "food.edit_photos"

    /** The edit dialog opened showing [shown] photos. */
    fun openDetails(shown: Int): Map<String, String> = mapOf("photos" to shown.toString())

    /** Saving changed the entry's photos from [before] to [after]. */
    fun saveDetails(before: List<String>, after: List<String>): Map<String, String> {
        val b = before.distinct(); val a = after.distinct()
        return mapOf(
            "photos_before" to b.size.toString(),
            "photos_after" to a.size.toString(),
            "photos_added" to (a - b.toSet()).size.toString(),
            "photos_removed" to (b - a.toSet()).size.toString(),
        )
    }

    fun logOpen(store: Store, shown: Int) =
        store.dispatch(Action.Event(Screen.DAY, OPEN, details = openDetails(shown)))

    fun logSave(store: Store, before: List<String>, after: List<String>) =
        store.dispatch(Action.Event(Screen.DAY, PHOTOS, details = saveDetails(before, after)))
}
