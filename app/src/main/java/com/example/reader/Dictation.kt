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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dictation: the tablet's microphone records what is said, and Whisper (on the tablet, offline)
 * turns it into text when the recording is stopped. Used by the note dialogs on the tablet and,
 * through the web server, by the mic buttons in the browser.
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
        val language: String = "no"
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

    private fun modelFile(context: Context, language: String): Pair<File, LocalWhisper.Model> {
        val m = LocalWhisper.modelFor(language(language))
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
            .put("available", WhisperNative.available)
            .put("permission", hasPermission(context))
            .put("models", JSONObject().put("no", hasModel(context, "no")).put("en", hasModel(context, "en")))
    }

    /** Starts listening. Throws IllegalStateException with a message for the user when it cannot. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, lang: String) {
        val l = language(lang)
        check(!busy()) { "The tablet is already busy with a dictation." }
        check(WhisperNative.available) { "Speech recognition is not part of this build of the app." }
        check(!LocalWhisper.state.value.busy) { "The tablet is transcribing a recording. Wait until it has finished." }
        check(Recordings.active.value == null) { "A recording is running on the tablet. Stop it first." }
        check(hasPermission(context)) { "The app may not use the tablet's microphone yet. On the tablet, tap a microphone button in a note and allow it." }
        check(hasModel(context, l)) { "The speech model is not on the tablet yet." }
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
        state.value = State(Phase.TRANSCRIBING, language = language)
        try {
            // Whisper wants at least a second of sound; short dictations are padded with silence.
            val samples = FloatArray(maxOf(total, RATE + RATE / 5))
            var k = 0
            for (c in chunks) for (v in c) samples[k++] = v / 32768f
            val text = transcribe(context, samples, language)
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

    /** Fetches the speech model for [lang] (about 180 MB, once). Only ever started by a button. */
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
