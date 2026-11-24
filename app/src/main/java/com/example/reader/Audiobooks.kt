package com.example.reader

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * The audiobook belonging to a book: its audio files (one .m4b with chapters, or several .mp3 in order), kept on
 * the tablet under files/audiobooks/<book id>/ (not in backups — too large), with audiobook.json:
 *   { files: [{name, title, durationMs}], chapters: [{title, startMs}], positionMs, speed, updatedAt }
 * Positions are on one timeline across all the files. The tablet's player and the web view's player share it.
 */
object Audiobooks {
    val EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav")
    private val lock = Any()
    /** Bumped on every change (screens refresh on it). */
    val changes = MutableStateFlow(0)

    fun dir(ctx: Context, docId: Long) = File(ctx.applicationContext.filesDir, "audiobooks/$docId")
    private fun meta(ctx: Context, docId: Long) = File(dir(ctx, docId), "audiobook.json")

    fun has(ctx: Context, docId: Long): Boolean = meta(ctx, docId).isFile && files(ctx, docId).isNotEmpty()

    fun read(ctx: Context, docId: Long): JSONObject = synchronized(lock) {
        runCatching { JSONObject(meta(ctx, docId).readText()) }.getOrDefault(JSONObject().put("files", JSONArray()).put("chapters", JSONArray()).put("positionMs", 0L).put("speed", 1.0))
    }

    private fun write(ctx: Context, docId: Long, o: JSONObject) = synchronized(lock) {
        val f = meta(ctx, docId); f.parentFile?.mkdirs()
        val t = File(f.parentFile, f.name + ".tmp"); t.writeText(o.toString()); t.renameTo(f)
        changes.value++
    }

    data class Part(val file: File, val title: String, val durationMs: Long, val startMs: Long)

    /** The files in playing order, each with where it starts on the shared timeline. */
    fun files(ctx: Context, docId: Long): List<Part> {
        val o = read(ctx, docId); val a = o.optJSONArray("files") ?: JSONArray(); val out = ArrayList<Part>(); var at = 0L
        for (i in 0 until a.length()) { val f = a.getJSONObject(i); val file = File(dir(ctx, docId), f.optString("name"))
            if (!file.isFile) continue
            val d = f.optLong("durationMs"); out += Part(file, f.optString("title"), d, at); at += d }
        return out
    }

    fun json(ctx: Context, docId: Long): JSONObject {
        val o = read(ctx, docId); val parts = files(ctx, docId)
        return JSONObject().put("exists", parts.isNotEmpty())
            .put("files", JSONArray().apply { parts.forEachIndexed { i, p -> put(JSONObject().put("index", i).put("name", p.file.name).put("title", p.title).put("durationMs", p.durationMs).put("startMs", p.startMs).put("size", p.file.length())) } })
            .put("chapters", o.optJSONArray("chapters") ?: JSONArray())
            .put("durationMs", parts.sumOf { it.durationMs })
            .put("positionMs", o.optLong("positionMs")).put("speed", o.optDouble("speed", 1.0)).put("updatedAt", o.optLong("updatedAt"))
    }

