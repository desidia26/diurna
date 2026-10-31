package app.diurna.data

import android.content.res.AssetManager
import app.diurna.domain.Changelog

/** Reads the What's new entries bundled in assets/changelog (see [Changelog]). */
object ChangelogFiles {
    const val DIR = "changelog"

    /** Every entry, newest first. A file that isn't an entry is skipped rather than shown. */
    fun load(assets: AssetManager): List<Changelog.Entry> =
        Changelog.newestFirst(
            assets.list(DIR).orEmpty().mapNotNull { name ->
                Changelog.parse(name, assets.open("$DIR/$name").bufferedReader().use { it.readText() })
            },
        )
}
