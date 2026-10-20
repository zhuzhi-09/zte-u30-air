package com.zhuzhi.ufi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal AES/GCM store backed by the Android Keystore. Used for the WebUI password so it
 * never sits in plain SharedPreferences.
 */
object SecureStore {

    private const val PREF = "ufi_prefs"
    private const val KEY_ALIAS = "ufi_webui_key"
    private const val SUFFIX_IV = ".iv"
    private const val SUFFIX_DATA = ".data"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun putSecret(ctx: Context, key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit()
            .putString(key + SUFFIX_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(key + SUFFIX_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun getSecret(ctx: Context, key: String): String? {
        val iv = prefs(ctx).getString(key + SUFFIX_IV, null) ?: return null
        val data = prefs(ctx).getString(key + SUFFIX_DATA, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun removeSecret(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key + SUFFIX_IV).remove(key + SUFFIX_DATA).apply()
    }

    fun putString(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    fun getString(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)

    fun remove(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key).apply()
    }
}
