package app.diurna.debug

import app.diurna.data.recipe.RecipeImportResult
import app.diurna.data.recipe.RecipeImportSource
import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** A pasted recipe link split for logging: [host] is always safe to log, [path] only as typed text. */
data class RecipeUrlParts(val host: String, val path: String?)

private val HOST_RE = Regex("[a-z0-9.-]{1,253}")
private val TOKEN_RE = Regex("[a-z_]{1,32}")

/**
 * Host (lowercase ASCII, no user info or port) and path of a pasted link. The query and fragment
 * are dropped: they can carry share tokens. Anything that doesn't parse logs as host "invalid".
 */
fun recipeUrlParts(raw: String): RecipeUrlParts {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return RecipeUrlParts("invalid", null)
    // URI leaves host null for non-ASCII names, so fall back to the authority minus user info and port.
    val rawHost = uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':') ?: ""
    val host = runCatching { IDN.toASCII(rawHost).lowercase(Locale.US).trimEnd('.') }.getOrNull()
        ?.takeIf { it.matches(HOST_RE) } ?: return RecipeUrlParts("invalid", null)
    val path = uri.rawPath?.takeIf { it.isNotEmpty() && it != "/" }?.take(200)
    return RecipeUrlParts(host, path)
}

/**
 * Logs each recipe import to the debug action log: `recipe_import` STARTED with the host, then
 * OK / FAILED / CANCELLED with the outcome (ok or a fixed failure token), bytes read, whether the
 * page was cut at the cap, ingredient count, HTTP status and latency. The path is sent only as an
 * [Action.Text], which the recorder keeps only when typed-text logging is on. Never the query,
 * page body or error message.
 */
class LoggedRecipeImporter(
    private val delegate: RecipeImportSource,
    private val actions: Store,
    private val nanoTime: () -> Long = System::nanoTime,
) : RecipeImportSource {
    override suspend fun importFromUrl(url: String): RecipeImportResult {
        val id = UUID.randomUUID().toString()
        val start = nanoTime()
        val parts = recipeUrlParts(url)
        actions.dispatch(Action.Event(Screen.LIBRARY, NAME, id, details = mapOf("host" to parts.host)))
        parts.path?.let { actions.dispatch(Action.Text(Screen.LIBRARY, TextField.URL, parts.host + it)) }
        fun ms() = (nanoTime() - start) / 1_000_000
        val result = try {
            delegate.importFromUrl(url)
        } catch (e: Exception) {
            val cancelled = e is CancellationException
            actions.dispatch(Action.Event(Screen.LIBRARY, NAME, id,
                if (cancelled) Action.Result.CANCELLED else Action.Result.FAILED, ms(),
                mapOf("host" to parts.host, "outcome" to if (cancelled) "cancelled" else "error")))
            throw e
        }
        val details = buildMap {
            put("host", parts.host)
            result.info.bytes?.let { put("bytes", it.toString()) }
            if (result.info.truncated) put("truncated", "true")
            result.info.httpStatus?.let { put("http_status", it.toString()) }
            when (result) {
                is RecipeImportResult.Ok -> { put("outcome", "ok"); put("ingredients", result.recipe.ingredients.size.toString()) }
                is RecipeImportResult.Problem -> put("outcome", result.reason.takeIf { it.matches(TOKEN_RE) } ?: "unknown")
            }
        }
        actions.dispatch(Action.Event(Screen.LIBRARY, NAME, id,
            if (result is RecipeImportResult.Ok) Action.Result.OK else Action.Result.FAILED, ms(), details))
        return result
    }

    private companion object { const val NAME = "recipe_import" }
}
