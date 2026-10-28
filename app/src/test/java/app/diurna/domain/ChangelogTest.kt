package app.diurna.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    @Test fun idsAreUniqueAndIncreaseWithDate() {
        val ids = Changelog.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(ids.sorted(), ids)
        val dates = Changelog.entries.map { LocalDate.parse(it.date) }
        assertEquals(dates.sorted(), dates)
    }

    @Test fun entriesHaveText() {
        assertTrue(Changelog.entries.all { it.title.isNotBlank() && it.body.isNotBlank() })
    }

    @Test fun sinceIsNewerOnlyNewestFirst() {
        assertEquals(Changelog.entries.reversed(), Changelog.since(0))
        assertEquals(emptyList<Changelog.Entry>(), Changelog.since(Changelog.latest))
    }

    @Test fun freshInstallStartsCaughtUpAndAnUpdateSeesEverything() {
        assertEquals(emptyList<Changelog.Entry>(), Changelog.since(Changelog.initialSeen(freshInstall = true)))
        assertEquals(Changelog.entries.size, Changelog.since(Changelog.initialSeen(freshInstall = false)).size)
    }
}
