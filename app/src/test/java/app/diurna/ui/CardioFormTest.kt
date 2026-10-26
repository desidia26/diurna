package app.diurna.ui

import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardioFormTest {
    private val row = Cardio.Style.ROW

    @Test fun typedRowFieldsBecomeAPiece() {
        val f = CardioForm.Fields(distance = "3,260", time = "15:07", pace = "2:10", bestSplit = "1:44", watts = "158", kcal = "120")
        assertEquals(CardioForm.Ok(CardioPiece(3260, 907.0, 130.0, 104.0, 158, 120, null)), CardioForm.parse(f, row))
    }

    @Test fun roundTripsAPieceIncludingTenths() {
        val p = CardioPiece(3260, 907.0, 130.0, 104.5, 158, 120, 141)
        assertEquals("1:44.5", CardioForm.fields(p, row).bestSplit)
        assertEquals(CardioForm.Ok(p), CardioForm.parse(CardioForm.fields(p, row), row))
    }

    @Test fun aRunIsTypedInKilometresAndPacePerKilometre() {
        val r = CardioForm.parse(CardioForm.Fields(distance = "5", time = "26:00", pace = "5:12"), Cardio.Style.RUN_KM) as CardioForm.Ok
        assertEquals(5000, r.piece.distanceM)
        assertEquals(156.0, r.piece.splitS!!, 1e-9)
        assertEquals("5", CardioForm.fields(r.piece, Cardio.Style.RUN_KM).distance)
        assertEquals("5:12", CardioForm.fields(r.piece, Cardio.Style.RUN_KM).pace)
    }

    @Test fun aRideIsTypedInMilesAndSpeed() {
        val r = CardioForm.parse(CardioForm.Fields(distance = "10", time = "37:00", pace = "16.2", watts = "180"), Cardio.Style.RIDE_MI) as CardioForm.Ok
        assertEquals(16093, r.piece.distanceM)
        assertEquals(16.2, Cardio.Pace.MPH.fromSplit(r.piece.splitS!!)!!, 1e-9)
        assertEquals("16.2", CardioForm.fields(r.piece, Cardio.Style.RIDE_MI).pace)
        assertEquals(CardioForm.Problem("Avg speed should be a number"), CardioForm.parse(CardioForm.Fields(time = "37:00", pace = "fast"), Cardio.Style.RIDE_MI))
    }

    @Test fun timeOnlyAsksForTimeAndKeepsWhatItDoesntShow() {
        val l = CardioForm.labels(Cardio.Style.TIME)
        assertNull(l.distance)
        assertNull(l.pace)
        assertEquals(CardioForm.Ok(CardioPiece(durationS = 1800.0, kcal = 250)), CardioForm.parse(CardioForm.Fields(time = "30:00", kcal = "250"), Cardio.Style.TIME))
        assertEquals(CardioForm.Problem("Enter a time"), CardioForm.parse(CardioForm.Fields(kcal = "250"), Cardio.Style.TIME))
        val keep = CardioPiece(distanceM = 4000, durationS = 1800.0)
        assertEquals(4000, (CardioForm.parse(CardioForm.Fields(time = "30:00"), Cardio.Style.TIME, keep) as CardioForm.Ok).piece.distanceM)
    }

    @Test fun labelsFitTheActivity() {
        assertEquals("Avg split", CardioForm.labels(row).pace)
        assertEquals("Avg pace", CardioForm.labels(Cardio.Style.RUN_MI).pace)
        assertEquals("Avg speed", CardioForm.labels(Cardio.Style.RIDE_KM).pace)
        assertEquals(false, CardioForm.labels(Cardio.Style.RUN_MI).watts)
    }

    @Test fun needsADistanceOrATime() {
        assertEquals(CardioForm.Problem("Enter a distance or a time"), CardioForm.parse(CardioForm.Fields(watts = "150"), row))
        assertEquals(CardioForm.Ok(CardioPiece(durationS = 1200.0)), CardioForm.parse(CardioForm.Fields(time = "20:00"), row))
    }

    @Test fun badEntriesSayWhichField() {
        assertEquals(CardioForm.Problem("Time should look like 15:07"), CardioForm.parse(CardioForm.Fields(distance = "2000", time = "15m"), row))
        assertEquals(CardioForm.Problem("Distance should be a whole number"), CardioForm.parse(CardioForm.Fields(distance = "3.2km"), row))
        assertEquals(CardioForm.Problem("Distance should be a number"), CardioForm.parse(CardioForm.Fields(distance = "3.2km"), Cardio.Style.RUN_KM))
    }
}
