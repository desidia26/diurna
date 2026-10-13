package app.daybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.daybook.data.SettingsStore.ProfileDraft
import app.daybook.domain.Activity
import app.daybook.domain.Goal
import app.daybook.domain.Profile
import app.daybook.domain.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Runs against the app's real settings file on the emulator, so it restores what was there. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val settings = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var saved: ProfileDraft

    @Before fun remember() = runTest { saved = settings.profileDraft.first() }
    @After fun restore() = runTest { settings.saveProfile(saved) }

    @Test fun completeProfileRoundTrips() = runTest {
        val d = ProfileDraft(Sex.MALE, LocalDate.of(1996, 1, 15), 70.0, Activity.MODERATE, Goal.RECOMP, 0, 1.2, 30)
        settings.saveProfile(d)
        assertEquals(d, settings.profileDraft.first())
        assertEquals(Profile(Sex.MALE, LocalDate.of(1996, 1, 15), 70.0, Activity.MODERATE, Goal.RECOMP, 0, 1.2, 30), settings.profile.first())
    }

    @Test fun incompleteProfileGivesNoTargets() = runTest {
        settings.saveProfile(ProfileDraft(sex = Sex.FEMALE, heightIn = 65.0))
        assertNull(settings.profile.first())
        assertEquals(Sex.FEMALE, settings.profileDraft.first().sex)
    }

    @Test fun clearingAnOverrideRemovesIt() = runTest {
        settings.saveProfile(ProfileDraft(Sex.MALE, LocalDate.of(1996, 1, 15), 70.0, Activity.LIGHT, Goal.CUT, proteinPerLb = 1.3))
        settings.saveProfile(settings.profileDraft.first().copy(proteinPerLb = null))
        assertNull(settings.profileDraft.first().proteinPerLb)
    }
}
