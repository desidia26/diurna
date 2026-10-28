package app.diurna.data.recipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SafeHttpTest {
    @Test fun httpsOkWithoutResolveForPublicHostShape() {
        // resolve=false: shape/scheme only (no DNS flakiness in unit tests)
        SafeHttp.validatePublicHttpsUri("https://example.com/recipe/123", resolve = false)
        SafeHttp.validatePublicHttpsUri("https://www.example.co.uk/a?b=1#x", resolve = false)
    }

    @Test fun rejectsNonHttps() {
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("http://example.com/r", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("file:///etc/passwd", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("javascript:alert(1)", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("data:text/html,hi", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("ftp://example.com/r", resolve = false)
        }
    }

    @Test fun rejectsEmbeddedCredentials() {
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("https://user:pass@example.com/r", resolve = false)
        }
    }

    @Test fun rejectsLocalhostByName() {
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("https://localhost/r", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("https://foo.localhost/r", resolve = false)
        }
    }

    @Test fun rejectsLiteralPrivateAndLoopbackIps() {
        fun bad(url: String) = assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri(url, resolve = true)
        }
        bad("https://127.0.0.1/r")
        bad("https://10.0.0.5/r")
        bad("https://192.168.1.1/r")
        bad("https://172.16.5.5/r")
        bad("https://169.254.169.254/latest/meta-data/")
        bad("https://0.0.0.0/r")
        bad("https://100.64.1.2/r") // CGNAT
        bad("https://[::1]/r")
        bad("https://[fc00::1]/r") // ULA
        bad("https://[fe80::1]/r") // link-local
    }

    @Test fun blockedAddressClassifier() {
        fun addr(s: String) = InetAddress.getByName(s)
        assertTrue(SafeHttp.isBlockedAddress(addr("127.0.0.1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("10.1.2.3")))
        assertTrue(SafeHttp.isBlockedAddress(addr("192.168.0.1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("172.31.255.1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("169.254.1.1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("100.64.0.1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("0.0.0.0")))
        assertTrue(SafeHttp.isBlockedAddress(addr("::1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("fc00::1")))
        assertTrue(SafeHttp.isBlockedAddress(addr("fe80::1")))
        // Public-ish documentation still blocked by classifier extras; 8.8.8.8 is public
        assertFalse(SafeHttp.isBlockedAddress(addr("8.8.8.8")))
        assertFalse(SafeHttp.isBlockedAddress(addr("1.1.1.1")))
    }

    @Test fun emptyAndRelativeRejected() {
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("/relative", resolve = false)
        }
        assertThrows(SafeHttpException::class.java) {
            SafeHttp.validatePublicHttpsUri("example.com/r", resolve = false)
        }
    }
}
