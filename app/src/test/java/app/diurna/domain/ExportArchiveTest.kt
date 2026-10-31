package app.diurna.domain

import app.diurna.domain.ExportArchive.Kind
import app.diurna.domain.ExportArchive.Manifest
import app.diurna.domain.ExportArchive.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportArchiveTest {
    private val photo = "0f8fad5b-d9cb-469f-a165-70867728950e.jpg"

    @Test fun knownNamesOnly() {
        assertEquals(Kind.MANIFEST, ExportArchive.kindOf("manifest.json"))
        assertEquals(Kind.DATABASE, ExportArchive.kindOf("daybook.db"))
        assertEquals(Kind.SETTINGS, ExportArchive.kindOf("settings.preferences_pb"))
        assertEquals(Kind.PHOTO, ExportArchive.kindOf("food-photos/$photo"))
        listOf(
            "../daybook.db", "/daybook.db", "food-photos/../daybook.db", "food-photos/../../x.jpg",
            "food-photos/", "food-photos/evil.jpg", "food-photos/sub/$photo", "daybook.db-wal", "Daybook.db",
        ).forEach { assertNull(it, ExportArchive.kindOf(it)) }
    }

    @Test fun eachKindHasACap() {
        assertEquals(PhotoSizing.MAX_BYTES.toLong(), ExportArchive.maxBytes(Kind.PHOTO))
        assertEquals(ExportArchive.MAX_DATABASE, ExportArchive.maxBytes(Kind.DATABASE))
    }

    @Test fun manifestRoundTrips() {
        val m = Manifest(1, 15, 113, "2026-10-28T09:30")
        assertEquals(m, ExportArchive.parseManifest(ExportArchive.manifestJson(m)))
    }

    @Test fun badManifestsAreNotExports() {
        assertNull(ExportArchive.parseManifest("not json"))
        assertNull(ExportArchive.parseManifest("""{"format":1}"""))
        assertEquals(Problem.NotAnExport, ExportArchive.check(null, 15))
        assertEquals(Problem.NotAnExport, ExportArchive.check(Manifest(1, 0, 1, "x"), 15))
    }

    @Test fun olderSchemasImportAndNewerOnesDont() {
        assertNull(ExportArchive.check(Manifest(1, 9, 61, "x"), 15))
        assertNull(ExportArchive.check(Manifest(1, 15, 113, "x"), 15))
        assertEquals(Problem.Newer, ExportArchive.check(Manifest(1, 16, 120, "x"), 15))
        assertEquals(Problem.Newer, ExportArchive.check(Manifest(2, 15, 120, "x"), 15))
    }
}
