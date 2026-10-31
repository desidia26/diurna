package app.diurna.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    // Unit tests run from the repo root (see app/build.gradle.kts).
    private val files = File("app/src/main/assets/changelog").listFiles().orEmpty().sortedBy { it.name }
    private val entries = files.mapNotNull { Changelog.parse(it.name, it.readText()) }

    @Test fun everyFileIsAnEntry() {
        assertTrue(files.isNotEmpty())
        files.forEach { assertNotNull("${it.name}: name must be yyyy-MM-dd-short-name.md, title line, then body", Changelog.parse(it.name, it.readText())) }
    }

    @Test fun parsesTitleAndJoinsTheBody() {
        val e = Changelog.parse("2026-11-01-new-thing.md", "New thing\n\nIt does\nthis.\n")!!
        assertEquals(Changelog.Entry("2026-11-01-new-thing", "2026-11-01", "New thing", "It does this."), e)
        assertNull(Changelog.parse("new-thing.md", "Title\n\nBody"))
        assertNull(Changelog.parse("2026-11-01-New_Thing.md", "Title\n\nBody"))
        assertNull(Changelog.parse("2026-11-01-no-body.md", "Only a title\n"))
    }

    @Test fun newestFirstAndUnseen() {
        val a = Changelog.Entry("2026-10-27-a", "2026-10-27", "A", "a")
        val b = Changelog.Entry("2026-10-30-b", "2026-10-30", "B", "b")
        val c = Changelog.Entry("2026-10-31-c", "2026-10-31", "C", "c")
        assertEquals(listOf(c, b, a), Changelog.newestFirst(listOf(a, c, b)))
        // An entry that merged late still shows, even though a newer one was seen.
        assertEquals(listOf(b), Changelog.unseen(listOf(a, b, c), setOf("2026-10-27-a", "2026-10-31-c")))
    }

    @Test fun freshInstallStartsCaughtUpAndAnUpdateSeesEverything() {
        assertEquals(emptyList<Changelog.Entry>(), Changelog.unseen(entries, Changelog.initialSeen(entries, freshInstall = true)))
        assertEquals(entries.size, Changelog.unseen(entries, Changelog.initialSeen(entries, freshInstall = false)).size)
    }

    @Test fun oldNumbersCarryOverToNames() {
        val ids = entries.map { it.id }.toSet()
        val all = Changelog.seenFromNumber(16)
        assertEquals(16, all.size)
        assertTrue("every numbered entry still has its file", ids.containsAll(all))
        assertEquals(setOf("2026-10-31-the-alarm-rings-again"), Changelog.seenFromNumber(16) - Changelog.seenFromNumber(15))
        assertEquals(emptySet<String>(), Changelog.seenFromNumber(0))
        assertEquals(all, Changelog.seenFromNumber(99))
    }
}
