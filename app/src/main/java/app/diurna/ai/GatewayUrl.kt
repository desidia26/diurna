package app.diurna.ai

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Normalizes and validates user-entered AI gateway URLs. Base URLs and model names aren't secrets,
 * but a URL must never carry userinfo (credentials in the authority), and cleartext (http) is
 * only for localhost and the Android emulator host alias — never for a LAN or public host.
 * LAN servers need HTTPS (e.g. Tailscale `tailscale serve`, or a reverse proxy such as Caddy).
 * Matches res/xml/network_security_config.xml: platform cleartext stays off globally except those
 * same named aliases. Larry's decision on PR #21: do not widen cleartext for RFC1918.
 */
object GatewayUrl {
    const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

    /** Shown in Setup and thrown from [assertAllowed] when http targets a non-local host. */
    const val CLEARTEXT_BLOCKED =
        "HTTP is only allowed for localhost and the emulator. Use HTTPS for a LAN server."

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
     * Validates a URL that will be contacted: scheme/host rules, no userinfo, and for http the
     * host must be localhost or the emulator alias (same allowlist as network_security_config).
     */
    fun assertAllowed(url: String): ValidatedUrl {
        val uri = parseHttpUri(url.trim())
        rejectUserinfo(uri)
        val host = uri.host ?: throw GatewayUrlException("URL needs a host")
        val scheme = uri.scheme!!.lowercase(Locale.US)
        val cleartext = scheme == "http"
        if (cleartext && !allowsCleartextHost(host)) {
            throw GatewayUrlException(CLEARTEXT_BLOCKED)
        }
        return ValidatedUrl(url = rebuild(uri, uri.rawPath.orEmpty()), host = host, cleartext = cleartext)
    }

    /**
     * True when http to [host] is permitted: localhost, loopback literals, or the emulator's
     * `10.0.2.2` host alias — the same names allowlisted in network_security_config.xml.
     * Private LAN addresses and `.local` names are not cleartext-allowed; use HTTPS.
     */
    fun allowsCleartextHost(host: String): Boolean {
        val h = host.trim().lowercase(Locale.US).trimEnd('.')
        if (h.isEmpty()) return false
        return isCleartextAllowedName(h)
    }

    /**
     * Supporting/error copy for Setup when the typed URL is http to something other than
     * localhost/emulator. Null when the URL is blank, not http, or cleartext-allowed.
     */
    fun cleartextBlockedHint(raw: String): String? {
        if (raw.isBlank()) return null
        val uri = try {
            parseHttpUri(raw.trim())
        } catch (_: GatewayUrlException) {
            return null
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (allowsCleartextHost(host)) return null
        return CLEARTEXT_BLOCKED
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
        return isCleartextAllowedName(h) || h.endsWith(".local") || isLiteralPrivateOrLoopback(h)
    }

    private fun isCleartextAllowedName(host: String): Boolean =
        host == "localhost" || host == "localhost." ||
            host == "127.0.0.1" || host == "::1" || host == "[::1]" ||
            host == "10.0.2.2" // Android emulator → host loopback

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

}

data class ValidatedUrl(val url: String, val host: String, val cleartext: Boolean)

class GatewayUrlException(message: String) : Exception(message)
