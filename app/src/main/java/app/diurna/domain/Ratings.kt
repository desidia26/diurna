package app.diurna.domain

/** The evening check-in's two 1..5 scales, with a word per step so ratings mean the same thing over months. */
object Ratings {
    val moodWords = listOf("rough", "meh", "fine", "good", "great")
    val energyWords = listOf("drained", "low", "okay", "good", "high")

    fun word(words: List<String>, value: Int?): String? = value?.let { words.getOrNull(it - 1) }

    /** "Mood 4 good · Energy 2 low", or what's still missing. */
    fun summary(mood: Int?, energy: Int?): String {
        if (mood == null && energy == null) return "Not rated yet"
        fun part(name: String, words: List<String>, v: Int?) = if (v == null) "$name not rated" else "$name $v ${word(words, v)}"
        return part("Mood", moodWords, mood) + " · " + part("Energy", energyWords, energy)
    }
}
