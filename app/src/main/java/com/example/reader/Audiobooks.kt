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
 * The audiobook belonging to a book, kept in the book's own folder (so it shows under Files and syncs with it):
 *   <book folder>/Audiobook – <Title>.m4b         one file
 *   <book folder>/Audiobook – <Title>/01 ….mp3    several files (a folder of its own)
 * What the app knows about it (the files in order, chapters, where you are, speed) is kept in the app:
 *   files/audiobooks/<book id>/audiobook.json  { files: [{path, name, title, durationMs}], chapters: [{title, startMs}], positionMs, speed, updatedAt }
 * Positions are on one timeline across all the files. The tablet's player and the web view's player share it.
 */
object Audiobooks {
    val EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav")
    private val lock = Any()
    /** Bumped on every change (screens refresh on it). */
    val changes = MutableStateFlow(0)

    /** The app's own record for the book (not the audio). */
    fun dir(ctx: Context, docId: Long) = File(ctx.applicationContext.filesDir, "audiobooks/$docId")
    private fun meta(ctx: Context, docId: Long) = File(dir(ctx, docId), "audiobook.json")

    private fun baseName(doc: com.example.data.local.DocumentEntity) = "Audiobook – " + MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "Untitled" }.take(120)

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

    private fun fileOf(ctx: Context, docId: Long, f: JSONObject): File =
        f.optString("path").takeIf { it.isNotBlank() }?.let { File(it) } ?: File(dir(ctx, docId), f.optString("name"))

    /** The files in playing order, each with where it starts on the shared timeline. */
    fun files(ctx: Context, docId: Long): List<Part> {
        val o = read(ctx, docId); val a = o.optJSONArray("files") ?: JSONArray(); val out = ArrayList<Part>(); var at = 0L
        for (i in 0 until a.length()) { val f = a.getJSONObject(i); val file = fileOf(ctx, docId, f)
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

    /** A place for an incoming file before it is put in the book's folder. */
    fun incoming(ctx: Context): File = File(ctx.applicationContext.filesDir, "audiobooks/.incoming/${System.nanoTime()}.part").apply { parentFile?.mkdirs() }

    private fun moveFile(src: File, target: File) { target.parentFile?.mkdirs(); if (!src.renameTo(target)) { src.copyTo(target, true); src.delete() } }
    private fun unique(f: File): File { var t = f; var n = 2; while (t.exists()) { t = File(f.parentFile, f.nameWithoutExtension + " ($n)." + f.extension); n++ }; return t }

    /**
     * Puts the files where they belong: one file lies in the book's folder as “Audiobook – <Title>.ext”;
     * two or more go in a folder “Audiobook – <Title>” under their own names.
     */
    private suspend fun arrange(ctx: Context, doc: com.example.data.local.DocumentEntity, list: MutableList<JSONObject>) {
        val folder = ItemFiles.folderOf(ctx, doc).apply { mkdirs() }
        val base = baseName(doc)
        val sub = File(folder, base)
        for (f in list) {
            val cur = fileOf(ctx, doc.id, f); if (!cur.isFile) continue
            val orig = f.optString("name").ifBlank { cur.name }
            val want = if (list.size == 1) File(folder, "$base.${cur.extension}") else File(sub, orig)
            if (cur.canonicalPath != want.canonicalPath) { val t = unique(want); moveFile(cur, t); f.put("path", t.absolutePath) }
            else f.put("path", cur.absolutePath)
        }
        // A folder left empty (from two files down to one) is removed.
        if (list.size <= 1 && sub.isDirectory && (sub.listFiles()?.isEmpty() == true)) sub.delete()
    }

    /** Adds one audio file (moved in from [src]); the files are kept in natural name order (track 2 before track 10). */
    suspend fun add(ctx: Context, doc: com.example.data.local.DocumentEntity, src: File, name: String): JSONObject {
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in EXTENSIONS) { "“$name” is not an audio file (MP3, M4B/M4A, AAC, OGG/Opus, FLAC or WAV)." }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)
        val (dur, title) = probe(src)
        require(dur > 0) { "“$name” could not be read as audio." }
        AudiobookPlayer.unload(doc.id)
        migrate(ctx, doc)
        val o = read(ctx, doc.id); val a = o.optJSONArray("files") ?: JSONArray()
        val list = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        // Into the folder first (under its own name), then arranged with the others.
        val folder = ItemFiles.folderOf(ctx, doc).apply { mkdirs() }
        val landed = unique(File(File(folder, baseName(doc)), safe)); moveFile(src, landed)
        list += JSONObject().put("name", safe).put("path", landed.absolutePath).put("title", title.ifBlank { safe.substringBeforeLast('.') }).put("durationMs", dur)
        list.sortWith { x, y -> naturalCompare(x.optString("name"), y.optString("name")) }
        arrange(ctx, doc, list)
        o.put("files", JSONArray(list)).put("chapters", chapters(ctx, doc.id, list))
        write(ctx, doc.id, o)
        return json(ctx, doc.id)
    }

    /** Removes one file ([index]) or the whole audiobook (null): the audio files are deleted from the book's folder. */
    suspend fun remove(ctx: Context, doc: com.example.data.local.DocumentEntity, index: Int?): JSONObject {
        AudiobookPlayer.unload(doc.id)
        val o = read(ctx, doc.id); val a = o.optJSONArray("files") ?: JSONArray()
        val list = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        val gone = if (index == null) list.toList() else listOf(list.getOrNull(index) ?: throw NoSuchElementException("No such file"))
        gone.forEach { fileOf(ctx, doc.id, it).delete() }
        list.removeAll(gone)
        val sub = File(ItemFiles.folderOf(ctx, doc), baseName(doc))
        if (list.isEmpty()) {
            if (sub.isDirectory && sub.listFiles()?.isEmpty() == true) sub.delete()
            AudiobookSync.syncFile(ctx, doc).delete()   // the sync map belongs to the audio
            dir(ctx, doc.id).deleteRecursively(); changes.value++; return json(ctx, doc.id)
        }
        arrange(ctx, doc, list)
        o.put("files", JSONArray(list)).put("chapters", chapters(ctx, doc.id, list)).put("positionMs", 0L)
        write(ctx, doc.id, o)
        return json(ctx, doc.id)
    }

    /** Audio kept in the app by the first version is moved into the book's folder. */
    suspend fun migrate(ctx: Context, doc: com.example.data.local.DocumentEntity) {
        val o = read(ctx, doc.id); val a = o.optJSONArray("files") ?: return
        val list = (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        if (list.isEmpty() || list.all { it.optString("path").isNotBlank() }) return
        arrange(ctx, doc, list)
        o.put("files", JSONArray(list)); write(ctx, doc.id, o)
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
            val file = fileOf(ctx, docId, f)
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

/**
 * Syncing an audiobook with its book. The transcription (Whisper, in the browser for now) is kept by the app while it
 * runs, so it can be resumed; the finished sync map lies in the book's folder beside the audio:
 *   <book folder>/Audiobook – <Title>.sync.json
 *   { version, kind: "pdf"|"epub", engine, language, created, coverage, durationMs,
 *     sentences: [{ t: start ms, l: locator, x: the first words }] }
 * Locators: PDF {p: page (1-based), w: [first word, last word]} in the page's words;
 *           EPUB {c: chapter, p: paragraph, s: start char, e: end char} in the chapter's paragraphs.
 */
object AudiobookSync {
    private fun transcriptFile(ctx: Context, docId: Long) = File(Audiobooks.dir(ctx, docId), "transcript.json")

    suspend fun syncFile(ctx: Context, doc: com.example.data.local.DocumentEntity): File =
        File(ItemFiles.folderOf(ctx, doc), "Audiobook – " + MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "Untitled" }.take(120) + ".sync.json")

    fun transcript(ctx: Context, docId: Long): JSONObject = runCatching { JSONObject(transcriptFile(ctx, docId).readText()) }.getOrDefault(JSONObject())
    fun saveTranscript(ctx: Context, docId: Long, o: JSONObject) {
        val f = transcriptFile(ctx, docId); f.parentFile?.mkdirs()
        val t = File(f.parentFile, f.name + ".tmp"); t.writeText(o.toString()); t.renameTo(f)
    }

    suspend fun saveMap(ctx: Context, doc: com.example.data.local.DocumentEntity, o: JSONObject): JSONObject {
        require((o.optJSONArray("sentences")?.length() ?: 0) > 0) { "The sync map is empty." }
        val f = syncFile(ctx, doc); f.parentFile?.mkdirs()
        val t = File(f.parentFile, "." + f.name + ".tmp"); t.writeText(o.toString()); t.renameTo(f)
        Audiobooks.changes.value++
        return info(ctx, doc)
    }

    suspend fun map(ctx: Context, doc: com.example.data.local.DocumentEntity): File? = syncFile(ctx, doc).takeIf { it.isFile }

    suspend fun info(ctx: Context, doc: com.example.data.local.DocumentEntity): JSONObject {
        val f = map(ctx, doc) ?: return JSONObject().put("exists", false).put("transcribedMs", transcript(ctx, doc.id).optLong("doneMs"))
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        return JSONObject().put("exists", true).put("name", f.name).put("coverage", o.optDouble("coverage", 0.0)).put("created", o.optLong("created"))
            .put("engine", o.optString("engine")).put("sentences", o.optJSONArray("sentences")?.length() ?: 0).put("transcribedMs", transcript(ctx, doc.id).optLong("doneMs"))
    }

    /** The book's text in units with what locators need: PDF pages with their words, or EPUB paragraphs. */
    fun epubText(doc: com.example.data.local.DocumentEntity): JSONObject {
        val book = EpubParser.parseEpub(File(doc.filePath), "audiobook-${doc.id}")
        val units = JSONArray()
        book.chapters.forEachIndexed { c, ch -> ch.paragraphs.forEachIndexed { p, text -> if (text.isNotBlank()) units.put(JSONObject().put("c", c).put("p", p).put("h", ch.fileKey).put("text", text)) } }
        return JSONObject().put("kind", "epub").put("units", units).put("language", book.language)
    }

    /** 16 kHz mono 16-bit little-endian PCM of [lenMs] from [fromMs] in one audio file (what Whisper wants). */
    fun pcmWindow(file: File, fromMs: Long, lenMs: Long): ByteArray {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("No audio in the file.")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val fromUs = fromMs * 1000; val toUs = (fromMs + lenMs) * 1000
        ex.seekTo(fromUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        val out = java.io.ByteArrayOutputStream((lenMs * 32).toInt().coerceAtMost(64 shl 20))
        try {
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var pos = 0.0; var inIndex = 0L; var carry = 0f; var haveCarry = false; var started = false
            while (!outputDone) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > toUs + 500_000) { codec.queueInputBuffer(ib, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                if (ob == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                } else if (ob >= 0) {
                    val buf = codec.getOutputBuffer(ob)!!.order(java.nio.ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.asShortBuffer()
                    val frames = sb.remaining() / channels
                    val usPerFrame = 1_000_000.0 / rate
                    val step = rate / 16000.0
                    for (fi in 0 until frames) {
                        val tUs = info.presentationTimeUs + (fi * usPerFrame).toLong()
                        if (tUs < fromUs) continue
                        if (tUs >= toUs) { outputDone = true; break }
                        if (!started) { started = true; pos = 0.0; inIndex = 0 }
                        var acc = 0f
                        for (c in 0 until channels) acc += sb.get(fi * channels + c)
                        val v = acc / channels
                        val prev = if (haveCarry) carry else v
                        while (pos <= inIndex) {
                            val frac = (pos - (inIndex - 1)).toFloat().coerceIn(0f, 1f)
                            val s = (prev + (v - prev) * frac).toInt().coerceIn(-32768, 32767)
                            out.write(s and 0xff); out.write((s shr 8) and 0xff)
                            pos += step
                        }
                        carry = v; haveCarry = true; inIndex++
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
        return out.toByteArray()
    }
}

/** A loaded sync map: sentence start times with where each sentence is (see [AudiobookSync]). */
class AudiobookSyncMap(
    val kind: String, val t: LongArray, val page: IntArray, val w0: IntArray, val w1: IntArray,
    val chapter: IntArray, val para: IntArray, val text: Array<String>, val href: Array<String>
) {
    /** For an EPUB: the sentence at [progression] (0–1) of resource [name], by its share of the resource's sentences. */
    fun atResource(name: String, progression: Double): Int {
        val base = name.substringAfterLast('/')
        val idx = (0 until size).filter { href[it].substringAfterLast('/') == base }
        if (idx.isEmpty()) return -1
        return idx[(progression.coerceIn(0.0, 1.0) * (idx.size - 1)).toInt()]
    }

    val size get() = t.size
    private val norm: Array<String> by lazy { Array(text.size) { normalize(text[it]) } }

    /** The sentence being read at [ms] (the last one started), or -1 before the first. */
    fun indexAt(ms: Long): Int {
        var lo = 0; var hi = t.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (t[mid] <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    /** The first sentence on PDF page [p] (1-based), or -1. */
    fun firstOnPage(p: Int): Int = (0 until size).firstOrNull { page[it] == p } ?: -1

    /**
     * The sentence [q] starts in: a paragraph's text (its first sentence) or a selection (the sentence it begins in).
     * Restricted to PDF page [pageHint] when given; the one nearest [near] when the text occurs more than once.
     */
    fun find(q: String, pageHint: Int? = null, near: Int = 0): Int {
        val nq = normalize(q); if (nq.length < 4) return -1
        val head = nq.take(30)
        var best = -1; var bestScore = Long.MAX_VALUE
        for (i in 0 until size) {
            if (pageHint != null && kind == "pdf" && page[i] != pageHint) continue
            val ns = norm[i]; if (ns.isEmpty()) continue
            val at = when {
                nq.contains(ns.take(30)) -> nq.indexOf(ns.take(30)).toLong()           // the paragraph contains the sentence: earliest wins
                ns.contains(head) -> 0L                                                // the selection begins inside this sentence
                else -> -1L
            }
            if (at < 0) continue
            val score = at * 100_000 + kotlin.math.abs(i - near)
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    companion object {
        fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        suspend fun load(ctx: Context, doc: com.example.data.local.DocumentEntity): AudiobookSyncMap? = runCatching {
            val f = AudiobookSync.map(ctx, doc) ?: return null
            val o = JSONObject(f.readText()); val a = o.optJSONArray("sentences") ?: return null
            val n = a.length()
            val t = LongArray(n); val pg = IntArray(n); val w0 = IntArray(n); val w1 = IntArray(n); val c = IntArray(n); val p = IntArray(n)
            val x = Array(n) { "" }; val h = Array(n) { "" }
            for (i in 0 until n) {
                val s = a.getJSONObject(i); val l = s.optJSONObject("l") ?: JSONObject()
                t[i] = s.optLong("t"); x[i] = s.optString("x")
                if (l.has("w")) { pg[i] = l.optInt("p"); l.optJSONArray("w")?.let { w0[i] = it.optInt(0); w1[i] = it.optInt(1) } }
                else { c[i] = l.optInt("c"); p[i] = l.optInt("p"); h[i] = l.optString("h") }
            }
            AudiobookSyncMap(o.optString("kind"), t, pg, w0, w1, c, p, x, h)
        }.getOrNull()
    }
}
