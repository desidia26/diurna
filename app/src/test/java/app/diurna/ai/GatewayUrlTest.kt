package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun cleartextAllowsLocalhostAndEmulatorOnly() {
        assertTrue(GatewayUrl.allowsCleartextHost("localhost"))
        assertTrue(GatewayUrl.allowsCleartextHost("127.0.0.1"))
        assertTrue(GatewayUrl.allowsCleartextHost("10.0.2.2"))
        assertTrue(GatewayUrl.allowsCleartextHost("::1"))
        assertTrue(GatewayUrl.allowsCleartextHost("[::1]"))
        val local = GatewayUrl.assertAllowed("http://localhost:11434/v1/chat/completions")
        assertTrue(local.cleartext)
        assertEquals("localhost", local.host)
    }

    @Test fun cleartextBlocksLanAndPublicHttp() {
        assertFalse(GatewayUrl.allowsCleartextHost("10.0.0.5"))
        assertFalse(GatewayUrl.allowsCleartextHost("192.168.1.10"))
        assertFalse(GatewayUrl.allowsCleartextHost("172.16.0.1"))
        assertFalse(GatewayUrl.allowsCleartextHost("nas.local"))
        assertFalse(GatewayUrl.allowsCleartextHost("example.com"))
        val e = assertThrows(GatewayUrlException::class.java) {
            GatewayUrl.assertAllowed("http://192.168.1.10/v1/chat/completions")
        }
        assertEquals(GatewayUrl.CLEARTEXT_BLOCKED, e.message)
        assertThrows(GatewayUrlException::class.java) {
            GatewayUrl.assertAllowed("http://example.com/v1/chat/completions")
        }
    }

    @Test fun cleartextBlockedHintForLanHttp() {
        assertEquals(
            GatewayUrl.CLEARTEXT_BLOCKED,
            GatewayUrl.cleartextBlockedHint("http://192.168.0.5:8080/v1"),
        )
        assertEquals(
            GatewayUrl.CLEARTEXT_BLOCKED,
            GatewayUrl.cleartextBlockedHint("http://nas.local/v1"),
        )
        assertNull(GatewayUrl.cleartextBlockedHint("http://localhost:11434/v1"))
        assertNull(GatewayUrl.cleartextBlockedHint("https://192.168.0.5/v1"))
        assertNull(GatewayUrl.cleartextBlockedHint(""))
        assertNull(GatewayUrl.cleartextBlockedHint("not a url @@"))
    }

    @Test fun httpsIsAlwaysAllowedBySchemeCheck() {
        val v = GatewayUrl.assertAllowed("https://api.x.ai/v1/chat/completions")
        assertFalse(v.cleartext)
        assertEquals("api.x.ai", v.host)
        // LAN over HTTPS is fine
        val lan = GatewayUrl.assertAllowed("https://192.168.1.10/v1/chat/completions")
        assertFalse(lan.cleartext)
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
