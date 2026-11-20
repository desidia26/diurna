package app.diurna.domain

import app.diurna.data.ExerciseSetEntity

/**
 * Whether the Day screen's Workout card opens folded. Decided only on arriving at a day, never
 * while you log, so finishing the last set doesn't snap the card shut and a tap to reopen it sticks
 * until you leave.
 */
object WorkoutFold {
    const val KEY = "workout"

    /** Every set of every exercise is done; a day with no exercises (or an exercise with no sets) isn't. */
    fun allDone(setsByExercise: List<List<ExerciseSetEntity>>): Boolean =
        setsByExercise.isNotEmpty() && setsByExercise.all { sets -> sets.isNotEmpty() && sets.all { it.isDone } }

    /** [folded] with the Workout card folded exactly when the day's workout is all done. */
    fun onArrival(folded: Set<String>, setsByExercise: List<List<ExerciseSetEntity>>): Set<String> =
        if (allDone(setsByExercise)) folded + KEY else folded - KEY
}
