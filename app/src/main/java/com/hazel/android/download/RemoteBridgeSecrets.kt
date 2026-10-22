package com.hazel.android.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted-at-rest bridge configuration. The encryption key never leaves Android Keystore. */
object RemoteBridgeSecrets {
    private const val PREFS = "remote_bridge_secure"
    private const val KEY_ALIAS = "hazel_remote_bridge_v1"
    private const val ENTRY = "config"

    data class Config(val endpoint: String, val token: String)

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun load(context: Context): Config? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null)
            ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        Config(json.getString("endpoint"), json.getString("token"))
    }.getOrNull()

    fun save(context: Context, endpoint: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(JSONObject().put("endpoint", endpoint.trim().trimEnd('/'))
            .put("token", token).toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not save encrypted bridge settings"
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ENTRY).commit()
    }
}
