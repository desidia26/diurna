package app.diurna.domain

/**
 * Autocomplete matches for [query] from [names], which arrive most-used first.
 * Names starting with the query come before names merely containing it; within each group the
 * incoming (usage) order is kept. An exact match isn't suggested, since there's nothing to complete.
 */
fun suggest(query: String, names: List<String>, limit: Int = 6): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val candidates = names.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
    val (prefix, rest) = candidates.partition { it.startsWith(q, ignoreCase = true) }
    return (prefix + rest).take(limit)
}
