package app.diurna.data.recipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

/**
 * Hardened HTTPS GET for untrusted user-supplied URLs (recipe pages).
 *
 * Guarantees (defense in depth — Android already blocks cleartext by default):
 * - HTTPS only; http / file / content / data / javascript rejected.
 * - No userinfo (embedded credentials) in the URL.
 * - Host resolves to a public address; private, loopback, link-local, ULA, CGNAT,
 *   multicast, and unspecified addresses are refused **before connect and again
 *   for every redirect hop**.
 * - Redirects are followed manually with a hard cap; each Location is re-validated.
 * - No cookies are sent or stored (no Cookie / Cookie2 header; CookieHandler unused).
 * - Response body capped; total wall-clock deadline; connect/read timeouts.
 * - Does not attach Authorization or any app secrets — this path never sees API keys.
 */
object SafeHttp {
    const val MAX_REDIRECTS = 5
    const val MAX_BYTES = 512 * 1024
    const val DEADLINE_MS = 20_000L
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000

    /**
     * Fetches [urlString] under the rules above. Returns UTF-8 body text on HTTP 200.
     * Throws [SafeHttpException] for policy / network failures (message is safe to show).
     */
    suspend fun get(urlString: String): String = withContext(Dispatchers.IO) {
        var current = validatePublicHttpsUri(urlString)
        var redirects = 0
        while (true) {
            assertPublicHost(current.host)
            val bodyOrRedirect = getOnce(current)
            when (bodyOrRedirect) {
                is FetchOk -> return@withContext bodyOrRedirect.body
                is FetchRedirect -> {
                    redirects++
                    if (redirects > MAX_REDIRECTS) {
                        throw SafeHttpException("Too many redirects")
                    }
                    current = validatePublicHttpsUri(resolveRedirect(current, bodyOrRedirect.location))
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * Validates scheme/host/userinfo and that every resolved address is public.
     * Exposed for unit tests (no network I/O beyond DNS when [resolve] is true).
     */
    fun validatePublicHttpsUri(raw: String, resolve: Boolean = true): URI {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw SafeHttpException("Enter a recipe URL")
        if (trimmed.length > 2_048) throw SafeHttpException("URL is too long")
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw SafeHttpException("That doesn't look like a URL")
        }
        if (!uri.isAbsolute) throw SafeHttpException("URL must start with https://")
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "https") throw SafeHttpException("Only https:// links are allowed")
        if (!uri.userInfo.isNullOrEmpty()) {
            throw SafeHttpException("URLs with embedded credentials are not allowed")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw SafeHttpException("URL is missing a host")
        // Reject obvious local names before DNS.
        val hostLower = host.lowercase()
        if (hostLower == "localhost" || hostLower.endsWith(".localhost") ||
            hostLower == "metadata.google.internal"
        ) {
            throw SafeHttpException("That host is not allowed")
        }
        if (resolve) assertPublicHost(host)
        return uri.normalize()
    }

    /** True when [addr] must not be contacted (SSRF). */
    fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress) return true
        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isSiteLocalAddress) return true
        if (addr.isMulticastAddress) return true
        when (addr) {
            is Inet4Address -> {
                val b = addr.address
                val a0 = b[0].toInt() and 0xff
                val a1 = b[1].toInt() and 0xff
                // 0.0.0.0/8 "this" network
                if (a0 == 0) return true
                // CGNAT 100.64.0.0/10
                if (a0 == 100 && a1 in 64..127) return true
                // Benchmark 198.18.0.0/15
                if (a0 == 198 && a1 in 18..19) return true
                // Documentation 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24
                if (a0 == 192 && a1 == 0 && (b[2].toInt() and 0xff) == 2) return true
                if (a0 == 198 && a1 == 51 && (b[2].toInt() and 0xff) == 100) return true
                if (a0 == 203 && a1 == 0 && (b[2].toInt() and 0xff) == 113) return true
                // IETF protocol assignments 192.0.0.0/24
                if (a0 == 192 && a1 == 0 && (b[2].toInt() and 0xff) == 0) return true
                // Broadcast
                if (a0 == 255) return true
            }
            is Inet6Address -> {
                val b = addr.address
                // Unique local fc00::/7
                if ((b[0].toInt() and 0xfe) == 0xfc) return true
                // IPv4-mapped :ffff:x.x.x.x — re-check the embedded v4
                if (addr.isIPv4CompatibleAddress || isIpv4Mapped(b)) {
                    val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
                    if (isBlockedAddress(v4)) return true
                }
            }
        }
        return false
    }

    private fun isIpv4Mapped(b: ByteArray): Boolean {
        if (b.size != 16) return false
        for (i in 0 until 10) if (b[i].toInt() != 0) return false
        return b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
    }

    fun assertPublicHost(host: String) {
        val ascii = try {
            IDN.toASCII(host)
        } catch (e: Exception) {
            throw SafeHttpException("Invalid host name")
        }
        // Literal IP in the host — check directly without DNS.
        val literal = parseLiteralIp(ascii)
        if (literal != null) {
            if (isBlockedAddress(literal)) throw SafeHttpException("That address is not allowed")
            return
        }
        val addrs = try {
            InetAddress.getAllByName(ascii)
        } catch (e: UnknownHostException) {
            throw SafeHttpException("Couldn't resolve that host")
        }
        if (addrs.isEmpty()) throw SafeHttpException("Couldn't resolve that host")
        for (a in addrs) {
            if (isBlockedAddress(a)) throw SafeHttpException("That host resolves to a private address")
        }
    }

    private fun parseLiteralIp(host: String): InetAddress? = try {
        when {
            host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) -> InetAddress.getByName(host)
            host.contains(':') -> InetAddress.getByName(host.trim('[', ']'))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun resolveRedirect(from: URI, location: String): String {
        if (location.isBlank()) throw SafeHttpException("Redirect missing Location")
        return try {
            from.resolve(location).toString()
        } catch (e: Exception) {
            throw SafeHttpException("Bad redirect")
        }
    }

    private suspend fun getOnce(uri: URI): FetchResult = coroutineScope {
        val url = URL(uri.toASCIIString())
        val conn = (url.openConnection() as HttpURLConnection)
        val deadline = launch {
            delay(DEADLINE_MS)
            runCatching { conn.disconnect() }
        }
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            // Identify the app; nothing about the user or API keys.
            conn.setRequestProperty("User-Agent", "Diurna/1.0 (personal Android app; recipe-import)")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/ld+json;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language", "en")
            // Explicitly do not send cookies or credentials.
            conn.setRequestProperty("Cookie", "")
            conn.setRequestProperty("Cookie2", "")

            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val stream = conn.inputStream ?: throw SafeHttpException("Empty response")
                    FetchOk(stream.use { readCapped(it) })
                }
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val loc = conn.getHeaderField("Location")
                        ?: throw SafeHttpException("Redirect missing Location")
                    FetchRedirect(loc)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> throw SafeHttpException("Page not found")
                else -> throw SafeHttpException("HTTP $code")
            }
        } catch (e: SafeHttpException) {
            throw e
        } catch (e: IOException) {
            throw SafeHttpException(e.message?.take(80) ?: "Couldn't fetch that page")
        } finally {
            deadline.cancel()
            runCatching { conn.disconnect() }
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw SafeHttpException("Page is too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private sealed interface FetchResult
    private data class FetchOk(val body: String) : FetchResult
    private data class FetchRedirect(val location: String) : FetchResult
}

/** User-visible fetch/policy failure; message has no secrets. */
class SafeHttpException(message: String) : IOException(message)
