package app.diurna.ai

/** Temporary trouble (offline, rate limited, server busy): leave the entry pending and try later. */
class EstimateRetryable(cause: Throwable? = null) : Exception(cause)

/**
 * Turns an API error into what the person should see. Plain function so every case is unit
 * tested without calling the API. Based on platform.claude.com/docs/en/api/errors and
 * .../api/rate-limits (checked 2026-10-13):
 * - A spend limit you set returns 400 invalid_request_error, "You have reached your specified
 *   (workspace) API usage limits…".
 * - The tier's monthly cap returns 429 rate_limit_error with error_code
 *   enforced_spend_limit_reached and no retry-after; retrying fails until access resumes, so it
 *   must not be treated as a rate limit.
 * Both messages say when access resumes; that date is passed through when present.
 */
object EstimateErrors {
    sealed interface Outcome
    data object Retry : Outcome
    data class Fail(val message: String) : Outcome

    const val BAD_KEY = "API key was rejected. Check it in Setup."
    const val NO_CREDIT = "Out of Anthropic credit. Add credit in the Claude Console, then tap Retry."
    const val BILLING = "Billing problem on your Anthropic account. Check payment details in the Claude Console, then tap Retry."
    const val GAVE_UP = "Couldn't reach Claude after several tries. Tap Retry."

    private val RESUME_DATE = Regex("""(?:regain access|access resumes|resume)\D*(\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)

    fun spendLimit(resumes: String?): String =
        "Anthropic spend limit reached" + (resumes?.let { " until $it" } ?: "") +
            ". Raise it in the Claude Console, then tap Retry."

    fun classify(status: Int, message: String?, model: String): Outcome {
        val m = message.orEmpty().lowercase()
        val isSpendLimit = "enforced_spend_limit_reached" in m || "api usage limits" in m ||
            "usage limit" in m || "spend limit" in m
        return when {
            isSpendLimit -> Fail(spendLimit(RESUME_DATE.find(message.orEmpty())?.groupValues?.get(1)))
            "credit balance" in m || "insufficient credit" in m -> Fail(NO_CREDIT)
            status == 402 -> Fail(BILLING)
            status == 401 -> Fail(BAD_KEY)
            status == 403 -> Fail("This API key isn't allowed to use Claude. Check its workspace in the Claude Console.")
            status == 404 -> Fail("$model isn't available to this API key.")
            status == 413 -> Fail("This entry is too long to estimate. Shorten it, then it will retry.")
            status == 400 || status == 422 -> Fail("Claude couldn't process this entry. Try rewording it.")
            status == 408 || status == 409 || status == 429 || status >= 500 -> Retry
            else -> Fail("Unexpected error from Claude (HTTP $status). Tap Retry.")
        }
    }
}
