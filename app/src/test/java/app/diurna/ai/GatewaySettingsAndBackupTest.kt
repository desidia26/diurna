package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings defaults and backup exclusion for the "secure" prefs that hold keys and gateway
 * config. Android Keystore encryption is not exercised on the JVM; we assert exclusion rules
 * and that empty overrides keep shipped Claude/Grok endpoints.
 */
class GatewaySettingsAndBackupTest {
    @Test fun gatewayDefaultsLeaveClaudeAndGrokEndpointsUnchanged() {
        val g = GatewaySettings()
        assertEquals("", g.openAiBaseUrl)
        assertEquals("", g.openAiModel)
        assertTrue(g.openAiSupportsImages)
        assertEquals("", g.anthropicBaseUrl)
        assertTrue(g.anthropicSupportsImages)
        assertEquals("", g.xaiEndpoint)
        assertTrue(g.xaiSupportsImages)
        assertEquals(XaiChat.ENDPOINT, GatewayUrl.normalizeChatCompletions("https://api.x.ai/v1"))
        assertEquals(GatewayUrl.ANTHROPIC_DEFAULT_BASE, "https://api.anthropic.com")
    }

    @Test fun backupRulesExcludeSecurePrefs() {
        assertTrue(excludesSecure(readResXml("backup_rules.xml")))
        assertTrue(excludesSecure(readResXml("data_extraction_rules.xml")))
    }

    @Test fun networkSecurityKeepsCleartextOffGlobally() {
        val xml = readResXml("network_security_config.xml")
        assertTrue("base-config must deny cleartext", xml.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(xml.contains("localhost"))
        assertTrue(xml.contains("127.0.0.1"))
        assertTrue(xml.contains("10.0.2.2"))
    }

    private fun excludesSecure(xml: String): Boolean =
        xml.contains("path=\"secure.xml\"") && xml.contains("sharedpref")

    private fun readResXml(name: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        val f = candidates.firstOrNull { it.exists() } ?: error("missing $name under ${File(".").absolutePath}")
        return f.readText()
    }
}
