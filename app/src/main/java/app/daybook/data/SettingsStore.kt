package app.daybook.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.daybook.domain.Activity
import app.daybook.domain.Goal
import app.daybook.domain.Profile
import app.daybook.domain.Sex
import java.time.LocalDate
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

/**
 * The porch code: a random secret printed as a QR. Real alarms are only armed once the
 * printed code has been scanned once ([porchConfirmed]), so an alarm can't ring with no
 * way to stop it.
 */
class SettingsStore(private val context: Context) {
    private val secretKey = stringPreferencesKey("porch_secret")
    private val confirmedKey = booleanPreferencesKey("porch_confirmed")

    val porchSecret: Flow<String?> = context.dataStore.data.map { it[secretKey] }
    val porchConfirmed: Flow<Boolean> = context.dataStore.data.map { it[confirmedKey] ?: false }

    private val lockWallpaperKey = booleanPreferencesKey("lock_wallpaper")
    val lockWallpaper: Flow<Boolean> = context.dataStore.data.map { it[lockWallpaperKey] ?: false }

    suspend fun setLockWallpaper(enabled: Boolean) {
        context.dataStore.edit { it[lockWallpaperKey] = enabled }
    }

    // ---- Profile, for calorie and macro targets ----
    private object P {
        val sex = stringPreferencesKey("profile_sex")
        val birthDate = stringPreferencesKey("profile_birth_date")
        val heightIn = doublePreferencesKey("profile_height_in")
        val activity = stringPreferencesKey("profile_activity")
        val goal = stringPreferencesKey("profile_goal")
        val customKcalDelta = intPreferencesKey("profile_custom_kcal_delta")
        val proteinPerLb = doublePreferencesKey("profile_protein_per_lb")
        val fatPercent = intPreferencesKey("profile_fat_percent")
    }

    /** Null until sex, birth date, height, activity and goal are all set. */
    val profile: Flow<Profile?> = context.dataStore.data.map { prefs ->
        runCatching {
            Profile(
                sex = Sex.valueOf(prefs[P.sex] ?: return@map null),
                birthDate = LocalDate.parse(prefs[P.birthDate] ?: return@map null),
                heightIn = prefs[P.heightIn] ?: return@map null,
                activity = Activity.valueOf(prefs[P.activity] ?: return@map null),
                goal = Goal.valueOf(prefs[P.goal] ?: return@map null),
                customKcalDelta = prefs[P.customKcalDelta] ?: 0,
                proteinPerLb = prefs[P.proteinPerLb],
                fatPercent = prefs[P.fatPercent] ?: 25,
            )
        }.getOrNull()
    }

    /** The profile as entered so far, complete or not, for the Setup form. */
    data class ProfileDraft(
        val sex: Sex? = null, val birthDate: LocalDate? = null, val heightIn: Double? = null,
        val activity: Activity? = null, val goal: Goal? = null, val customKcalDelta: Int = 0,
        val proteinPerLb: Double? = null, val fatPercent: Int = 25,
    )

    val profileDraft: Flow<ProfileDraft> = context.dataStore.data.map { prefs ->
        ProfileDraft(
            sex = prefs[P.sex]?.let { runCatching { Sex.valueOf(it) }.getOrNull() },
            birthDate = prefs[P.birthDate]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            heightIn = prefs[P.heightIn],
            activity = prefs[P.activity]?.let { runCatching { Activity.valueOf(it) }.getOrNull() },
            goal = prefs[P.goal]?.let { runCatching { Goal.valueOf(it) }.getOrNull() },
            customKcalDelta = prefs[P.customKcalDelta] ?: 0,
            proteinPerLb = prefs[P.proteinPerLb],
            fatPercent = prefs[P.fatPercent] ?: 25,
        )
    }

    suspend fun saveProfile(d: ProfileDraft) {
        context.dataStore.edit { prefs ->
            fun <T> put(key: Preferences.Key<T>, value: T?) { if (value == null) prefs.remove(key) else prefs[key] = value }
            put(P.sex, d.sex?.name)
            put(P.birthDate, d.birthDate?.toString())
            put(P.heightIn, d.heightIn)
            put(P.activity, d.activity?.name)
            put(P.goal, d.goal?.name)
            put(P.customKcalDelta, d.customKcalDelta)
            put(P.proteinPerLb, d.proteinPerLb)
            put(P.fatPercent, d.fatPercent)
        }
    }

    suspend fun secret(): String = porchSecret.first() ?: newSecret()
    suspend fun isConfirmed(): Boolean = porchConfirmed.first()

    suspend fun ensureSecret() {
        if (porchSecret.first() == null) newSecret()
    }

    /** Replaces the code. The old printout stops working and alarms pause until the new one is scanned. */
    suspend fun newSecret(): String {
        val secret = "daybook:porch:" + UUID.randomUUID()
        context.dataStore.edit {
            it[secretKey] = secret
            it[confirmedKey] = false
        }
        return secret
    }

    suspend fun setConfirmed() {
        context.dataStore.edit { it[confirmedKey] = true }
    }
}
