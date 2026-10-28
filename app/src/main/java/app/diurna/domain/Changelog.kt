package app.diurna.domain

/**
 * What changed, in words for the person using the app, shown once after an update and kept in
 * Setup. Ids, not versions: the version is the commit count, unknown when an entry is written.
 * Add an entry in the same change as the feature, with the next id.
 */
object Changelog {
    data class Entry(val id: Int, val date: String, val title: String, val body: String)

    val entries: List<Entry> = listOf(
        Entry(
            1, "2026-10-27", "View metrics from Day",
            "An exercise's ⋮ menu on Day now has View metrics, which opens its chart and records. Back returns to Day.",
        ),
        Entry(
            2, "2026-10-28", "Macros on Metrics",
            "A Macros card shows your calories over time, split into protein, carbs and fat. Switch between calories and percentages; in calories, a dashed line marks your target.",
        ),
        Entry(
            3, "2026-10-28", "Detailed nutrition",
            "Turn on Detailed nutrition in Setup → Library to add fiber, sugars, starch and the kinds of fat to saved meals. Barcode scans fill them in when the label has them, and Day's food entries can expand to show them. Once fiber is logged, the Macros card shows it separately.",
        ),
        Entry(
            4, "2026-10-28", "Detailed nutrition from AI estimates",
            "With Detailed nutrition on, food you describe or photograph is now estimated for fiber, sugars, starch and the kinds of fat too, not just calories and macros. Anything the AI can't judge is left blank instead of guessed as zero.",
        ),
        Entry(
            5, "2026-10-28", "Carbs and fats on Metrics",
            "With Detailed nutrition on, Metrics adds a Carbs card (fiber, starch, sugars), a Fats card (saturated, mono, poly, trans) and a summary of omegas and cholesterol. Days without that detail show as gaps, and each card says how many days it covers.",
        ),
        Entry(
            6, "2026-10-28", "Amino acids",
            "With Detailed nutrition on and a free USDA FoodData Central key added in Setup, food entries get essential amino acids, worked out from USDA's data for each ingredient rather than guessed by the AI. When only some ingredients match, the entry says which ones were missed.",
        ),
        Entry(
            7, "2026-10-28", "Fill in detail for past entries",
            "With Detailed nutrition on, Setup can fill in fiber, sugars, fats and amino acids for food you logged before, over the last 30 days or everything. It shows how many AI calls that takes before you start, and never changes the calories or macros already recorded.",
        ),
        Entry(
            8, "2026-10-28", "Recipes",
            "Saved meals can now be recipes: add ingredients by hand, from a barcode, from another saved item or from USDA's database, and the totals per serving add up as you go. Log a recipe by servings, or by grams if you weighed the cooked dish. Library can show meals and recipes separately.",
        ),
        Entry(
            9, "2026-10-28", "Fill a meal from its label",
            "When adding or editing a saved meal, Fill from label photo reads a Nutrition Facts panel with the AI you picked and fills in the name, serving and numbers for you to check. Nothing is saved until you tap Save, and the photo is deleted after reading.",
        ),
        Entry(
            10, "2026-10-28", "Restaurant meals",
            "Saved meals can be marked as restaurant meals, with the restaurant's name and whether the numbers are published by the restaurant or estimated. Estimate from menu photo fills them in from a picture of the menu. Library has a Restaurants filter.",
        ),
        Entry(
            11, "2026-10-28", "Barcodes stay with what you log",
            "A scanned barcode is now kept on the food entry along with Open Food Facts' data for it. If a product isn't found or the lookup fails, you get a form to type the numbers in, and the barcode is still saved. Filling in detail later uses the product's own data before asking the AI.",
        ),
        Entry(
            12, "2026-10-28", "Several photos per meal",
            "Food entries and saved meals can now hold up to 5 photos. The first one is the one sent for the estimate.",
        ),
        Entry(
            13, "2026-10-28", "Import a recipe from a link",
            "Import URL on the Meals page reads a recipe from a web page and opens the recipe editor with its name, servings and ingredients filled in. Nutrition is left for you to look up per ingredient, and nothing is saved until you tap Save.",
        ),
        Entry(
            14, "2026-10-28", "Use any OpenAI-compatible AI",
            "Setup → Food estimates has a new OpenAI-compatible option, for services like OpenRouter or a model you run yourself: enter its address, the model name and a key if it needs one. Claude and Grok can also point at a different address. Nothing changes unless you set one up.",
        ),
    )

    val latest: Int get() = entries.maxOf { it.id }

    /** Entries newer than [seen], newest first. */
    fun since(seen: Int): List<Entry> = entries.filter { it.id > seen }.sortedByDescending { it.id }

    /**
     * Where to start counting when nothing has been recorded yet: a fresh install has nothing to
     * catch up on, while an update from before the changelog existed should see every entry.
     */
    fun initialSeen(freshInstall: Boolean): Int = if (freshInstall) latest else 0
}
