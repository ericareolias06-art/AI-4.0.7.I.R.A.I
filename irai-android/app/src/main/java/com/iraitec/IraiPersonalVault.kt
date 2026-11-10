package com.iraitec

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-only personal data store. The entire profile and credential collection
 * is encrypted with an AES-256 key held by Android Keystore. Only ciphertext
 * and the GCM nonce are persisted in SharedPreferences.
 *
 * This is local encrypted storage, not a replacement for a dedicated password
 * manager. Do not store banking, payment-card, government-ID, or recovery data.
 */
class IraiPersonalVault(context: Context, accountScope: String) {
    private val scopeHash = MessageDigest.getInstance("SHA-256")
        .digest(accountScope.trim().lowercase(Locale.US).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    private val scopedKeyAlias = "$KEY_ALIAS_PREFIX.$scopeHash"
    private val prefs = context.applicationContext.getSharedPreferences(
        "irai_personal_vault_$scopeHash",
        Context.MODE_PRIVATE
    )

    @Synchronized
    private fun readPayload(): JSONObject {
        val encoded = prefs.getString("payload", null) ?: return JSONObject()
        val ivEncoded = prefs.getString("nonce", null) ?: return JSONObject()
        return try {
            val iv = Base64.decode(ivEncoded, Base64.NO_WRAP)
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (_: Exception) {
            // Never overwrite unreadable ciphertext automatically.
            JSONObject().put("_vault_error", true)
        }
    }

    @Synchronized
    private fun writePayload(payload: JSONObject): Boolean {
        return try {
            if (payload.optBoolean("_vault_error")) return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString("nonce", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(scopedKeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                scopedKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun getProfileValue(key: String): String {
        val payload = readPayload()
        if (payload.optBoolean("_vault_error")) return ""
        return payload.optJSONObject("profile")?.optString(key, "").orEmpty()
    }

    @Synchronized
    fun saveProfile(values: Map<String, String>): Boolean {
        val payload = readPayload()
        if (payload.optBoolean("_vault_error")) return false
        val profile = payload.optJSONObject("profile") ?: JSONObject()
        values.forEach { (key, value) ->
            if (key in PROFILE_KEYS) {
                if (value.isBlank()) profile.remove(key) else profile.put(key, value.trim())
            }
        }
        payload.put("profile", profile)
        return writePayload(payload)
    }

    @Synchronized
    fun saveCredential(label: String, username: String, password: String): Boolean {
        if (label.isBlank() || password.isBlank()) return false
        val payload = readPayload()
        if (payload.optBoolean("_vault_error")) return false
        val credentials = payload.optJSONObject("credentials") ?: JSONObject()
        credentials.put(
            label.trim().lowercase(),
            JSONObject().put("label", label.trim())
                .put("username", username.trim())
                .put("password", password)
        )
        payload.put("credentials", credentials)
        return writePayload(payload)
    }

    @Synchronized
    fun getCredentialUsername(label: String): String {
        val payload = readPayload()
        if (payload.optBoolean("_vault_error")) return ""
        return payload.optJSONObject("credentials")
            ?.optJSONObject(label.trim().lowercase(Locale.US))
            ?.optString("username", "")
            .orEmpty()
    }

    @Synchronized
    fun getCredentialPassword(label: String): String {
        val payload = readPayload()
        if (payload.optBoolean("_vault_error")) return ""
        return payload.optJSONObject("credentials")
            ?.optJSONObject(label.trim().lowercase())
            ?.optString("password", "")
            .orEmpty()
    }

    @Synchronized
    fun clearAll(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(scopedKeyAlias)) keyStore.deleteEntry(scopedKeyAlias)
            prefs.edit().clear().commit()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun credentialLabels(): List<String> {
        val payload = readPayload()
        if (payload.optBoolean("_vault_error")) return emptyList()
        val credentials = payload.optJSONObject("credentials") ?: return emptyList()
        return credentials.keys().asSequence().toList()
    }

    companion object {
        private const val KEY_ALIAS_PREFIX = "com.iraitec.personal.vault.aes256"
        val PROFILE_KEYS = setOf("full_name", "first_name", "last_name", "birthday", "email", "phone", "address", "city", "state", "postal_code")
    }
}
