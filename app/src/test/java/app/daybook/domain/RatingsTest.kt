package app.daybook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingsTest {
    @Test fun summaryShowsBothOrWhatsMissing() {
        assertEquals("Not rated yet", Ratings.summary(null, null))
        assertEquals("Mood 4 good · Energy 2 low", Ratings.summary(4, 2))
        assertEquals("Mood 1 rough · Energy not rated", Ratings.summary(1, null))
    }

    @Test fun wordsFollowTheScale() {
        assertEquals("great", Ratings.word(Ratings.moodWords, 5))
        assertEquals("drained", Ratings.word(Ratings.energyWords, 1))
        assertNull(Ratings.word(Ratings.moodWords, null))
    }
}
