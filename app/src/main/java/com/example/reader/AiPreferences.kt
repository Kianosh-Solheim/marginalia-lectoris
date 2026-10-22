package com.example.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider(val label: String, val defaultModel: String) {
    GEMINI("Gemini", "gemini-3.8-flash"),
    CLAUDE("Claude", "claude-haiku-5-5"),
    OPENAI("ChatGPT / OpenAI", "gpt-6-luna"),
    CUSTOM("Other (OpenAI compatible)", "")
}

data class AiSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val model: String = AiProvider.GEMINI.defaultModel,
    val endpoint: String = "",
    val titleLanguage: String = "BOOK",
    val titlePrompt: String = "Write an explanatory TL;DR title that captures the main claim or insight. Make it as short as possible, but use more words when needed to explain the content. Do not merely name the topic.",
    val summaryPrompt: String = "Summarise the highlight faithfully and concisely. Preserve important qualifications. Do not invent details.",
    val tagsPrompt: String = "Suggest a few useful tags. Prefer relevant tags from my existing vocabulary, preserving their spelling. Allow new free-form tags when the existing ones do not fit.",
    val dictionaryPrompt: String = "Explain the selected word in the supplied context. Give its meaning here and a brief example. If context is insufficient, say so.",
    val dictionaryAi: Boolean = false
)

/** Credentials live only in noBackupFilesDir, encrypted with a device-bound Keystore key. */
class AiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    private val keyFile = File(context.noBackupFilesDir, "ai_credentials.json")
    fun load(): AiSettings {
        val p = runCatching { AiProvider.valueOf(prefs.getString("provider", "GEMINI")!!) }.getOrDefault(AiProvider.GEMINI)
        val defaults = AiSettings(provider = p, model = p.defaultModel)
        return defaults.copy(
            model = model(p),
            endpoint = prefs.getString("endpoint", "")!!,
            titleLanguage = prefs.getString("titleLanguage", "BOOK")!!,
            titlePrompt = prefs.getString("titlePrompt", defaults.titlePrompt)!!,
            summaryPrompt = prefs.getString("summaryPrompt", defaults.summaryPrompt)!!,
            tagsPrompt = prefs.getString("tagsPrompt", defaults.tagsPrompt)!!,
            dictionaryPrompt = prefs.getString("dictionaryPrompt", defaults.dictionaryPrompt)!!,
            dictionaryAi = prefs.getBoolean("dictionaryAi", false)
        )
    }
    fun model(provider: AiProvider): String {
        val saved = prefs.getString("model_${provider.name}", provider.defaultModel)!!
        // Earlier builds shipped outdated defaults; move untouched old defaults to the current ones.
        return if (saved in OUTDATED_DEFAULTS) provider.defaultModel else saved
    }
    private companion object {
        val OUTDATED_DEFAULTS = setOf("claude-sonnet-4-6", "gpt-5.4-mini")
    }
    fun save(s: AiSettings) {
        check(prefs.edit().putString("provider", s.provider.name).putString("model_${s.provider.name}", s.model.trim())
            .putString("endpoint", s.endpoint.trim()).putString("titleLanguage", s.titleLanguage)
            .putString("titlePrompt", s.titlePrompt).putString("summaryPrompt", s.summaryPrompt)
            .putString("tagsPrompt", s.tagsPrompt).putString("dictionaryPrompt", s.dictionaryPrompt)
            .putBoolean("dictionaryAi", s.dictionaryAi).commit()) { "Could not save AI settings" }
    }
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("marginalia_ai", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("marginalia_ai", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun keys() = if (keyFile.exists()) JSONObject(keyFile.readText()) else JSONObject()
    fun hasKey(provider: AiProvider) = runCatching { keys().has(provider.name) }.getOrDefault(false)
    fun readKey(provider: AiProvider): String {
        val encoded = keys().optString(provider.name)
        if (encoded.isBlank()) return ""
        return try {
            val parts = encoded.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("Saved key is unavailable. Enter it again in Settings → AI.") }
    }
    fun saveKey(provider: AiProvider, value: String) {
        val data = keys()
        if (value.isBlank()) data.remove(provider.name) else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            data.put(provider.name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        val atomic = android.util.AtomicFile(keyFile)
        val stream = atomic.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}
