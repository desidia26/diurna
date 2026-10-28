package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class GatewayUrlTest {
    @Test fun normalizesBaseAndFullChatCompletions() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            GatewayUrl.normalizeChatCompletions("https://openrouter.ai/api/v1"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            GatewayUrl.normalizeChatCompletions("https://openrouter.ai/api/v1/chat/completions"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            GatewayUrl.normalizeChatCompletions("http://localhost:11434/v1"),
        )
        assertEquals(
            "https://host/chat/completions",
            GatewayUrl.normalizeChatCompletions("https://host"),
        )
        assertEquals(
            "https://host:8443/v1/chat/completions",
            GatewayUrl.normalizeChatCompletions("https://host:8443/v1/"),
        )
    }

    @Test fun addsHttpsWhenSchemeOmitted() {
        assertEquals(
            "https://gateway.example/v1/chat/completions",
            GatewayUrl.normalizeChatCompletions("gateway.example/v1"),
        )
    }

    @Test fun rejectsUserinfoInUrls() {
        assertThrows(GatewayUrlException::class.java) {
            GatewayUrl.normalizeChatCompletions("https://user:pass@host/v1")
        }
        assertThrows(GatewayUrlException::class.java) {
            GatewayUrl.normalizeAnthropicBase("https://user@api.example")
        }
        assertThrows(GatewayUrlException::class.java) {
            GatewayUrl.assertAllowed("https://token@evil.example/v1/chat/completions")
        }
    }

    @Test fun normalizesAnthropicBase() {
        assertEquals("https://proxy.example", GatewayUrl.normalizeAnthropicBase("https://proxy.example"))
        assertEquals("https://proxy.example", GatewayUrl.normalizeAnthropicBase("https://proxy.example/v1"))
        assertEquals("https://proxy.example", GatewayUrl.normalizeAnthropicBase("https://proxy.example/v1/messages"))
        assertEquals("http://localhost:8080", GatewayUrl.normalizeAnthropicBase("http://localhost:8080/v1/"))
    }

    @Test fun defaultXaiEndpointUnchanged() {
        assertEquals(
            XaiChat.ENDPOINT,
            GatewayUrl.normalizeChatCompletions("https://api.x.ai/v1"),
        )
        assertEquals(XaiChat.ENDPOINT, GatewayUrl.normalizeChatCompletions(XaiChat.ENDPOINT))
    }

    @Test fun cleartextAllowsLoopbackAndPrivateLiterals() {
        assertTrue(GatewayUrl.allowsCleartextHost("localhost") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("127.0.0.1") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("10.0.2.2") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("10.0.0.5") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("192.168.1.10") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("172.16.0.1") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("172.31.255.1") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("nas.local") { error("no dns") })
        assertTrue(GatewayUrl.allowsCleartextHost("169.254.1.1") { error("no dns") })
    }

    @Test fun cleartextBlocksPublicHostsEvenIfNameLooksFine() {
        val public = listOf(InetAddress.getByName("8.8.8.8"))
        assertFalse(GatewayUrl.allowsCleartextHost("example.com") { public })
        assertThrows(GatewayUrlException::class.java) {
            GatewayUrl.assertAllowed("http://example.com/v1/chat/completions") { public }
        }
    }

    @Test fun cleartextAllowsHostnameThatResolvesOnlyToPrivate() {
        val lan = listOf(InetAddress.getByName("192.168.0.50"))
        assertTrue(GatewayUrl.allowsCleartextHost("gateway.home") { lan })
        val v = GatewayUrl.assertAllowed("http://gateway.home/v1/chat/completions") { lan }
        assertTrue(v.cleartext)
    }

    @Test fun httpsIsAlwaysAllowedBySchemeCheck() {
        val public = listOf(InetAddress.getByName("1.1.1.1"))
        val v = GatewayUrl.assertAllowed("https://api.x.ai/v1/chat/completions") { public }
        assertFalse(v.cleartext)
        assertEquals("api.x.ai", v.host)
    }

    @Test fun rejectsNonHttpSchemesAndBlank() {
        assertThrows(GatewayUrlException::class.java) { GatewayUrl.normalizeChatCompletions("ftp://host/v1") }
        assertThrows(GatewayUrlException::class.java) { GatewayUrl.normalizeChatCompletions("   ") }
    }

    @Test fun privateAddressHelpers() {
        assertTrue(GatewayUrl.isPrivateOrLoopback(InetAddress.getByName("127.0.0.1")))
        assertTrue(GatewayUrl.isPrivateOrLoopback(InetAddress.getByName("10.1.2.3")))
        assertTrue(GatewayUrl.isPrivateOrLoopback(InetAddress.getByName("172.20.0.1")))
        assertFalse(GatewayUrl.isPrivateOrLoopback(InetAddress.getByName("172.15.0.1")))
        assertFalse(GatewayUrl.isPrivateOrLoopback(InetAddress.getByName("8.8.8.8")))
        assertTrue(GatewayUrl.looksLocalOrPrivate("localhost"))
        assertTrue(GatewayUrl.looksLocalOrPrivate("192.168.0.1"))
        assertFalse(GatewayUrl.looksLocalOrPrivate("example.com"))
    }
}
