package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.diurna.domain.AlarmDismiss
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Porch-code dismissal path: alarms stay disarmed until the printed secret is confirmed,
 * and replacing the code un-confirms so the old printout cannot stop a new ring.
 */
@RunWith(AndroidJUnit4::class)
class PorchSecretTest {
    private val settings = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private var savedSecret: String? = null
    private var savedConfirmed: Boolean = false

    @Before fun remember() = runTest {
        savedSecret = settings.porchSecret.first()
        savedConfirmed = settings.isConfirmed()
    }

    @After fun restore() = runTest {
        settings.restorePorchForTest(savedSecret, savedConfirmed)
    }

    @Test fun newSecretIsUnconfirmedPrefixedAndOnlyExactMatchDismisses() = runTest {
        val first = settings.newSecret()
        assertTrue(first.startsWith("daybook:porch:"))
        assertFalse(settings.isConfirmed())
        assertEquals(first, settings.secret())
        assertTrue(AlarmDismiss.matchesPorch(first, settings.secret()))
        assertFalse(AlarmDismiss.matchesPorch(first + "x", settings.secret()))

        settings.setConfirmed()
        assertTrue(settings.isConfirmed())

        val second = settings.newSecret()
        assertNotEquals(first, second)
        assertFalse("replacing the code must pause alarms until rescanned", settings.isConfirmed())
        assertFalse(AlarmDismiss.matchesPorch(first, settings.secret()))
        assertTrue(AlarmDismiss.matchesPorch(second, settings.secret()))
    }

    @Test fun ensureSecretIsIdempotentUntilReplaced() = runTest {
        settings.newSecret()
        val a = settings.secret()
        settings.ensureSecret()
        settings.ensureSecret()
        assertEquals(a, settings.porchSecret.first())
    }
}
