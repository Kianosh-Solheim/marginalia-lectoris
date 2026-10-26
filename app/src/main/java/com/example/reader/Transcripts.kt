package com.example.reader

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.SettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Turns a recording into a transcript tied to the reading: each stretch of speech is placed
 * under the page or highlight you were on when you said it, and what you said right after a
 * highlight becomes that highlight's "spoken comment".
 *
 * Transcripts come from three places: the tablet (Whisper, offline), the browser on a PC
 * (Whisper in the web view), or an online service with the API key saved in Settings → AI.
 * They all end in [save] with segments of {start, end (ms), text}.
 */
object Transcripts {
    data class Segment(val start: Long, val end: Long, val text: String)

    /** Work in progress, by recording id: a short status line ("Uploading…", "Transcribing 40%"). */
    val progress = MutableStateFlow<Map<String, String>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES).writeTimeout(15, TimeUnit.MINUTES).callTimeout(30, TimeUnit.MINUTES).build()

    fun setProgress(id: String, text: String?) {
        progress.value = progress.value.toMutableMap().apply { if (text == null) remove(id) else put(id, text) }
    }

    // ------------------------------------------------------------------------ saving

    fun parseSegments(a: JSONArray): List<Segment> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val text = o.optString("text").trim()
        if (text.isBlank()) null else Segment(o.optLong("start"), o.optLong("end", o.optLong("start")), text)
    }.sortedBy { it.start }

    /** Stores the transcript, writes the Markdown file and the highlights' spoken comments. */
    suspend fun save(context: Context, doc: DocumentEntity, info: Recordings.Info, segments: List<Segment>, language: String, engine: String) {
        val o = JSONObject().put("language", language).put("engine", engine).put("created", System.currentTimeMillis())
            .put("segments", JSONArray().apply { segments.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("text", it.text)) } })
        File(info.audio.parentFile, info.id + ".transcript.json").writeText(o.toString(1))
        val tl = Recordings.timeline(info)
        tl.put("language", language)
        runCatching { info.timeline.writeText(tl.toString(1)) }
        val settings = AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot() ?: SettingsEntity()
        info.transcript.writeText(markdown(doc, info, tl, segments, language, engine, settings))
        SpokenComments.init(context)
        SpokenComments.setForRecording(info.id, spokenComments(info, tl, segments))
        Recordings.changes.value++
    }

    private data class Ev(val t: Long, val type: String, val o: JSONObject)

    private fun events(tl: JSONObject): List<Ev> {
        val a = tl.optJSONArray("events") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Ev(it.optLong("t"), it.optString("type"), it) }.sortedBy { it.t }
    }

    /** Speech that starts from shortly before a highlight until the next highlight (at most 3 minutes). */
    private fun spokenComments(info: Recordings.Info, tl: JSONObject, segs: List<Segment>): Map<Long, List<SpokenComments.Entry>> {
        val hls = events(tl).filter { it.type == "highlight" }
        val out = HashMap<Long, List<SpokenComments.Entry>>()
        hls.forEachIndexed { i, e ->
            val from = (e.t - 3000).coerceAtLeast(0)
            val until = minOf(hls.getOrNull(i + 1)?.t ?: Long.MAX_VALUE, e.t + 180_000)
            val mine = segs.filter { it.start in from until until }
            if (mine.isNotEmpty()) out[e.o.optLong("id")] = listOf(SpokenComments.Entry(info.id, info.audio.name, mine.first().start, mine.joinToString(" ") { it.text }))
        }
        return out
    }

    private fun markdown(doc: DocumentEntity, info: Recordings.Info, tl: JSONObject, segs: List<Segment>, language: String, engine: String, settings: SettingsEntity): String {
        val clock = SpokenComments::clock
        val evs = events(tl).filter { it.type == "start" || it.type == "page" || it.type == "highlight" }
        val started = tl.optLong("started", info.startedAt)
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("title: \"Recording – ${doc.title.replace("\"", "'")} – ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(started))}\"")
        sb.appendLine("marginalia-recording: ${info.id}")
        sb.appendLine("From Book: \"[[${MarkdownExporter.getMocTitle(doc.title, settings)}]]\"")
        sb.appendLine("date: ${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(started))}")
        sb.appendLine("duration: \"${clock(tl.optLong("durationMs", info.durationMs))}\"")
        sb.appendLine("language: $language")
        sb.appendLine("transcribed-with: $engine")
        sb.appendLine("tags:")
        sb.appendLine("  - Recording")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("![[${info.audio.name}]]")
        // Sections start at each page turn or highlight; speech goes under the last one before it.
        var segIndex = 0
        val bounds = evs.ifEmpty { listOf(Ev(0, "start", JSONObject())) }
        bounds.forEachIndexed { i, e ->
            val next = bounds.getOrNull(i + 1)?.t ?: Long.MAX_VALUE
            val mine = ArrayList<Segment>()
            while (segIndex < segs.size && (segs[segIndex].start < next || i == bounds.lastIndex)) { mine += segs[segIndex]; segIndex++ }
            if (mine.isEmpty() && e.type == "page") return@forEachIndexed
            val label = e.o.optString("label").trim()
            val page = e.o.optInt("page", 0)
            val where = when {
                label.isNotBlank() && label != page.toString() -> "p. $label"
                page > 0 -> if (doc.fileType.equals("PDF", true)) "PDF p. $page" else "loc. $page"
                else -> ""
            }
            val chapter = e.o.optString("chapter").trim()
            val link = "[[${info.audio.name}#t=${e.t / 1000}|${clock(e.t)}]]"
            sb.appendLine()
            if (e.type == "highlight") {
                val title = e.o.optString("title").trim()
                sb.appendLine("### $link Highlight${if (where.isNotBlank()) " · $where" else ""}${if (title.isNotBlank()) " · [[$title]]" else ""}")
                val q = e.o.optString("text").trim()
                if (q.isNotBlank()) sb.appendLine("> ${q.replace("\n", " ").take(400)}").appendLine()
            } else {
                sb.appendLine("### $link ${listOf(where, chapter).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Start" }}")
            }
            mine.forEach { s -> sb.appendLine(s.text) }
        }
        if (segs.isEmpty()) sb.appendLine().appendLine("*No speech was found in this recording.*")
        return sb.toString()
    }

    // ------------------------------------------------------------------------ online

    /** Providers whose saved key can transcribe audio. */
    fun onlineProviders(context: Context): List<AiProvider> {
        val p = AiPreferences(context)
        return listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.CUSTOM).filter { p.hasKey(it) }
    }

    fun startOnline(context: Context, doc: DocumentEntity, info: Recordings.Info, provider: AiProvider, language: String) {
        val app = context.applicationContext
        if (progress.value.containsKey(info.id)) return
        setProgress(info.id, "Starting…")
        scope.launch {
            runCatching {
                val segs = when (provider) {
                    AiProvider.GEMINI -> gemini(app, info, language)
                    AiProvider.OPENAI, AiProvider.CUSTOM -> openAi(app, info, language, provider)
                    AiProvider.CLAUDE -> throw IllegalStateException("Claude cannot transcribe audio. Add a Gemini or OpenAI key in Settings → AI.")
                }
                save(app, doc, info, segs.second, segs.first, provider.label)
            }.onFailure { e -> lastError.value = lastError.value + (info.id to (e.message ?: e.javaClass.simpleName)) }
            setProgress(info.id, null)
        }
    }

    /** The last failure per recording, shown until the next attempt. */
    val lastError = MutableStateFlow<Map<String, String>>(emptyMap())
    fun clearError(id: String) { lastError.value = lastError.value - id }

    private fun httpError(code: Int, body: String): Nothing = throw IllegalStateException(when (code) {
        401, 403 -> "API access denied. Check the API key in Settings → AI."
        413 -> "The recording is too large for this service. Try Gemini, or transcribe on the PC."
        429 -> "Rate or quota limit reached. Try again later."
        else -> "Transcription failed (HTTP $code). ${body.take(200)}"
    })

    private fun openAi(context: Context, info: Recordings.Info, language: String, provider: AiProvider): Pair<String, List<Segment>> {
        val prefs = AiPreferences(context)
        val key = prefs.readKey(provider)
        check(key.isNotBlank()) { "Add your ${provider.label} API key in Settings → AI." }
        require(info.audio.length() <= 25L * 1024 * 1024) { "OpenAI accepts recordings up to 25 MB (about 80 minutes). Use Gemini or transcribe on the PC." }
        val url = if (provider == AiProvider.OPENAI) "https://api.openai.com/v1/audio/transcriptions" else {
            val ep = prefs.load().endpoint
            val u = ep.replace(Regex("/chat/completions/?$"), "/audio/transcriptions").toHttpUrlOrNull()
            require(u != null && u.isHttps) { "Set an HTTPS endpoint for the Other provider in Settings → AI." }
            u.toString()
        }
        setProgress(info.id, "Uploading to ${provider.label}…")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.m4a", info.audio.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { if (language == "no" || language == "en") addFormDataPart("language", language) }
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) httpError(r.code, text)
            setProgress(info.id, "Linking to your highlights…")
            val j = JSONObject(text)
            val a = j.optJSONArray("segments") ?: JSONArray()
            val segs = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Segment((it.optDouble("start") * 1000).toLong(), (it.optDouble("end") * 1000).toLong(), it.optString("text").trim())
            }.filter { it.text.isNotBlank() }
            val lang = when (j.optString("language").lowercase(Locale.ROOT)) { "norwegian", "no", "nb", "nn" -> "no"; "english", "en" -> "en"; else -> language }
            return lang to segs.ifEmpty { listOf(Segment(0, 0, j.optString("text").trim())).filter { it.text.isNotBlank() } }
        }
    }

    private fun gemini(context: Context, info: Recordings.Info, language: String): Pair<String, List<Segment>> {
        val prefs = AiPreferences(context)
        val key = prefs.readKey(AiProvider.GEMINI)
        check(key.isNotBlank()) { "Add your Gemini API key in Settings → AI." }
        val model = prefs.model(AiProvider.GEMINI).ifBlank { AiProvider.GEMINI.defaultModel }
        setProgress(info.id, "Uploading to Gemini…")
        val len = info.audio.length()
        val start = Request.Builder().url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", len.toString()).header("X-Goog-Upload-Header-Content-Type", "audio/mp4")
            .post(JSONObject().put("file", JSONObject().put("display_name", "recording")).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val uploadUrl = client.newCall(start).execute().use { r ->
            if (!r.isSuccessful) httpError(r.code, r.body?.string().orEmpty())
            r.header("x-goog-upload-url") ?: throw IllegalStateException("Gemini did not accept the upload.")
        }
        val fileObj = client.newCall(Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
            .post(info.audio.asRequestBody("audio/mp4".toMediaType())).build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) httpError(r.code, t)
            JSONObject(t).getJSONObject("file")
        }
        val name = fileObj.optString("name")
        val uri = fileObj.optString("uri")
        try {
            setProgress(info.id, "Gemini is preparing the audio…")
            var state = fileObj.optString("state")
            var tries = 0
            while (state == "PROCESSING" && tries++ < 120) {
                Thread.sleep(3000)
                state = client.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/$name").header("x-goog-api-key", key).get().build())
                    .execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString("state") }
            }
            check(state == "ACTIVE" || state.isBlank()) { "Gemini could not read the audio ($state)." }
            setProgress(info.id, "Transcribing with Gemini…")
            val langLine = when (language) { "no" -> "The speech is in Norwegian (Bokmål or Nynorsk as spoken)."; "en" -> "The speech is in English."; else -> "The speech is in Norwegian or English, possibly both." }
            val prompt = "Transcribe this recording of a person thinking aloud while reading. $langLine " +
                "Write down exactly what is said, in the language it is said, without translating, summarising or correcting. " +
                "Leave out filler sounds and long silences. Split it into segments of one to three sentences. " +
                "Answer only with JSON: {\"language\":\"no\" or \"en\",\"segments\":[{\"start\":\"MM:SS\",\"end\":\"MM:SS\",\"text\":\"…\"}]}. " +
                "Use H:MM:SS for times past one hour."
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("file_data", JSONObject().put("mime_type", "audio/mp4").put("file_uri", uri)))
                    .put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 65536).put("temperature", 0))
            val out = client.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) httpError(r.code, t)
                JSONObject(t).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                    (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeUnless { p -> p.optBoolean("thought") }?.optString("text") }.joinToString("")
                }.orEmpty()
            }
            setProgress(info.id, "Linking to your highlights…")
            val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0), (out.lastIndexOf('}') + 1).coerceAtLeast(1)))
            val a = json.optJSONArray("segments") ?: JSONArray()
            val segs = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Segment(clockMs(it.optString("start")), clockMs(it.optString("end")), it.optString("text").trim())
            }.filter { it.text.isNotBlank() }.sortedBy { it.start }
            return json.optString("language").ifBlank { language } to segs
        } finally {
            runCatching { client.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/$name").header("x-goog-api-key", key).delete().build()).execute().close() }
        }
    }

    /** "12:34", "1:02:03" or "75.5" → ms. */
    fun clockMs(s: String): Long {
        val t = s.trim()
        if (t.isBlank()) return 0
        if (!t.contains(':')) return ((t.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val p = t.split(':').map { it.toDoubleOrNull() ?: 0.0 }
        val sec = when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> p[0] }
        return (sec * 1000).toLong()
    }
}
