package app.diurna.ai

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale

/**
 * Normalizes and validates user-entered AI gateway URLs. Base URLs and model names aren't secrets,
 * but a URL must never carry userinfo (credentials in the authority), and cleartext is only for
 * loopback / private LAN / .local — never for a public host.
 *
 * Android's network security config can't express IP ranges, so cleartext to LAN IPs is gated here
 * in-app. Platform cleartext stays off globally except for named localhost aliases; see
 * res/xml/network_security_config.xml and the PR security notes.
 */
object GatewayUrl {
    const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

    /** Default Anthropic Messages API origin (SDK appends /v1/messages). */
    const val ANTHROPIC_DEFAULT_BASE = "https://api.anthropic.com"

    /**
     * Turns a base (`https://host/v1`) or a full chat-completions URL into a single POST target.
     * Rejects userinfo, empty hosts, and non-http(s) schemes.
     */
    fun normalizeChatCompletions(raw: String): String {
        val uri = parseHttpUri(raw.trim())
        rejectUserinfo(uri)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val normalizedPath = when {
            path.endsWith(CHAT_COMPLETIONS_SUFFIX) -> path
            path.endsWith("/v1") || path.endsWith("/api/v1") || path.isEmpty() ->
                path + CHAT_COMPLETIONS_SUFFIX
            else -> path + CHAT_COMPLETIONS_SUFFIX
        }
        return rebuild(uri, normalizedPath)
    }

    /**
     * Anthropic SDK [com.anthropic.client.okhttp.AnthropicOkHttpClient.Builder.baseUrl]: origin only.
     * Strips a trailing `/v1` or `/v1/messages` if the person pasted a full path.
     */
    fun normalizeAnthropicBase(raw: String): String {
        val uri = parseHttpUri(raw.trim())
        rejectUserinfo(uri)
        var path = uri.rawPath.orEmpty().trimEnd('/')
        when {
            path.endsWith("/v1/messages") -> path = path.removeSuffix("/v1/messages")
            path.endsWith("/v1") -> path = path.removeSuffix("/v1")
        }
        return rebuild(uri, path.ifEmpty { "" }).trimEnd('/')
    }

    /**
     * Validates a URL that will be contacted: scheme/host rules, no userinfo, and for http the host
     * must resolve to a private or loopback address (or be a known local name before resolve).
     * [resolve] is injectable so unit tests don't touch DNS.
     */
    fun assertAllowed(
        url: String,
        resolve: (String) -> List<InetAddress> = ::resolveHost,
    ): ValidatedUrl {
        val uri = parseHttpUri(url.trim())
        rejectUserinfo(uri)
        val host = uri.host ?: throw GatewayUrlException("URL needs a host")
        val scheme = uri.scheme!!.lowercase(Locale.US)
        val cleartext = scheme == "http"
        if (cleartext && !allowsCleartextHost(host, resolve)) {
            throw GatewayUrlException(
                "HTTP (no TLS) is only allowed for localhost, .local, and private LAN addresses. Use HTTPS for anything else."
            )
        }
        return ValidatedUrl(url = rebuild(uri, uri.rawPath.orEmpty()), host = host, cleartext = cleartext)
    }

    /** True when http to [host] is permitted (name check, then resolved addresses must all be private/loopback). */
    fun allowsCleartextHost(host: String, resolve: (String) -> List<InetAddress> = ::resolveHost): Boolean {
        val h = host.trim().lowercase(Locale.US).trimEnd('.')
        if (h.isEmpty()) return false
        if (isLocalName(h)) return true
        if (isLiteralPrivateOrLoopback(h)) return true
        val addrs = try {
            resolve(h)
        } catch (_: UnknownHostException) {
            return false
        }
        if (addrs.isEmpty()) return false
        return addrs.all { isPrivateOrLoopback(it) }
    }

    fun isPrivateOrLoopback(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            return when {
                a == 10 -> true // 10/8
                a == 172 && b in 16..31 -> true // 172.16/12
                a == 192 && b == 168 -> true // 192.168/16
                a == 169 && b == 254 -> true // link-local
                else -> false
            }
        }
        // IPv6: unique-local fc00::/7, link-local fe80::/10 already covered by isLinkLocalAddress
        if (bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xff
            if (b0 and 0xfe == 0xfc) return true // fc00::/7
        }
        return false
    }

    fun isCleartextUrl(url: String): Boolean =
        runCatching { parseHttpUri(url.trim()).scheme.equals("http", ignoreCase = true) }.getOrDefault(false)

    /** Host-only check without DNS: localhost aliases, .local, or a private/loopback literal. */
    fun looksLocalOrPrivate(host: String): Boolean {
        val h = host.trim().lowercase(Locale.US).trimEnd('.')
        return isLocalName(h) || isLiteralPrivateOrLoopback(h)
    }

    private fun isLocalName(host: String): Boolean =
        host == "localhost" || host == "localhost." || host.endsWith(".local") ||
            host == "10.0.2.2" // Android emulator's host loopback alias

    private fun isLiteralPrivateOrLoopback(host: String): Boolean {
        if (host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
        val bare = host.removePrefix("[").removeSuffix("]")
        return try {
            isPrivateOrLoopback(InetAddress.getByName(bare))
        } catch (_: Exception) {
            false
        }
    }

    private fun parseHttpUri(raw: String): URI {
        if (raw.isBlank()) throw GatewayUrlException("Enter a URL")
        val withScheme = when {
            raw.contains("://") -> raw
            else -> "https://$raw"
        }
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw GatewayUrlException("That doesn't look like a URL")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "https" && scheme != "http") {
            throw GatewayUrlException("URL must start with https:// or http://")
        }
        if (uri.host.isNullOrBlank()) throw GatewayUrlException("URL needs a host")
        return uri
    }

    private fun rejectUserinfo(uri: URI) {
        if (!uri.userInfo.isNullOrEmpty()) {
            throw GatewayUrlException("Don't put a username or password in the URL. Use the API key field.")
        }
        // URI.userInfo can be null while the raw authority still has user@ — check the raw string.
        val authority = uri.rawAuthority ?: return
        if ('@' in authority) {
            throw GatewayUrlException("Don't put a username or password in the URL. Use the API key field.")
        }
    }

    private fun rebuild(uri: URI, path: String): String {
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val q = uri.rawQuery?.let { "?$it" }.orEmpty()
        // Drop fragments; they never belong on an API endpoint.
        return "${uri.scheme}://${uri.host}$port$path$q"
    }

    private fun resolveHost(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()
}

data class ValidatedUrl(val url: String, val host: String, val cleartext: Boolean)

class GatewayUrlException(message: String) : Exception(message)
