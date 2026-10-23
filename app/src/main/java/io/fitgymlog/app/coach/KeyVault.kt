package io.fitgymlog.app.coach

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The key is kept with the address it was checked against and is only handed out for that address. */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("coach_key", Context.MODE_PRIVATE)
    private val _savedFor = MutableStateFlow(prefs.getString(BASE_URL, null)?.takeIf { prefs.contains(STORED) })
    val savedFor: StateFlow<String?> = _savedFor.asStateFlow()

    /** A key that can no longer be decrypted, such as after the Keystore was reset, is dropped so the user is asked again. */
    suspend fun read(baseUrl: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(STORED, null)?.takeIf { baseUrl == _savedFor.value } ?: return@withContext null
        try {
            val (iv, sealed) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv)) }
            cipher.doFinal(sealed).decodeToString()
        } catch (error: Exception) {
            if (!error.meansKeyIsGone()) throw error
            clear()
            null
        }
    }

    suspend fun write(apiKey: String, baseUrl: String) = withContext(Dispatchers.IO + NonCancellable) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(apiKey.trim().toByteArray())
        prefs.edit(commit = true) {
            putString(STORED, "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(sealed, Base64.NO_WRAP)}")
            putString(BASE_URL, baseUrl)
        }
        _savedFor.value = baseUrl
    }

    fun clear() {
        prefs.edit { remove(STORED); remove(BASE_URL) }
        _savedFor.value = null
    }

    private fun Exception.meansKeyIsGone() = this is AEADBadTagException || this is KeyPermanentlyInvalidatedException ||
        this is UnrecoverableKeyException || this is IllegalArgumentException || this is IndexOutOfBoundsException

    @Synchronized
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
        const val BASE_URL = "base_url"
    }
}
