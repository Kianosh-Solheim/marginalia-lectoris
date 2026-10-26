package com.example.reader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording mode: while reading, the tablet records your voice. Every page turn, highlight and
 * note is written to a timeline with its time in the recording, so what you said can later be
 * tied to the passage you were on. Files live in the book's folder under "Recordings":
 *   "<Title> – 2026-10-26 22.40.m4a"            the audio (AAC, mono, 16 kHz — what Whisper wants)
 *   "<Title> – 2026-10-26 22.40.timeline.json"  the events
 *   "<Title> – 2026-10-26 22.40.transcript.md"  once transcribed
 */
object Recordings {
    private const val TAG = "Recordings"

    data class Active(
        val docId: Long, val title: String, val audio: File, val timeline: File,
        val startedAt: Long, val paused: Boolean, val elapsedMs: Long
    )

    /** The recording running now, or null. [Active.elapsedMs] is refreshed about once a second. */
    val active = MutableStateFlow<Active?>(null)
    val error = MutableStateFlow("")
    /** Changes whenever a recording is saved, transcribed or deleted (lists refresh on it). */
    val changes = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: MediaRecorder? = null
    private val events = JSONArray()
    private var meta = JSONObject()
    private var clockStart = 0L      // elapsedRealtime when the audio clock last (re)started
    private var clockBase = 0L       // audio ms recorded before that
    private var watcher: Job? = null
    private var ticker: Job? = null
    private var lastPage = -1

    /** Audio time now, in ms (pauses not counted). */
    fun now(): Long {
        val a = active.value ?: return 0
        return if (a.paused) clockBase else clockBase + (SystemClock.elapsedRealtime() - clockStart)
    }

    // ------------------------------------------------------------------------ where files go

