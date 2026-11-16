package com.example.reader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dictation: the tablet's microphone records what is said, and Whisper (on the tablet, offline)
 * turns it into text when the recording is stopped — or, when the tablet has internet and the
 * settings allow it, an online service does (much faster). Used by the note dialogs on the tablet
 * and, through the web server, by the mic buttons in the browser.
 */
object Dictation {
    enum class Phase { IDLE, RECORDING, TRANSCRIBING, DONE, ERROR, DOWNLOADING }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** How loud the last tenth of a second was, 0–1 (for the animation). */
        val level: Float = 0f,
        val startedAt: Long = 0L,
        val text: String = "",
        val error: String = "",
        /** Download of the speech model, 0–100. */
        val percent: Int = 0,
        val language: String = "no",
        /** The text is being written by an online service. */
        val online: Boolean = false
    )

    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val RATE = 16000
    private const val MAX_SECONDS = 300

    @Volatile private var stopFlag = false
    @Volatile private var cancelFlag = false

    // The loaded model is kept for a few minutes, so a second dictation starts at once.
    private val whisperLock = Any()
    private var ctx = 0L
    private var ctxModel = ""
    private var freeJob: Job? = null

    fun language(l: String): String = if (l == "en") "en" else "no"

    // ---- settings (Settings → Dictation, on the tablet and in the browser)

    private fun prefs(context: Context) = context.getSharedPreferences("dictation", Context.MODE_PRIVATE)

    /** "auto": online when the tablet has internet, otherwise offline; "online"; "offline". */
    fun mode(context: Context): String = prefs(context).getString("mode", "auto").let { if (it == "online" || it == "offline") it else "auto" }
    /** "ai": the provider chosen under Settings → AI; or GEMINI, OPENAI, CUSTOM. */
    fun service(context: Context): String = prefs(context).getString("service", "ai").let { if (it == "GEMINI" || it == "OPENAI" || it == "CUSTOM") it else "ai" }
    /** Offline: the small, quick models instead of the accurate ones. */
    fun fast(context: Context): Boolean = prefs(context).getBoolean("fast", false)
    /** The speech model asked of an OpenAI-compatible service; blank: the usual one. */
    fun speechModel(context: Context): String = prefs(context).getString("speechModel", "").orEmpty().trim()

    fun saveSettings(context: Context, mode: String?, service: String?, fast: Boolean?, speechModel: String?) {
        val e = prefs(context).edit()
        if (mode != null) e.putString("mode", mode)
        if (service != null) e.putString("service", service)
        if (fast != null) e.putBoolean("fast", fast)
        if (speechModel != null) e.putString("speechModel", speechModel.trim())
        e.apply()
    }

    /** The online service a dictation would go to, or null when none has an API key. */
    fun provider(context: Context): AiProvider? {
        val able = runCatching { Transcripts.onlineProviders(context) }.getOrDefault(emptyList())
        return when (val want = service(context)) {
            "ai" -> runCatching { AiPreferences(context).load().provider }.getOrNull()?.takeIf { it in able } ?: able.firstOrNull()
            else -> runCatching { AiProvider.valueOf(want) }.getOrNull()?.takeIf { it in able }
        }
    }

    /** Would a dictation started now be written online? */
    fun goesOnline(context: Context): Boolean =
        mode(context) != "offline" && provider(context) != null && NoteAiAssistant.isInternetAvailable(context)

    private val FAST_NORWEGIAN = LocalWhisper.Model("nb-whisper-base-q5_0.bin", "https://huggingface.co/NbAiLab/nb-whisper-base/resolve/main/ggml-model-q5_0.bin", 55_300_000)
    private val FAST_MULTILINGUAL = LocalWhisper.Model("whisper-base-q5_1.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", 59_700_000)

    private fun modelFile(context: Context, language: String): Pair<File, LocalWhisper.Model> {
        val l = language(language)
        val m = if (fast(context)) (if (l == "no") FAST_NORWEGIAN else FAST_MULTILINGUAL) else LocalWhisper.modelFor(l)
        return File(LocalWhisper.modelDir(context), m.file) to m
    }

    fun hasModel(context: Context, language: String): Boolean {
        val (f, m) = modelFile(context, language)
        return f.isFile && f.length() > m.bytes * 9 / 10
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun busy(): Boolean = state.value.phase.let { it == Phase.RECORDING || it == Phase.TRANSCRIBING || it == Phase.DOWNLOADING }

    fun json(context: Context): JSONObject {
        val s = state.value
        return JSONObject()
            .put("phase", s.phase.name.lowercase())
            .put("level", s.level.toDouble())
            .put("seconds", if (s.phase == Phase.RECORDING) ((System.currentTimeMillis() - s.startedAt) / 1000).toInt() else 0)
            .put("text", s.text)
            .put("error", s.error)
            .put("percent", s.percent)
            .put("language", s.language)
            .put("writingOnline", s.online)
            .put("mode", mode(context))
            .put("service", service(context))
            .put("fast", fast(context))
            .put("speechModel", speechModel(context))
            .put("online", goesOnline(context))
            .put("internet", NoteAiAssistant.isInternetAvailable(context))
            .put("provider", provider(context)?.label ?: "")
            .put("aiProvider", runCatching { AiPreferences(context).load().provider.label }.getOrDefault(""))
            .put("providers", JSONArray().apply { runCatching { Transcripts.onlineProviders(context) }.getOrDefault(emptyList()).forEach { put(JSONObject().put("id", it.name).put("label", it.label)) } })
            .put("available", WhisperNative.available)
            .put("permission", hasPermission(context))
            .put("models", JSONObject().put("no", hasModel(context, "no")).put("en", hasModel(context, "en")))
            .put("modelMb", JSONObject().put("no", modelMegabytes(context, "no")).put("en", modelMegabytes(context, "en")))
    }

    /** Starts listening. Throws IllegalStateException with a message for the user when it cannot. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, lang: String) {
        val l = language(lang)
        check(!busy()) { "The tablet is already busy with a dictation." }
        check(Recordings.active.value == null) { "A recording is running on the tablet. Stop it first." }
        check(hasPermission(context)) { "The app may not use the tablet's microphone yet. On the tablet, tap a microphone button in a note and allow it." }
        val online = goesOnline(context)
        if (!online) {
            if (mode(context) == "online") {
                check(provider(context) != null) { "Dictation is set to always go online, but no service with an API key is set up for it. See Settings → AI and Settings → Dictation." }
                throw IllegalStateException("Dictation is set to always go online, but the tablet has no internet right now.")
            }
            check(WhisperNative.available) { "Speech recognition is not part of this build of the app." }
            check(!LocalWhisper.state.value.busy) { "The tablet is transcribing a recording. Wait until it has finished." }
            check(hasModel(context, l)) { "The speech model is not on the tablet yet." }
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("The tablet's microphone could not be opened.") }
        try { rec.startRecording() } catch (e: Throwable) { rec.release(); throw IllegalStateException("The tablet's microphone could not be started.") }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); throw IllegalStateException("The tablet's microphone is in use by something else.") }
        stopFlag = false
        cancelFlag = false
        state.value = State(Phase.RECORDING, startedAt = System.currentTimeMillis(), language = l)
        val app = context.applicationContext
        scope.launch { capture(app, rec, l) }
    }

    /** Stops listening; the text follows (phase DONE). */
    fun stop() { if (state.value.phase == Phase.RECORDING) stopFlag = true }

    /** Drops what is being recorded or transcribed, and clears a finished text or an error. */
    fun cancel() {
        when (state.value.phase) {
            Phase.RECORDING -> cancelFlag = true
            Phase.TRANSCRIBING -> { cancelFlag = true; runCatching { WhisperNative.cancel() } }
            Phase.DOWNLOADING -> cancelFlag = true
            else -> state.value = State(language = state.value.language)
        }
    }

    private fun capture(context: Context, rec: AudioRecord, language: String) {
        val chunks = ArrayList<ShortArray>()
        var total = 0
        var peak = 0
        val buf = ShortArray(RATE / 10)
        try {
            while (!stopFlag && !cancelFlag && total < MAX_SECONDS * RATE) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                chunks += buf.copyOf(n)
                total += n
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toInt(); sum += v.toDouble() * v; val a = if (v < 0) -v else v; if (a > peak) peak = a }
                val rms = Math.sqrt(sum / n) / 32768.0
                state.value = state.value.copy(level = (rms * 8.0).coerceIn(0.0, 1.0).toFloat())
            }
        } catch (e: Throwable) {
            Log.w("Dictation", "recording failed", e)
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (cancelFlag) { state.value = State(language = language); return }
        if (total < RATE / 2) { state.value = State(Phase.ERROR, error = "Nothing was recorded.", language = language); return }
        if (peak == 0) {
            state.value = State(Phase.ERROR, language = language,
                error = "The tablet heard only silence. Android lets the app use the microphone only while Marginalia is open on the tablet's screen.")
            return
        }
        val pcm = ShortArray(total)
        var at = 0
        for (c in chunks) { System.arraycopy(c, 0, pcm, at, c.size); at += c.size }
        val via = if (goesOnline(context)) provider(context) else null
        state.value = State(Phase.TRANSCRIBING, language = language, online = via != null)
        try {
            fun offline(): String {
                check(WhisperNative.available) { "Speech recognition is not part of this build of the app." }
                check(hasModel(context, language)) { "The speech model is not on the tablet yet." }
                // Whisper wants at least a second of sound; short dictations are padded with silence.
                val samples = FloatArray(maxOf(total, RATE + RATE / 5))
                for (i in 0 until total) samples[i] = pcm[i] / 32768f
                return transcribe(context, samples, language)
            }
            val text = if (via == null) offline() else try {
                transcribeOnline(context, pcm, language, via)
            } catch (e: Throwable) {
                // "Online when there is internet": a service that fails falls back to the tablet's own model, when it has one.
                if (cancelFlag || mode(context) != "auto" || !WhisperNative.available || !hasModel(context, language) || LocalWhisper.state.value.busy) throw e
                Log.w("Dictation", "online failed, writing offline", e)
                state.value = State(Phase.TRANSCRIBING, language = language, online = false)
                offline()
            }
            state.value = if (cancelFlag) State(language = language) else State(Phase.DONE, text = text, language = language)
        } catch (e: Throwable) {
            Log.w("Dictation", "transcription failed", e)
            state.value = if (cancelFlag) State(language = language)
            else State(Phase.ERROR, error = e.message ?: "The speech could not be turned into text.", language = language)
        }
    }

    private fun transcribe(context: Context, samples: FloatArray, language: String): String = synchronized(whisperLock) {
        freeJob?.cancel()
        val (model, _) = modelFile(context, language)
        if (ctx == 0L || ctxModel != model.absolutePath) {
            if (ctx != 0L) runCatching { WhisperNative.freeContext(ctx) }
            ctx = WhisperNative.initContext(model.absolutePath)
            ctxModel = model.absolutePath
            check(ctx != 0L) { "The speech model could not be loaded." }
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val bytes = WhisperNative.transcribe(ctx, samples, language, threads, null, null)
            ?: throw IllegalStateException("Whisper could not transcribe what was said.")
        val segs = Transcripts.parseSegments(JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("segments") ?: JSONArray())
        freeJob = scope.launch {
            delay(3 * 60 * 1000L)
            synchronized(whisperLock) { if (ctx != 0L) { runCatching { WhisperNative.freeContext(ctx) }; ctx = 0L; ctxModel = "" } }
        }
        segs.map { it.text.trim() }.filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("(") }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build() }

    /** 16 kHz mono samples as a WAV file in memory. */
    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(RATE).putInt(RATE * 2).putShort(2.toShort()).putShort(16.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        for (v in pcm) b.putShort(v)
        return b.array()
    }

    private fun httpFault(provider: AiProvider, code: Int, body: String): Nothing = throw IllegalStateException(when (code) {
        401, 403 -> "${provider.label} refused the API key. Check it in Settings → AI."
        404 -> "${provider.label} does not know the speech model or the address. Check Settings → Dictation."
        429 -> "${provider.label}: rate or quota limit reached. Try again later."
        else -> "${provider.label} could not write the dictation (HTTP $code). ${body.take(160)}"
    })

    /** Sends what was said to the online service and returns the text. */
    private fun transcribeOnline(context: Context, pcm: ShortArray, language: String, provider: AiProvider): String {
        val ai = AiPreferences(context)
        val key = ai.readKey(provider)
        check(key.isNotBlank()) { "Add your ${provider.label} API key in Settings → AI." }
        val sound = wav(pcm)
        if (provider == AiProvider.GEMINI) {
            val model = ai.model(AiProvider.GEMINI).ifBlank { AiProvider.GEMINI.defaultModel }
            val prompt = "Transcribe this dictation. The speech is in ${if (language == "en") "English" else "Norwegian (write it as spoken, Bokmål or Nynorsk)"}. " +
                "Write exactly what is said, in that language, with normal punctuation and capital letters, without translating, summarising, correcting or adding anything. " +
                "Leave out filler sounds. Answer only with the transcribed text. If nothing is said, answer with an empty text."
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", android.util.Base64.encodeToString(sound, android.util.Base64.NO_WRAP))))
                    .put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0))
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) httpFault(provider, r.code, t)
                val parts = JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeUnless { p -> p.optBoolean("thought") }?.optString("text") }
                    .joinToString("").replace(Regex("\\s+"), " ").trim()
            }
        }
        val url = if (provider == AiProvider.OPENAI) "https://api.openai.com/v1/audio/transcriptions" else {
            val u = ai.load().endpoint.replace(Regex("/chat/completions/?$"), "/audio/transcriptions").toHttpUrlOrNull()
            check(u != null && u.isHttps) { "Set an HTTPS address for the Other provider in Settings → AI." }
            u.toString()
        }
        val model = speechModel(context).ifBlank { if (provider == AiProvider.CUSTOM && url.contains("groq", ignoreCase = true)) "whisper-large-v3-turbo" else "whisper-1" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "dictation.wav", sound.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("language", language)
            .build()
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) httpFault(provider, r.code, t)
            return JSONObject(t).optString("text").replace(Regex("\\s+"), " ").trim()
        }
    }

    /** Megabytes of the speech model a language needs with the present settings (for the question before the download). */
    fun modelMegabytes(context: Context, lang: String): Int = (modelFile(context, lang).second.bytes / 1_000_000).toInt()

    /** Fetches the speech model for [lang] (once). Only ever started by a button. */
    @Synchronized
    fun downloadModel(context: Context, lang: String) {
        val l = language(lang)
        if (hasModel(context, l)) return
        check(!busy()) { "The tablet is busy with a dictation." }
        check(!LocalWhisper.state.value.busy) { "The tablet is transcribing a recording. Wait until it has finished." }
        cancelFlag = false
        state.value = State(Phase.DOWNLOADING, language = l)
        val app = context.applicationContext
        scope.launch {
            val (f, m) = modelFile(app, l)
            val part = File(f.parentFile, f.name + ".part")
            try {
                val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
                client.newCall(Request.Builder().url(m.url).build()).execute().use { r ->
                    check(r.isSuccessful) { "Could not download the speech model (HTTP ${r.code})." }
                    val body = r.body ?: error("Empty download.")
                    val len = body.contentLength().takeIf { it > 0 } ?: m.bytes
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            var got = 0L
                            var last = -1
                            while (true) {
                                check(!cancelFlag) { "Cancelled." }
                                val k = input.read(buf)
                                if (k < 0) break
                                out.write(buf, 0, k)
                                got += k
                                val pc = (got * 100 / len).toInt()
                                if (pc != last) { last = pc; state.value = state.value.copy(percent = pc) }
                            }
                        }
                    }
                }
                check(part.renameTo(f)) { "Could not save the speech model." }
                state.value = State(language = l)
            } catch (e: Throwable) {
                part.delete()
                state.value = if (cancelFlag) State(language = l) else State(Phase.ERROR, error = e.message ?: "The download failed.", language = l)
            }
        }
    }
}
