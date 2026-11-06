package app.diurna.debug

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.diurna.ui.theme.DiurnaTheme
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** CI emulator only: never clears debug history on a real phone. */
class DebugCardTest {
    @get:Rule val compose = createComposeRule()
    private var debug: DebugLog? = null
    @Before fun setup() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
        debug = DebugLog(ApplicationProvider.getApplicationContext<Context>()).also { it.configure(false, false) }
    }
    @After fun cleanup() { debug?.configure(false, false) }
    @Test fun debugAndTextConsentAreSeparateAndOffErasesTextConsent() {
        compose.setContent { DiurnaTheme { DebugCard(debug!!) } }
        val recording = compose.onNodeWithContentDescription("Record debug actions")
        val text = compose.onNodeWithContentDescription("Include typed field text")
        recording.assertIsOff(); text.assertIsOff().assertIsNotEnabled()
        recording.performClick(); text.assertIsEnabled().assertIsOff()
        text.performClick(); text.assertIsOn()
        recording.performClick(); text.assertIsOff().assertIsNotEnabled()
        recording.performClick(); text.assertIsOff()
    }
}