    /** Adds one audio file (moved in from [src]); the files are kept in natural name order (track 2 before track 10). */
    fun add(ctx: Context, docId: Long, src: File, name: String): JSONObject {
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in EXTENSIONS) { "“$name” is not an audio file (MP3, M4B/M4A, AAC, OGG/Opus, FLAC or WAV)." }
        val d = dir(ctx, docId).apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)
        var target = File(d, safe); var n = 2
        while (target.exists()) target = File(d, safe.substringBeforeLast('.') + " ($n)." + ext).also { n++ }
        if (!src.renameTo(target)) { src.copyTo(target, true); src.delete() }
        val (dur, title) = probe(target)
        require(dur > 0) { "“$name” could not be read as audio." }
        synchronized(lock) {
            val o = read(ctx, docId); val a = o.optJSONArray("files") ?: JSONArray()
            val list = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
            list += JSONObject().put("name", target.name).put("title", title.ifBlank { target.nameWithoutExtension }).put("durationMs", dur)
            list.sortWith { x, y -> naturalCompare(x.optString("name"), y.optString("name")) }
            o.put("files", JSONArray(list))
            o.put("chapters", chapters(ctx, docId, list))
            write(ctx, docId, o)
        }
        return json(ctx, docId)
    }

    fun remove(ctx: Context, docId: Long, index: Int?): JSONObject {
        if (index == null) { AudiobookPlayer.unload(docId); dir(ctx, docId).deleteRecursively(); changes.value++; return json(ctx, docId) }
        synchronized(lock) {
            val o = read(ctx, docId); val a = o.optJSONArray("files") ?: JSONArray()
            val list = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
            val gone = list.getOrNull(index) ?: throw NoSuchElementException("No such file")
            AudiobookPlayer.unload(docId)
            File(dir(ctx, docId), gone.optString("name")).delete()
            list.removeAt(index)
            o.put("files", JSONArray(list)).put("chapters", chapters(ctx, docId, list)).put("positionMs", 0L)
            write(ctx, docId, o)
        }
        return json(ctx, docId)
    }

    /** Saves where you are (from the tablet's player or the web view). */
    fun savePosition(ctx: Context, docId: Long, positionMs: Long, speed: Double? = null) {
        if (!meta(ctx, docId).isFile) return
        synchronized(lock) {
            val o = read(ctx, docId)
            o.put("positionMs", positionMs.coerceAtLeast(0)).put("updatedAt", System.currentTimeMillis())
            if (speed != null && speed in 0.5..3.0) o.put("speed", speed)
            val f = meta(ctx, docId); val t = File(f.parentFile, f.name + ".tmp"); t.writeText(o.toString()); t.renameTo(f)
        }
    }

    private fun probe(f: File): Pair<Long, String> = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(f.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) to (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty())
        } finally { r.release() }
    }.getOrDefault(0L to "")

    /** Chapters: from the files' own chapter marks (Nero “chpl”, as most .m4b have), else one per file. */
    private fun chapters(ctx: Context, docId: Long, list: List<JSONObject>): JSONArray {
        val out = JSONArray(); var at = 0L
        for (f in list) {
            val file = File(dir(ctx, docId), f.optString("name"))
            val marks = if (file.extension.lowercase() in setOf("m4b", "m4a", "mp4", "aac")) runCatching { mp4Chapters(file) }.getOrDefault(emptyList()) else emptyList()
            if (marks.size > 1) marks.forEach { (t, ms) -> out.put(JSONObject().put("title", t).put("startMs", at + ms)) }
            else out.put(JSONObject().put("title", f.optString("title").ifBlank { file.nameWithoutExtension }).put("startMs", at))
            at += f.optLong("durationMs")
        }
        return out
    }

    /** The Nero chapter list (moov/udta/chpl) of an MP4/M4B file: (title, start ms). */
    private fun mp4Chapters(f: File): List<Pair<String, Long>> = RandomAccessFile(f, "r").use { raf ->
        fun find(start: Long, end: Long, type: String): Pair<Long, Long>? {
            var p = start
            while (p + 8 <= end) {
                raf.seek(p); var size = raf.readInt().toLong() and 0xffffffffL
                val t = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
                var header = 8L
                if (size == 1L) { size = raf.readLong(); header = 16 } else if (size == 0L) size = end - p
                if (size < header) return null
                if (t == type) return (p + header) to (p + size)
                p += size
            }
            return null
        }
        val moov = find(0, raf.length(), "moov") ?: return emptyList()
        val udta = find(moov.first, moov.second, "udta") ?: return emptyList()
        val chpl = find(udta.first, udta.second, "chpl") ?: return emptyList()
        raf.seek(chpl.first)
        val version = raf.readUnsignedByte(); raf.skipBytes(3)
        if (version == 1) raf.skipBytes(4)
        val count = raf.readUnsignedByte()
        (0 until count).map {
            val start = raf.readLong()
            val len = raf.readUnsignedByte()
            val title = ByteArray(len).also { raf.readFully(it) }.toString(Charsets.UTF_8)
            title to start / 10_000
        }.filter { it.second >= 0 }
    }

    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList(); val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}

/**
 * The tablet's audiobook player (one book at a time): Media3's ExoPlayer over the book's files as one playlist,
 * run by [AudiobookService] so it plays with the screen off and answers headphones, Bluetooth and the lock screen.
 * All calls are made on the main thread.
 */
object AudiobookPlayer {
    data class State(
        val docId: Long = 0, val title: String = "", val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0,
        val speed: Float = 1f, val chapter: String = "", val chapterIndex: Int = -1, val sleepAt: Long = 0, val sleepAtChapterEnd: Boolean = false
    )
    val state = MutableStateFlow(State())

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var player: ExoPlayer? = null
    private var parts: List<Audiobooks.Part> = emptyList()
    private var chapters: List<Pair<String, Long>> = emptyList()
    private var lastSaved = 0L
    private var sleepChapterEnd = -1L

