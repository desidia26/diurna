package app.diurna.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the Anthropic API key, encrypted with an AES key that lives in the Android Keystore.
 * The Keystore key can't be exported, so copying the app's files off the phone (e.g. scripts/
 * backup-db) yields only ciphertext. The prefs file is also excluded from cloud backup
 * (res/xml/backup_rules.xml). If the Keystore key is ever lost, the stored key reads as absent
 * and just needs re-entering.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _lastFour = MutableStateFlow(get()?.takeLast(4))

    /** Last four characters of the stored key, for display; null when none is set. */
    val lastFour: StateFlow<String?> = _lastFour

    fun get(): String? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val blob = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        _lastFour.value = apiKey.trim().takeLast(4)
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        _lastFour.value = null
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val PREFS = "secure"
        const val KEY_BLOB = "anthropic_api_key"
        const val ALIAS = "daybook_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
