package com.iraitec

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small, encrypted, per-account recent transcript. AES-256-GCM key lives in
 * Android Keystore and is not exported. No plaintext conversations in prefs.
 * Stores at most 10 messages, each capped to 3500 chars.
 */
class IraiRecentMemory(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "irai_chat").apply { mkdirs() }
    private val alias = "irai-recent-chat-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val current = store.getKey(alias, null) as? SecretKey
        if (current != null) return current
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun file(account: String): AtomicFile {
        require(account.isNotBlank())
        val id = MessageDigest.getInstance("SHA-256")
            .digest(account.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(dir, "$id.bin"))
    }

    @Synchronized
    fun load(account: String): List<JSONObject> {
        if (account.isBlank()) return emptyList()
        return try {
            val blob = file(account).readFully()
            if (blob.size < 29 || blob.size > 200_000) return emptyList()
            val iv = blob.copyOfRange(0, 12)
            val ciphertext = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val array = JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val role = obj.optString("role")
                val content = obj.optString("content")
                if (role !in setOf("user", "assistant") || content.isBlank()) null
                else JSONObject().put("role", role).put("content", content.take(3500))
            }.takeLast(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(account: String, messages: List<JSONObject>) {
        if (account.isBlank()) return
        val array = JSONArray()
        messages.takeLast(10).forEach { item ->
            val role = item.optString("role")
            val content = item.optString("content").take(3500)
            if (role == "user" || role == "assistant") {
                if (content.isNotBlank()) array.put(
                    JSONObject().put("role", role).put("content", content)
                )
            }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val target = file(account)
        val output = target.startWrite()
        try {
            output.write(data)
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear(account: String) {
        if (account.isNotBlank()) file(account).delete()
    }
}
