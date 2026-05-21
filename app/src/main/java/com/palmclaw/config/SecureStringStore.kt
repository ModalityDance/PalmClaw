package com.palmclaw.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecureStringStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun contains(key: String): Boolean = prefs.contains(key)

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return when {
            stored.startsWith(ENCRYPTED_PREFIX) -> decrypt(stored.removePrefix(ENCRYPTED_PREFIX)).getOrNull()
            stored.startsWith(LEGACY_FALLBACK_PREFIX) -> {
                val decoded = decodeLegacyFallback(stored.removePrefix(LEGACY_FALLBACK_PREFIX))
                if (decoded != null) {
                    runCatching { putString(key, decoded) }
                }
                decoded
            }
            else -> stored
        }
    }

    fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key).apply()
            return
        }
        val stored = encrypt(value)
            .map { ENCRYPTED_PREFIX + it }
            .getOrElse { throw IllegalStateException("Unable to encrypt secure preference '$key'", it) }
        editor.putString(key, stored).apply()
    }

    private fun encrypt(value: String): Result<String> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): Result<String> = runCatching {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "invalid encrypted payload" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun decodeLegacyFallback(value: String): String? {
        return runCatching {
            String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "palmclaw_secure_config"
        private const val KEY_ALIAS = "palmclaw_config_secret"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val ENCRYPTED_PREFIX = "v1:"
        private const val LEGACY_FALLBACK_PREFIX = "plain:"
    }
}