    fun player(ctx: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(30_000).setSeekForwardIncrementMs(30_000)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { tick(); save(force = true) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { tick() }
        })
        player = p
        return p
    }

    /** Loads [docId]'s audiobook at its saved place (does nothing when it is already loaded). */
    fun load(ctx: Context, docId: Long, title: String): Boolean {
        app = ctx.applicationContext
        if (state.value.docId == docId && player != null && parts.isNotEmpty()) return true
        save(force = true)
        val ps = Audiobooks.files(ctx, docId); if (ps.isEmpty()) return false
        val meta = Audiobooks.read(ctx, docId)
        parts = ps
        val ch = meta.optJSONArray("chapters") ?: JSONArray()
        chapters = (0 until ch.length()).map { ch.getJSONObject(it).let { c -> c.optString("title") to c.optLong("startMs") } }
        val p = player(ctx)
        p.setMediaItems(ps.mapIndexed { i, part -> MediaItem.Builder().setUri(android.net.Uri.fromFile(part.file)).setMediaId("$docId/$i")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(part.title).setAlbumTitle(title).build()).build() })
        val (idx, off) = locate(meta.optLong("positionMs"))
        p.seekTo(idx, off)
        p.setPlaybackSpeed(meta.optDouble("speed", 1.0).toFloat())
        p.prepare()
        state.value = State(docId, title, false, meta.optLong("positionMs"), ps.sumOf { it.durationMs }, meta.optDouble("speed", 1.0).toFloat())
        startTicker()
        return true
    }

    fun unload(docId: Long) = main.post {
        if (state.value.docId != docId) return@post
        player?.stop(); player?.clearMediaItems(); parts = emptyList(); state.value = State()
    }

    private fun locate(globalMs: Long): Pair<Int, Long> {
        val i = parts.indexOfLast { it.startMs <= globalMs }.coerceAtLeast(0)
        return i to (globalMs - (parts.getOrNull(i)?.startMs ?: 0)).coerceAtLeast(0)
    }
    private fun global(): Long { val p = player ?: return 0; return (parts.getOrNull(p.currentMediaItemIndex)?.startMs ?: 0) + p.currentPosition }

    fun play(ctx: Context) {
        val p = player ?: return
        // The service shows the player in the notification and keeps it alive with the screen off.
        runCatching { ctx.startService(Intent(ctx.applicationContext, AudiobookService::class.java)) }
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        p.play()
    }
    fun pause() { player?.pause() }
    fun toggle(ctx: Context) { if (player?.isPlaying == true) pause() else play(ctx) }
    fun seekTo(globalMs: Long) { val p = player ?: return; val (i, o) = locate(globalMs.coerceIn(0, state.value.durationMs)); p.seekTo(i, o); tick(); save(force = true) }
    fun skip(deltaMs: Long) = seekTo(global() + deltaMs)
    fun setSpeed(s: Float) { player?.setPlaybackSpeed(s); tick(); save(force = true) }
    fun chapterStep(dir: Int) {
        if (chapters.isEmpty()) return
        val now = global(); val i = chapters.indexOfLast { it.second <= now }
        val target = if (dir < 0) { if (i >= 0 && now - chapters[i].second > 3000) chapters[i].second else chapters.getOrNull(i - 1)?.second ?: 0 } else chapters.getOrNull(i + 1)?.second ?: return
        seekTo(target)
    }
    fun chapters(): List<Pair<String, Long>> = chapters

    /** Sleep timer: [minutes] > 0 stops after that long; -1 at the end of this chapter; 0 off. */
    fun sleep(minutes: Int) {
        sleepChapterEnd = -1
        when {
            minutes > 0 -> state.value = state.value.copy(sleepAt = System.currentTimeMillis() + minutes * 60_000L, sleepAtChapterEnd = false)
            minutes < 0 -> { val now = global(); sleepChapterEnd = chapters.firstOrNull { it.second > now }?.second ?: state.value.durationMs
                state.value = state.value.copy(sleepAt = 0, sleepAtChapterEnd = true) }
            else -> state.value = state.value.copy(sleepAt = 0, sleepAtChapterEnd = false)
        }
    }

    /** Where the web view or another place moved to (applied when the tablet is not playing). */
    fun positionFromElsewhere(docId: Long, positionMs: Long) = main.post {
        if (state.value.docId == docId && player?.isPlaying != true) { val (i, o) = locate(positionMs); player?.seekTo(i, o); tick() }
    }

    private var ticking = false
    private fun startTicker() {
        if (ticking) return; ticking = true
        main.post(object : Runnable { override fun run() {
            tick(); save(force = false)
            val s = state.value
            if (s.playing && s.sleepAt > 0 && System.currentTimeMillis() >= s.sleepAt) { pause(); state.value = s.copy(sleepAt = 0) }
            if (s.playing && sleepChapterEnd > 0 && global() >= sleepChapterEnd) { pause(); sleepChapterEnd = -1; state.value = state.value.copy(sleepAtChapterEnd = false) }
            if (player != null && state.value.docId != 0L) main.postDelayed(this, if (s.playing) 500 else 2000) else ticking = false
        } })
    }

    private fun tick() {
        val p = player ?: return
        if (parts.isEmpty()) return
        val g = global(); val ci = chapters.indexOfLast { it.second <= g }
        state.value = state.value.copy(playing = p.isPlaying, positionMs = g, speed = p.playbackParameters.speed, chapter = chapters.getOrNull(ci)?.first.orEmpty(), chapterIndex = ci)
    }

    private fun save(force: Boolean) {
        val ctx = app ?: return; val s = state.value
        if (s.docId == 0L || parts.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && (!s.playing || now - lastSaved < 5000)) return
        lastSaved = now
        val pos = global(); val speed = s.speed.toDouble()
        Thread { Audiobooks.savePosition(ctx, s.docId, pos, speed) }.start()
    }
}

/** Keeps the audiobook playing with the screen off: Media3 shows it in the notification and on the lock screen. */
class AudiobookService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val s = MediaSession.Builder(this, AudiobookPlayer.player(this)).setId("audiobook").build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }
}
