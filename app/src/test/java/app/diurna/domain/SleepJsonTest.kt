package app.diurna.domain

import app.diurna.domain.SleepRules.Point
import app.diurna.domain.SleepRules.StageSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepJsonTest {
    @Test fun stagesRoundTrip() {
        val stages = listOf(StageSpan(0, 600, 1), StageSpan(600, 1200, 4), StageSpan(1200, 3900, 5))
        val json = SleepJson.stages(stages)
        assertEquals("[[0,600,1],[600,1200,4],[1200,3900,5]]", json)
        assertEquals(stages, SleepJson.parseStages(json))
    }

    @Test fun pointsKeepOneDecimal() {
        val points = listOf(Point(0, 61.666), Point(300, 58.0))
        val json = SleepJson.points(points)
        assertEquals("[[0,61.7],[300,58]]", json)
        assertEquals(listOf(Point(0, 61.7), Point(300, 58.0)), SleepJson.parsePoints(json))
    }

    @Test fun emptyListsAreNull() {
        assertEquals(null, SleepJson.stages(emptyList()))
        assertEquals(null, SleepJson.points(emptyList()))
        assertEquals(emptyList<StageSpan>(), SleepJson.parseStages(null))
    }

    @Test fun damagedTextReadsAsNothingRatherThanCrashing() {
        assertEquals(emptyList<StageSpan>(), SleepJson.parseStages("[[0,600"))
        assertEquals(emptyList<Point>(), SleepJson.parsePoints("not json"))
        assertEquals(listOf(StageSpan(0, 600, 1)), SleepJson.parseStages("[[0,600,1],[5,x,2]]"))
    }
}
