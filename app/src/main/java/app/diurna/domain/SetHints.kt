package app.diurna.domain

import app.diurna.data.ExerciseSetEntity

/** The grey hint in an empty set field; pressing Enter on the empty field accepts it. */
object SetHints {
    data class Hint(val weight: Double?, val reps: Int?)

    /**
     * Weight: the set above this session when it's filled (sets usually share a weight), else last
     * time's set at the same position, else last time's final set.
     * Reps: last time's set at the same position, else its final set, else the set above.
     */
    fun hint(index: Int, session: List<ExerciseSetEntity>, last: List<ExerciseSetEntity>): Hint {
        val above = session.getOrNull(index - 1)
        val lastSet = last.getOrNull(index) ?: last.lastOrNull()
        return Hint(
            weight = above?.weight ?: lastSet?.weight,
            reps = lastSet?.reps ?: above?.reps,
        )
    }
}