    /** The book's own folder when it has one, otherwise <library>/Recordings/<title>. */
    fun folderFor(context: Context, doc: DocumentEntity, settings: SettingsEntity): File {
        val parent = File(doc.filePath).parentFile
        val own = parent != null && parent.isDirectory && parent.name != "Books" && parent.name != "Articles"
        val base = if (own) parent!! else File(
            StorageManager.getRootDirectory(context, settings.customStoragePath, settings.storageLocationType),
            "Recordings/" + MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "Untitled" })
        return File(base, "Recordings").apply { mkdirs() }
    }

    data class Info(
        val audio: File, val timeline: File, val transcript: File,
        val title: String, val startedAt: Long, val durationMs: Long, val events: Int, val language: String
    ) {
        val id: String get() = audio.nameWithoutExtension
        val transcribed: Boolean get() = transcript.isFile
    }

    fun list(context: Context, doc: DocumentEntity, settings: SettingsEntity): List<Info> {
        val dir = folderFor(context, doc, settings)
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.equals("m4a", true) }
            .mapNotNull { info(it) }
            .sortedByDescending { it.startedAt }
    }

    fun info(audio: File): Info? {
        val base = audio.nameWithoutExtension
        val tl = File(audio.parentFile, "$base.timeline.json")
        val tr = File(audio.parentFile, "$base.transcript.md")
        val j = runCatching { JSONObject(tl.readText()) }.getOrNull()
        return Info(audio, tl, tr, j?.optString("documentTitle").orEmpty().ifBlank { base },
            j?.optLong("started") ?: audio.lastModified(), j?.optLong("durationMs") ?: 0L,
            j?.optJSONArray("events")?.length() ?: 0, j?.optString("language").orEmpty().ifBlank { "auto" })
    }

    fun find(context: Context, doc: DocumentEntity, settings: SettingsEntity, id: String): Info? =
        list(context, doc, settings).firstOrNull { it.id == id }

    fun timeline(info: Info): JSONObject = runCatching { JSONObject(info.timeline.readText()) }.getOrDefault(JSONObject())

    // ------------------------------------------------------------------------ start / stop

    fun start(context: Context, doc: DocumentEntity, page: Int, pageLabel: String, chapter: String) {
        if (active.value != null) return
        val app = context.applicationContext
        val i = Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra("docId", doc.id).putExtra("page", page).putExtra("label", pageLabel).putExtra("chapter", chapter)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
    }

    fun stop(context: Context) { send(context, RecordingService.ACTION_STOP) }
    fun pause(context: Context) { send(context, RecordingService.ACTION_PAUSE) }
    fun resume(context: Context) { send(context, RecordingService.ACTION_RESUME) }

    private fun send(context: Context, action: String) {
        if (active.value == null) return
        context.applicationContext.startService(Intent(context.applicationContext, RecordingService::class.java).setAction(action))
    }

    /** Called by the service. Returns false when the microphone could not be started. */
    @SuppressLint("MissingPermission")
    internal suspend fun begin(context: Context, docId: Long, page: Int, label: String, chapter: String): Boolean {
        val db = AppDatabase.getInstance(context)
        val doc = db.documentDao().getDocumentById(docId) ?: return false
        val settings = db.settingsDao().getSettingsSnapshot() ?: SettingsEntity()
        val dir = folderFor(context, doc, settings)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date())
        var base = MarkdownExporter.sanitizeFilename("${doc.title.take(60)} – $stamp")
        var n = 2
        while (File(dir, "$base.m4a").exists()) base = MarkdownExporter.sanitizeFilename("${doc.title.take(60)} – $stamp ($n)").also { n++ }
        val audio = File(dir, "$base.m4a")
        val tl = File(dir, "$base.timeline.json")
        val r = runCatching { startRecorder(context, audio, 16000, 40000) }
            .recoverCatching { startRecorder(context, audio, 44100, 64000) }
            .getOrElse { e -> error.value = "Could not use the microphone: ${e.message}"; Log.w(TAG, "start", e); audio.delete(); return false }
        recorder = r
        synchronized(events) { while (events.length() > 0) events.remove(0) }
        meta = JSONObject().put("version", 1).put("documentId", doc.id).put("documentTitle", doc.title)
            .put("author", doc.author).put("fileType", doc.fileType).put("started", System.currentTimeMillis())
            .put("audio", audio.name).put("language", "auto")
        clockBase = 0; clockStart = SystemClock.elapsedRealtime(); lastPage = -1
        active.value = Active(doc.id, doc.title, audio, tl, System.currentTimeMillis(), false, 0)
        error.value = ""
        add(JSONObject().put("type", "start").put("page", page).put("label", label).put("chapter", chapter))
        lastPage = page
        watchHighlights(context, doc)
        ticker = scope.launch {
            var tick = 0
            while (active.value != null) {
                active.value?.let { a -> active.value = a.copy(elapsedMs = now()) }
                if (++tick % 10 == 0) save()
                kotlinx.coroutines.delay(1000)
            }
        }
        save()
        return true
    }

    @Suppress("DEPRECATION")
    private fun startRecorder(context: Context, out: File, rate: Int, bitRate: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(rate)
            r.setAudioEncodingBitRate(bitRate)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            return r
        } catch (e: Exception) {
            runCatching { r.release() }
            throw e
        }
    }

    internal fun doPause() {
        val a = active.value ?: return
        if (a.paused || Build.VERSION.SDK_INT < 24) return
        runCatching { recorder?.pause() }.onFailure { return }
        clockBase += SystemClock.elapsedRealtime() - clockStart
        add(JSONObject().put("type", "pause"))
        active.value = a.copy(paused = true, elapsedMs = clockBase)
        save()
    }

    internal fun doResume() {
        val a = active.value ?: return
        if (!a.paused || Build.VERSION.SDK_INT < 24) return
        runCatching { recorder?.resume() }.onFailure { return }
        clockStart = SystemClock.elapsedRealtime()
        active.value = a.copy(paused = false)
        add(JSONObject().put("type", "resume"))
        save()
    }

    internal fun finish() {
        val a = active.value ?: return
        add(JSONObject().put("type", "stop"))
        meta.put("durationMs", now())
        watcher?.cancel(); ticker?.cancel()
        runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "stop", it) }
        runCatching { recorder?.release() }
        recorder = null
        save()
        active.value = null
        if (!a.audio.isFile || a.audio.length() < 1024) { a.audio.delete(); a.timeline.delete() }
        changes.value++
    }

    // ------------------------------------------------------------------------ the timeline

    private fun add(e: JSONObject) {
        if (active.value == null) return
        e.put("t", now())
        synchronized(events) { events.put(e) }
    }

    private fun save() {
        val a = active.value ?: return
        val o = JSONObject(meta.toString())
        synchronized(events) { o.put("events", JSONArray(events.toString())) }
        if (!o.has("durationMs")) o.put("durationMs", now())
        runCatching {
            val tmp = File(a.timeline.parentFile, a.timeline.name + ".tmp")
            tmp.writeText(o.toString(1))
            if (!tmp.renameTo(a.timeline)) { a.timeline.writeText(o.toString(1)); tmp.delete() }
        }.onFailure { Log.w(TAG, "save", it) }
    }

    /** The reader reports where you are. Repeated reports of the same page are ignored. */
    fun page(docId: Long, page: Int, label: String, chapter: String) {
        val a = active.value ?: return
        if (page == lastPage || page <= 0) return
        lastPage = page
        add(JSONObject().put("type", "page").put("page", page).put("label", label).put("chapter", chapter)
            .put("doc", docId).put("sameBook", docId == a.docId))
    }

    /** New highlights and changed notes, from the tablet or the web view, go on the timeline. */
    private fun watchHighlights(context: Context, doc: DocumentEntity) {
        val dao = AppDatabase.getInstance(context).highlightDao()
        watcher = scope.launch {
            var known: Map<Long, HighlightEntity>? = null
            val related = (listOf(doc.id) + FolderBooks.partsOf(doc.id) + listOfNotNull(FolderBooks.folderOf(doc.id))).toSet()
            dao.getAllHighlights().collect { all ->
                val mine = all.filter { it.documentId in related || active.value?.docId == it.documentId }
                val before = known
                known = mine.associateBy { it.id }
                if (before == null) return@collect
                mine.forEach { h ->
                    val old = before[h.id]
                    if (old == null) {
                        add(JSONObject().put("type", "highlight").put("id", h.id).put("doc", h.documentId)
                            .put("page", h.pageNumber).put("label", h.printedPageLabel.ifBlank { h.pageEquivalent })
                            .put("chapter", h.chapterTitle).put("title", h.title).put("text", h.highlightedText.take(600)))
                        save()
                    } else if (old.userNotes != h.userNotes && h.userNotes.isNotBlank()) {
                        add(JSONObject().put("type", "note").put("id", h.id).put("text", h.userNotes.take(600)))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------ managing files

    fun delete(info: Info) {
        listOf(info.audio, info.timeline, info.transcript, File(info.audio.parentFile, info.id + ".transcript.json")).forEach { it.delete() }
        SpokenComments.removeRecording(info.id)
        changes.value++
    }

    fun rename(info: Info, title: String) {
        val tl = timeline(info)
        tl.put("documentTitle", tl.optString("documentTitle")).put("name", title.trim())
        info.timeline.writeText(tl.toString(1))
        changes.value++
    }
}

/** Foreground service holding the microphone while a recording runs. */
class RecordingService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        private const val CHANNEL = "recording"
        private const val ID = 4711
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                foreground("Starting…")
                val docId = intent.getLongExtra("docId", 0)
                val page = intent.getIntExtra("page", 1)
                val label = intent.getStringExtra("label").orEmpty()
                val chapter = intent.getStringExtra("chapter").orEmpty()
                scope.launch {
                    val ok = kotlinx.coroutines.withContext(Dispatchers.IO) { Recordings.begin(this@RecordingService, docId, page, label, chapter) }
                    if (!ok) { stopSelfNow(); return@launch }
                    foreground(null)
                    // Keep the notification's pause/resume button in step.
                    Recordings.active.collect { a -> if (a == null) stopSelfNow() }
                }
            }
            ACTION_PAUSE -> { Recordings.doPause(); foreground(null) }
            ACTION_RESUME -> { Recordings.doResume(); foreground(null) }
            ACTION_STOP -> { Recordings.finish(); stopSelfNow() }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun foreground(text: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Marginalia records what you say while reading"
                setShowBadge(false)
            })
        }
        val imm = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(a), imm or PendingIntent.FLAG_UPDATE_CURRENT)
        val a = Recordings.active.value
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 9, it, imm or PendingIntent.FLAG_UPDATE_CURRENT) }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (a?.paused == true) "Recording paused" else "Recording")
            .setContentText(text ?: a?.title.orEmpty())
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { open?.let { setContentIntent(it) } }
        if (a != null) {
            if (Build.VERSION.SDK_INT >= 24) b.addAction(0, if (a.paused) "Resume" else "Pause", action(if (a.paused) ACTION_RESUME else ACTION_PAUSE, 11))
            b.addAction(0, "Stop", action(ACTION_STOP, 12))
        }
        val n: Notification = b.build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, n)
    }

    override fun onDestroy() {
        if (Recordings.active.value != null) Recordings.finish()
        super.onDestroy()
    }
}
