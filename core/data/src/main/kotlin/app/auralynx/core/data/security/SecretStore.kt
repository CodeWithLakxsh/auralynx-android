package app.auralynx.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores small secrets (accessory proximity keys, an optional user-supplied translation API key)
 * encrypted with AES-256-GCM under a non-exportable Android Keystore key.
 *
 * Ciphertext lives in a private SharedPreferences file that is excluded from cloud backup and
 * device transfer (see the app's data-extraction rules). Nothing here is ever logged.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value)
        val blob = cipher.iv + encrypted
        prefs.edit { putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    fun get(name: String): ByteArray? {
        val blob = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }.getOrNull()
    }

    fun putString(name: String, value: String) = put(name, value.encodeToByteArray())
    fun getString(name: String): String? = get(name)?.decodeToString()

    fun remove(name: String) = prefs.edit { remove(name) }

    fun clear() {
        prefs.edit { clear() }
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE = "auralynx_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "auralynx_secret_store_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        fun irkName(address: String) = "irk:" + address.uppercase()
        fun encKeyName(address: String) = "enc:" + address.uppercase()
        const val TRANSLATION_API_KEY = "translation_api_key"
        const val TRANSLATION_CONTACT_EMAIL = "translation_contact_email"
    }
}
