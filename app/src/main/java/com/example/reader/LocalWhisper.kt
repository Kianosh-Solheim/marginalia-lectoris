package com.example.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** whisper.cpp, built into the app (libmarginalia_whisper.so). */
object WhisperNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("marginalia_whisper"); true }.getOrElse { Log.w("Whisper", "not built in", it); false } }

    interface ProgressListener { fun onProgress(percent: Int) }

    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(ctx: Long)
    @JvmStatic external fun cancel()
    @JvmStatic external fun transcribe(ctx: Long, samples: FloatArray, language: String, threads: Int, prompt: String?, callback: ProgressListener?): ByteArray?
}

/**
 * Offline transcription on the tablet: downloads a Whisper model once (only when asked),
 * decodes the recording to 16 kHz mono and runs whisper.cpp over it in five-minute pieces,
 * in a foreground service so it can run with the screen off.
 */
object LocalWhisper {
    data class State(val id: String = "", val busy: Boolean = false, val status: String = "")

    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Models: Norwegian → NB-Whisper (National Library of Norway); English or unknown → OpenAI's multilingual Whisper. */
    private data class Model(val file: String, val url: String, val bytes: Long)
    private val NORWEGIAN = Model("nb-whisper-small-q5_0.bin", "https://huggingface.co/NbAiLab/nb-whisper-small/resolve/main/ggml-model-q5_0.bin", 175_209_680)
    private val MULTILINGUAL = Model("whisper-small-q5_1.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190_085_487)

    private fun modelFor(language: String) = if (language == "no") NORWEGIAN else MULTILINGUAL
    private fun modelDir(context: Context) = File(context.filesDir, "whisper").apply { mkdirs() }

    fun modelsOnDevice(context: Context): List<String> = modelDir(context).listFiles()?.filter { it.extension == "bin" }?.map { it.name }.orEmpty()

    private var pending: Triple<DocumentEntity, Recordings.Info, String>? = null

    fun start(context: Context, doc: DocumentEntity, info: Recordings.Info, language: String) {
        if (state.value.busy) { Transcripts.lastError.value = Transcripts.lastError.value + (info.id to "Another recording is being transcribed on the tablet. Wait until it finishes."); return }
        if (!WhisperNative.available) { Transcripts.lastError.value = Transcripts.lastError.value + (info.id to "Offline transcription is not part of this build. Use the PC or an online service."); return }
        pending = Triple(doc, info, language)
        state.value = State(info.id, true, "Starting…")
        val app = context.applicationContext
        val i = Intent(app, TranscribeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
    }

    fun cancel() { if (state.value.busy) runCatching { WhisperNative.cancel() }; cancelled = true }
    @Volatile private var cancelled = false

    internal fun run(service: TranscribeService) {
        val job = pending ?: run { service.done(); return }
        pending = null
        cancelled = false
        val (doc, info, language) = job
        scope.launch {
            val wake = (service.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Marginalia:transcribe")
            wake.acquire(6 * 60 * 60 * 1000L)
            var ctx = 0L
            val pcm = File(service.cacheDir, "transcribe.pcm")
            try {
                val model = ensureModel(service, info.id, modelFor(language))
                status(service, info.id, "Reading the recording…")
                val samples = decodeTo16k(info.audio, pcm)
                check(samples > 0) { "The recording has no sound." }
                status(service, info.id, "Loading the model…")
                ctx = WhisperNative.initContext(model.absolutePath)
                check(ctx != 0L) { "The model could not be loaded. It is downloaded again next time." .also { model.delete() } }
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val chunk = 16000 * 300 // five minutes
                val total = samples
                val segs = ArrayList<Transcripts.Segment>()
                var lang = if (language == "auto") "" else language
                DataInputStream(BufferedInputStream(pcm.inputStream(), 1 shl 16)).use { input ->
                    var offset = 0L
                    while (offset < total) {
                        check(!cancelled) { "Cancelled." }
                        val n = minOf(chunk.toLong(), total - offset).toInt()
                        val buf = FloatArray(n)
                        for (k in 0 until n) buf[k] = java.lang.Short.reverseBytes(input.readShort()) / 32768f
                        val startMs = offset * 1000 / 16000
                        val prompt = segs.takeLast(2).joinToString(" ") { it.text }.takeLast(200).ifBlank { null }
                        val done = offset
                        val bytes = WhisperNative.transcribe(ctx, buf, lang.ifBlank { "auto" }, threads, prompt, object : WhisperNative.ProgressListener {
                            override fun onProgress(percent: Int) {
                                val all = ((done + n.toLong() * percent / 100) * 100 / total).toInt()
                                status(service, info.id, "Transcribing on the tablet: $all%")
                            }
                        }) ?: throw IllegalStateException(if (cancelled) "Cancelled." else "Whisper could not transcribe this part.")
                        val j = JSONObject(String(bytes, Charsets.UTF_8))
                        if (lang.isBlank()) lang = j.optString("language").let { if (it == "nn" || it == "nb") "no" else it }
                        segs += Transcripts.parseSegments(j.optJSONArray("segments") ?: org.json.JSONArray())
                            .map { it.copy(start = it.start + startMs, end = it.end + startMs) }
                        offset += n
                    }
                }
                status(service, info.id, "Linking to your highlights…")
                Transcripts.save(service, AppDatabase.getInstance(service).documentDao().getDocumentById(doc.id) ?: doc, info,
                    segs.filter { it.text.isNotBlank() && !it.text.startsWith("[") }, lang.ifBlank { "auto" }, "Whisper on the tablet")
            } catch (e: Throwable) {
                Log.w("Whisper", "failed", e)
                Transcripts.lastError.value = Transcripts.lastError.value + (info.id to (e.message ?: e.javaClass.simpleName))
            } finally {
                if (ctx != 0L) runCatching { WhisperNative.freeContext(ctx) }
                pcm.delete()
                runCatching { wake.release() }
                state.value = State()
                service.done()
            }
        }
    }

    private fun status(service: TranscribeService, id: String, text: String) {
        state.value = State(id, true, text)
        service.update(text)
    }

    private fun ensureModel(service: TranscribeService, id: String, m: Model): File {
        val f = File(modelDir(service), m.file)
        if (f.isFile && f.length() > m.bytes * 9 / 10) return f
        val part = File(f.parentFile, f.name + ".part")
        status(service, id, "Downloading the speech model (once)…")
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
        client.newCall(Request.Builder().url(m.url).build()).execute().use { r ->
            check(r.isSuccessful) { "Could not download the speech model (HTTP ${r.code})." }
            val body = r.body ?: error("Empty download.")
            val len = body.contentLength().takeIf { it > 0 } ?: m.bytes
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var got = 0L; var last = -1
                    while (true) {
                        check(!cancelled) { "Cancelled." }
                        val k = input.read(buf); if (k < 0) break
                        out.write(buf, 0, k); got += k
                        val pc = (got * 100 / len).toInt()
                        if (pc != last) { last = pc; status(service, id, "Downloading the speech model (once): $pc%") }
                    }
                }
            }
        }
        check(part.renameTo(f)) { "Could not save the speech model." }
        return f
    }

    /** Decodes the audio to 16 kHz mono 16-bit samples in [out]; returns the number of samples. */
    private fun decodeTo16k(audio: File, out: File): Long {
        val ex = MediaExtractor()
        ex.setDataSource(audio.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("No audio in the recording.")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var written = 0L
        var pos = 0.0 // resampling position in input samples
        val step = { rate / 16000.0 }
        DataOutputStream(BufferedOutputStream(out.outputStream(), 1 shl 16)).use { dos ->
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var carry = 0.0f; var haveCarry = false; var inIndex = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (ob >= 0) {
                    val buf = codec.getOutputBuffer(ob)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.asShortBuffer()
                    val frames = sb.remaining() / channels
                    val s = step()
                    for (fIdx in 0 until frames) {
                        var acc = 0f
                        for (c in 0 until channels) acc += sb.get(fIdx * channels + c)
                        val v = acc / channels
                        // Linear resampling to 16 kHz across buffer boundaries.
                        val prev = if (haveCarry) carry else v
                        while (pos <= inIndex) {
                            val frac = (pos - (inIndex - 1)).toFloat().coerceIn(0f, 1f)
                            val sample = prev + (v - prev) * frac
                            dos.writeShort(java.lang.Short.reverseBytes(sample.toInt().coerceIn(-32768, 32767).toShort()).toInt())
                            written++
                            pos += s
                        }
                        carry = v; haveCarry = true; inIndex++
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        runCatching { codec.stop() }; codec.release(); ex.release()
        return written
    }
}

/** Keeps offline transcription running with the screen off. */
class TranscribeService : Service() {
    private val channel = "transcribe"
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update("Starting…")
        LocalWhisper.run(this)
        return START_NOT_STICKY
    }

    fun update(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(NotificationChannel(channel, "Transcription", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val n: Notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Transcribing a recording")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(4712, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(4712, n)
    }

    fun done() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }
}
