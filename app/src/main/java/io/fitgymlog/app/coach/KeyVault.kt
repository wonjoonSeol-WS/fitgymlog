package io.fitgymlog.app.coach

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.fitgymlog.app.catching
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The API key, encrypted with a key that never leaves the phone's Android Keystore. */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("coach_key", Context.MODE_PRIVATE)

    val hasKey: Boolean get() = prefs.contains(STORED)

    fun read(): String? {
        val stored = prefs.getString(STORED, null) ?: return null
        return catching {
            val (iv, sealed) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv)) }
            cipher.doFinal(sealed).decodeToString()
        }.getOrNull()
    }

    fun write(apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            prefs.edit { remove(STORED) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(apiKey.trim().toByteArray())
        prefs.edit { putString(STORED, "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(sealed, Base64.NO_WRAP)}") }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fitgymlog-coach"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val STORED = "api_key"
    }
}
