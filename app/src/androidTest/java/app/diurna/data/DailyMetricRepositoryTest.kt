package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.data.LibraryRepository.Result
import app.diurna.domain.DailyMetrics
import app.diurna.domain.DailyMetrics.Def
import app.diurna.domain.DailyMetrics.Kind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyMetricRepositoryTest {
    private val t = TestDb()
    private val mon = t.mon
    private val repo = t.dailyMetrics

    @After fun tearDown() = t.close()

    private suspend fun defs() = repo.definitions.first()
    private suspend fun named(name: String) = defs().first { it.name == name }
    private suspend fun values() = repo.observeValues(mon).first()

    @Test fun newInstallsStartWithMoodAndEnergy() = runTest {
        val d = defs()
        assertEquals(listOf("Mood", "Energy"), d.map { it.name })
        assertTrue(d.all { it.kind == Kind.SCALE && it.scaleMax == 5 })
        assertEquals(DailyMetrics.moodWords, d[0].words)
    }

    @Test fun valuesSaveClearAndStayEditableOnFinalizedDays() = runTest {
        val changed = mutableListOf<java.time.LocalDate>()
        repo.onValueChanged = { changed += it }
        val mood = named("Mood").id
        val energy = named("Energy").id
        repo.setValue(mon, mood, 4.0)
        assertFalse("energy still missing", repo.isComplete(mon))
        assertEquals(listOf("Energy"), repo.missing(mon).map { it.name })
        repo.setValue(mon, energy, 5.0)
        assertTrue(repo.isComplete(mon))
        assertTrue("the day exists for Metrics' All range", t.days.observeDay(mon).first() != null)

        t.db.dayDao().finalize(mon.toString(), "06:00", null, null, null, null, null, null)
        repo.setValue(mon, mood, null)
        assertNull(values()[mood])
        assertEquals(5.0, values()[energy]!!.value!!, 0.0)
        repo.setValue(mon, energy, 9.0)
        assertNull("off the scale clears rather than clamps", values()[energy])
        assertEquals(listOf(mon, mon, mon, mon), changed)
    }

    @Test fun eachKindStoresItsOwnValue() = runTest {
        assertEquals(Result.Ok, repo.add(Def(0, "Sleep", Kind.NUMBER, unit = " h ")))
        assertEquals(Result.Ok, repo.add(Def(0, "Meds", Kind.YES_NO)))
        assertEquals(Result.Ok, repo.add(Def(0, "Journal", Kind.TEXT)))
        val sleep = named("Sleep")
        assertEquals("h", sleep.unit)
        assertEquals("added at the end", listOf("Mood", "Energy", "Sleep", "Meds", "Journal"), defs().map { it.name })

        repo.setValue(mon, sleep.id, 7.25)
        repo.setValue(mon, named("Meds").id, 0.0)
        repo.setText(mon, named("Journal").id, "  long walk ")
        repo.setValue(mon, named("Journal").id, 3.0)
        repo.setText(mon, sleep.id, "not a number")
        val v = values()
        assertEquals(7.25, v[sleep.id]!!.value!!, 0.0)
        assertEquals(0.0, v[named("Meds").id]!!.value!!, 0.0)
        assertEquals("long walk", v[named("Journal").id]!!.text)
        assertNull(v[named("Journal").id]!!.value)
        assertNull(v[sleep.id]!!.text)
    }

    @Test fun namesAreUniqueIgnoringCase() = runTest {
        assertTrue(repo.add(Def(0, "mood", Kind.NUMBER)) is Result.Problem)
        assertTrue(repo.add(Def(0, "", Kind.NUMBER)) is Result.Problem)
        val energy = named("Energy")
        assertTrue(repo.update(energy.copy(name = "MOOD")) is Result.Problem)
        assertEquals(Result.Ok, repo.update(energy.copy(name = "Vigour")))
    }

    @Test fun typeAndStepsLockOnceLoggedButRenamingAndRewordingDont() = runTest {
        val mood = named("Mood")
        assertEquals("unlogged: anything goes", Result.Ok, repo.update(mood.copy(scaleMax = 7, words = emptyList())))
        assertEquals(Result.Ok, repo.update(named("Mood").copy(scaleMax = 5, words = DailyMetrics.moodWords)))
        repo.setValue(mon, mood.id, 3.0)
        assertTrue(repo.update(mood.copy(scaleMax = 10, words = emptyList())) is Result.Problem)
        assertTrue(repo.update(mood.copy(kind = Kind.NUMBER)) is Result.Problem)
        val words = listOf("awful", "low", "ok", "good", "great")
        assertEquals(Result.Ok, repo.update(mood.copy(name = "Feeling", words = words)))
        val after = named("Feeling")
        assertEquals(words, after.words)
        assertEquals("history kept", 3.0, values()[after.id]!!.value!!, 0.0)
        assertEquals(1, repo.rows.first().first { it.def.id == mood.id }.days)
    }

    @Test fun archiveHidesFromTheCheckInAndDeleteTakesTheValues() = runTest {
        val mood = named("Mood")
        val energy = named("Energy")
        repo.setValue(mon, mood.id, 3.0)
        repo.setValue(mon, energy.id, 3.0)
        repo.setArchived(energy.id, true, mon)
        assertTrue(named("Energy").archived)
        assertEquals("archived values stay", 3.0, values()[energy.id]!!.value!!, 0.0)
        repo.setValue(t.mon.plusDays(1), mood.id, 4.0)
        assertTrue("archived ones don't hold the check-in open", repo.isComplete(t.mon.plusDays(1)))

        repo.delete(mood.id, mon)
        assertEquals(listOf("Energy"), defs().map { it.name })
        assertNull(values()[mood.id])
        assertEquals(0, t.db.dailyMetricDao().valueCount(mood.id))
    }

    @Test fun moveSwapsNeighbours() = runTest {
        repo.add(Def(0, "Sleep", Kind.NUMBER))
        repo.move(named("Sleep").id, up = true)
        assertEquals(listOf("Mood", "Sleep", "Energy"), defs().map { it.name })
        repo.move(named("Mood").id, up = true)
        assertEquals("nothing above the top", listOf("Mood", "Sleep", "Energy"), defs().map { it.name })
        repo.delete(named("Sleep").id, mon)
        repo.move(named("Energy").id, up = true)
        assertEquals(listOf("Energy", "Mood"), defs().map { it.name })
    }
}
