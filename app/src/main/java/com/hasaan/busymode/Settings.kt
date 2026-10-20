package com.hasaan.busymode

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All user settings. The Groq API key is encrypted with a key kept in the Android Keystore. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var groqKey: String
        get() = SecureBox.decrypt(prefs.getString("groq_key_enc", null)) ?: ""
        set(v) = prefs.edit().putString("groq_key_enc", if (v.isBlank()) null else SecureBox.encrypt(v.trim())).apply()

    var ownerName: String
        get() = prefs.getString("owner_name", "Hasaan")!!
        set(v) = prefs.edit().putString("owner_name", v.trim()).apply()

    var replyText: String
        get() = prefs.getString("reply_text", DEFAULT_REPLY)!!
        set(v) = prefs.edit().putString("reply_text", v.trim()).apply()

    /** "suggest" = AI suggestions I approve, "auto" = automatic busy reply, "off" = no replies. */
    var replyMode: String
        get() = prefs.getString("reply_mode", "suggest")!!
        set(v) = prefs.edit().putString("reply_mode", v).apply()

    var voiceWake: Boolean
        get() = prefs.getBoolean("voice_wake", false)
        set(v) = prefs.edit().putBoolean("voice_wake", v).apply()

    var countryCode: String
        get() = prefs.getString("country_code", "92")!!.ifBlank { "92" }
        set(v) = prefs.edit().putString("country_code", v.filter { it.isDigit() }).apply()

    var agentModel: String
        get() = prefs.getString("agent_model", DEFAULT_AGENT_MODEL)!!.ifBlank { DEFAULT_AGENT_MODEL }
        set(v) = prefs.edit().putString("agent_model", v.trim()).apply()

    var voiceAlerts: Boolean
        get() = prefs.getBoolean("voice_alerts", true)
        set(v) = prefs.edit().putBoolean("voice_alerts", v).apply()

    var replyCooldownMin: Int
        get() = prefs.getInt("reply_cooldown", 60)
        set(v) = prefs.edit().putInt("reply_cooldown", v.coerceIn(0, 1440)).apply()

    var ignoreList: List<String>
        get() = lines(prefs.getString("ignore_list", ""))
        set(v) = prefs.edit().putString("ignore_list", v.joinToString("\n")).apply()

    var importantList: List<String>
        get() = lines(prefs.getString("important_list", ""))
        set(v) = prefs.edit().putString("important_list", v.joinToString("\n")).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var lastPhone: String
        get() = prefs.getString("last_phone", "")!!
        set(v) = prefs.edit().putString("last_phone", v).apply()

    var batteryAsked: Boolean
        get() = prefs.getBoolean("battery_asked", false)
        set(v) = prefs.edit().putBoolean("battery_asked", v).apply()

    fun toEngineConfig(): JSONObject = JSONObject().apply {
        put("groqKey", groqKey)
        put("model", model)
        put("ownerName", ownerName)
        put("replyText", replyText.ifBlank { DEFAULT_REPLY })
        put("replyMode", replyMode)
        put("autoReply", replyMode == "auto")
        put("agentModel", agentModel)
        put("countryCode", countryCode)
        put("tzOffsetMin", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
        put("voiceAlerts", voiceAlerts)
        put("replyCooldownMin", replyCooldownMin)
        put("ignoreList", JSONArray(ignoreList))
        put("importantList", JSONArray(importantList))
    }

    fun addToList(important: Boolean, name: String) {
        val list = (if (important) importantList else ignoreList).toMutableList()
        if (list.none { it.equals(name, true) }) list.add(name)
        if (important) {
            importantList = list
            ignoreList = ignoreList.filterNot { it.equals(name, true) }
        } else {
            ignoreList = list
            importantList = importantList.filterNot { it.equals(name, true) }
        }
    }

    companion object {
        const val DEFAULT_REPLY = "Hasaan is busy right now, he will get back to you soon."
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        const val DEFAULT_AGENT_MODEL = "openai/gpt-oss-120b"
        fun lines(s: String?): List<String> =
            (s ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** AES-GCM encryption with a non-exportable key in the Android Keystore. */
object SecureBox {
    private const val ALIAS = "busymode_secret_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
