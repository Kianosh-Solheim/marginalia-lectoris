package com.example.reader

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.data.local.DocumentEntity
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** The Piper voices (neural, offline) the app can download and read with. */
object PiperVoices {
    data class Voice(val id: String, val label: String, val lang: String, val sizeMb: Int)
    val catalog = listOf(
        Voice("en_GB-alan-medium", "Alan — British English, male", "en", 67),
        Voice("en_GB-northern_english_male-medium", "Northern English, male", "en", 67),
        Voice("en_GB-jenny_dioco-medium", "Jenny — British English, female", "en", 67),
        Voice("en_GB-cori-high", "Cori — British English, female (finer, slower)", "en", 112),
        Voice("en_GB-alba-medium", "Alba — Scottish English, female", "en", 67),
        Voice("en_US-lessac-medium", "Lessac — American English, female", "en", 67),
        Voice("en_US-ryan-high", "Ryan — American English, male (finer, slower)", "en", 112),
        Voice("no_NO-talesyntese-medium", "Talesyntese — Norwegian (Bokmål), male", "no", 67),
    )
    /** id → share downloaded (0–1) while a voice is being fetched. */
    val downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Bumped when a voice is added or removed. */
    val changes = MutableStateFlow(0)

    fun dir(ctx: Context, id: String) = File(ctx.applicationContext.filesDir, "voices/$id")
    fun model(ctx: Context, id: String): File? = dir(ctx, id).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
    fun installed(ctx: Context, id: String) = model(ctx, id) != null && File(dir(ctx, id), "tokens.txt").isFile && File(dir(ctx, id), "espeak-ng-data").isDirectory

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** Downloads and unpacks a voice (only when you ask for it). */
    fun install(ctx: Context, id: String) {
        val v = catalog.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown voice")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-${v.id}.tar.bz2"
        val target = dir(ctx, id); val tmp = File(target.parentFile, ".$id.part").apply { deleteRecursively(); mkdirs() }
        downloading.value = downloading.value + (id to 0f)
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                require(r.isSuccessful) { "The voice could not be downloaded (HTTP ${r.code})." }
                val body = r.body ?: error("No data")
                val total = body.contentLength().takeIf { it > 0 } ?: (v.sizeMb * 1_000_000L)
                var got = 0L; var lastShown = 0L
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) { got += n; if (got - lastShown > 500_000) { lastShown = got; downloading.value = downloading.value + (id to (got.toFloat() / total).coerceAtMost(0.99f)) } }
                        return n
                    }
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered(1 shl 16))).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        // The package's own top folder is left out; nothing may land outside the voice's folder.
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isNotBlank() && !rel.contains("..")) {
                            val f = File(tmp, rel)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { tar.copyTo(it, 1 shl 16) } }
                        }
                        e = tar.nextEntry
                    }
                }
            }
            require(File(tmp, "tokens.txt").isFile && tmp.listFiles()?.any { it.name.endsWith(".onnx") } == true) { "The voice package was incomplete." }
            target.deleteRecursively(); check(tmp.renameTo(target)) { "Could not save the voice." }
            changes.value++
        } finally {
            tmp.deleteRecursively()
            downloading.value = downloading.value - id
        }
    }

    fun remove(ctx: Context, id: String) { ReadAloud.dropVoice(id); dir(ctx, id).deleteRecursively(); changes.value++ }
}

/** What read-aloud leaves out (each can be turned on and off). */
data class ReadAloudSkips(
    val citations: Boolean = true, val footnoteNumbers: Boolean = true, val headersFooters: Boolean = true,
    val urls: Boolean = true, val references: Boolean = true, val captions: Boolean = false
)

/** One sentence to read: its text as in the book (for underlining), what is said, and where it is. */
data class RaSentence(val text: String, val speak: String, val page: Int = 0, val w0: Int = 0, val w1: Int = 0, val href: String = "", val chapter: Int = 0)

/** The book's text as sentences, one unit (PDF page, EPUB chapter) at a time, with the skips applied. */
abstract class RaSource(val skips: ReadAloudSkips) {
    abstract val unitCount: Int
    protected abstract fun raw(unit: Int): List<RaSentence>
    private var refsAt = Int.MAX_VALUE
    fun sentences(unit: Int): List<RaSentence> {
        if (unit >= refsAt) return emptyList()
        val out = ArrayList<RaSentence>()
        for (s in raw(unit)) {
            if (skips.references && REFS.matches(s.text.trim())) { if (this is PdfRaSource) refsAt = unit + 1; break }
            if (skips.captions && CAPTION.containsMatchIn(s.text.take(14))) continue
            val say = clean(s.text)
            if (say.any { it.isLetterOrDigit() }) out += s.copy(speak = say)
        }
        return out
    }
    open fun close() {}

    private fun clean(t: String): String {
        var s = t
        if (skips.urls) s = s.replace(URL, " ")
        if (skips.citations) s = s.replace(CITE_PAREN, "").replace(CITE_NUM, "")
        if (skips.footnoteNumbers) s = s.replace(SUPERSCRIPTS, "").replace(GLUED_NOTE, "$1")
        return s.replace(Regex("\\s+([,.;:!?])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
    }

    companion object {
        val REFS = Regex("(?i)^(references|bibliography|works cited|literature cited|sources|litteratur(liste)?|referanser|kilder|kildeliste)\\.?$")
        val CAPTION = Regex("(?i)^(figure|fig\\.|table|figur|tabell)\\s*\\d")
        val URL = Regex("(?i)\\b(https?://\\S+|www\\.\\S+|doi:\\s*\\S+)")
        // (Hitchens 2007), (Hitchens, 2007, p. 12), (see Smith & Jones 1999; Lee 2001a: 4–7), (ibid.)
        val CITE_PAREN = Regex("\\s?\\((?:(?:see|e\\.g\\.,?|cf\\.?|jf\\.?|se)\\s+)?(?:[^()]{0,120}?\\b(?:1[5-9]\\d\\d|20\\d\\d)[a-z]?(?:[,:]?\\s*(?:pp?\\.|s\\.)?\\s*[\\d–-]+(?:\\s*[-–]\\s*\\d+)?)?(?:;[^()]{0,120})?|ibid\\.?(?:,\\s*[^()]{0,20})?)\\)")
        val CITE_NUM = Regex("\\s?\\[(?:\\d{1,3}(?:\\s*[-–,]\\s*\\d{1,3})*)\\]")
        val SUPERSCRIPTS = Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹]+")
        // “…end of a sentence.12 Next” — a note number stuck to the end of a word or punctuation.
        val GLUED_NOTE = Regex("([\\p{Ll}.,;:)\"”’])\\d{1,3}(?=\\s|$)")

        private val ABBR = setOf("mr", "mrs", "ms", "dr", "st", "prof", "eg", "ie", "etc", "vs", "cf", "nr", "no", "ca", "bl", "mfl", "osv", "dvs", "fx", "jf", "kap", "jr", "sr", "vol", "ed", "pp", "p", "ch", "fig", "s")
        fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        /** Token ranges that make sentences (the same rule as the sync with an audiobook). */
        fun sentenceRanges(tokens: List<String>): List<IntRange> {
            val out = ArrayList<IntRange>(); var s0 = 0
            for (k in tokens.indices) {
                val raw = tokens[k]; val next = tokens.getOrNull(k + 1)
                val end = k == tokens.size - 1 || (Regex("[.!?…][\"'”’»)\\]]*$").containsMatchIn(raw) && raw.length > 1 && norm(raw) !in ABBR &&
                    (next == null || Regex("^[\"'“‘«(\\[]?[\\p{Lu}\\p{N}]").containsMatchIn(next)))
                if (end) { out += s0..k; s0 = k + 1 }
            }
            if (s0 < tokens.size) out += s0 until tokens.size
            return out
        }
    }
}

class PdfRaSource(ctx: Context, doc: DocumentEntity, skips: ReadAloudSkips) : RaSource(skips) {
    private val r = PdfDocumentRenderer(File(doc.filePath), ctx.applicationContext).apply {
        updateExtractionConfiguration(PdfTextExtractionMode.fromId(doc.pdfTextExtractionMode),
            PdfPageDiagnosticsAndOcrEngine.parsePageOverridesJson(doc.pdfPageExtractionOverridesJson), doc.pdfOcrLanguage.ifBlank { "AUTO" })
    }
    override val unitCount = r.pageCount
    private val edgeLines = HashMap<String, Int>()   // running headers and footers seen (digits left out)

    override fun raw(unit: Int): List<RaSentence> {
        val words = runCatching { r.extractPageTextData(unit).words }.getOrDefault(emptyList())
        if (words.isEmpty()) return emptyList()
        val keep = BooleanArray(words.size) { true }
        if (skips.headersFooters) {
            val lines = words.indices.groupBy { words[it].lineIndex }.values.sortedBy { idx -> idx.minOf { words[it].topRatio } }
            for (line in listOfNotNull(lines.firstOrNull(), lines.lastOrNull()).distinct()) {
                val text = line.joinToString(" ") { words[it].text }
                val top = line.minOf { words[it].topRatio }; val bottom = line.maxOf { words[it].bottomRatio }
                val atEdge = top < 0.09f || bottom > 0.91f
                val key = norm(text).replace(Regex("\\d+"), "#")
                val numberOnly = Regex("^[\\divxlcIVXLC#\\s–-]{1,12}$").matches(key)
                val seen = (edgeLines[key] ?: 0) + 1; edgeLines[key] = seen
                if (atEdge && text.length < 90 && (numberOnly || seen >= 2)) line.forEach { keep[it] = false }
            }
        }
        val idx = words.indices.filter { keep[it] }
        val toks = idx.map { words[it].text }
        return sentenceRanges(toks).map { rg -> RaSentence(rg.joinToString(" ") { toks[it] }, "", page = unit + 1, w0 = idx[rg.first], w1 = idx[rg.last]) }
    }
    override fun close() { runCatching { r.close() } }
}

class EpubRaSource(doc: DocumentEntity, skips: ReadAloudSkips) : RaSource(skips) {
    private val book = EpubParser.parseEpub(File(doc.filePath), "readaloud-${doc.id}")
    override val unitCount = book.chapters.size
    fun hrefOf(unit: Int) = book.chapters.getOrNull(unit)?.fileKey.orEmpty()
    fun unitOfHref(href: String): Int { val n = href.substringAfterLast('/'); return book.chapters.indexOfFirst { it.fileKey.substringAfterLast('/') == n } }
    override fun raw(unit: Int): List<RaSentence> {
        val ch = book.chapters.getOrNull(unit) ?: return emptyList()
        val out = ArrayList<RaSentence>(); var inRefs = false
        ch.paragraphs.forEach { p ->
            if (p.isBlank() || inRefs) return@forEach
            if (skips.references && REFS.matches(p.trim())) { inRefs = true; return@forEach }
            val m = Regex("\\S+").findAll(p).toList()
            for (rg in sentenceRanges(m.map { it.value })) out += RaSentence(p.substring(m[rg.first].range.first, m[rg.last].range.last + 1), "", href = ch.fileKey, chapter = unit)
        }
        return out
    }
}

/**
 * Reads the book aloud with a Piper voice: sentences are spoken into short WAV pieces a little ahead of the
 * listening, and played by the same player as audiobooks (so it goes on with the screen off and answers
 * headphones, Bluetooth and the lock screen). The sentence being read is published for the reader to underline.
 */
object ReadAloud {
    data class State(
        val docId: Long = 0, val playing: Boolean = false, val preparing: String = "", val error: String = "",
        val current: RaSentence? = null, val speed: Float = 1f, val voice: String = "", val needVoice: String = "",
        val sleepAt: Long = 0, val ended: Boolean = false
    )
    val state = MutableStateFlow(State())
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    // Settings: the voice per language, speed, and the skips.
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("readaloud", Context.MODE_PRIVATE)
    fun skips(ctx: Context) = prefs(ctx).let { p -> ReadAloudSkips(p.getBoolean("citations", true), p.getBoolean("notes", true), p.getBoolean("edges", true), p.getBoolean("urls", true), p.getBoolean("refs", true), p.getBoolean("captions", false)) }
    fun setSkips(ctx: Context, s: ReadAloudSkips) = prefs(ctx).edit().putBoolean("citations", s.citations).putBoolean("notes", s.footnoteNumbers).putBoolean("edges", s.headersFooters).putBoolean("urls", s.urls).putBoolean("refs", s.references).putBoolean("captions", s.captions).apply()
    fun voiceFor(ctx: Context, lang: String): String = prefs(ctx).getString("voice_$lang", null) ?: PiperVoices.catalog.first { it.lang == lang }.id
    fun setVoice(ctx: Context, lang: String, id: String) = prefs(ctx).edit().putString("voice_$lang", id).apply()
    fun langFor(ctx: Context, docId: Long): String? = prefs(ctx).getString("lang_$docId", null)?.takeIf { it.isNotBlank() }
    fun setLang(ctx: Context, docId: Long, lang: String) = prefs(ctx).edit().putString("lang_$docId", lang).apply()
    fun savedSpeed(ctx: Context) = prefs(ctx).getFloat("speed", 1f)

    // The voice engine (one at a time, kept while the same voice is used).
    private var tts: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var ttsVoice = ""
    fun dropVoice(id: String) { if (ttsVoice == id) synchronized(this) { runCatching { tts?.release() }; tts = null; ttsVoice = "" } }
    private fun engine(ctx: Context, id: String): com.k2fsa.sherpa.onnx.OfflineTts = synchronized(this) {
        if (ttsVoice == id) tts?.let { return it }
        runCatching { tts?.release() }; tts = null
        val d = PiperVoices.dir(ctx, id)
        val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(model = PiperVoices.model(ctx, id)!!.absolutePath, tokens = File(d, "tokens.txt").absolutePath, dataDir = File(d, "espeak-ng-data").absolutePath),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4), debug = false, provider = "cpu"),
        )
        com.k2fsa.sherpa.onnx.OfflineTts(null, cfg).also { tts = it; ttsVoice = id }
    }

    // The run: sentences in pieces, generated ahead by one worker; generation numbers stop an old run's worker.
    private class Piece(val file: File, val first: Int, val offsets: LongArray)
    private var gen = 0
    private var source: RaSource? = null
    private val sentences = ArrayList<RaSentence>()
    private val pieces = ArrayList<Piece>()
    private var worker: Thread? = null
    private var docTitle = ""

    private fun cacheDir(ctx: Context) = File(ctx.applicationContext.cacheDir, "readaloud").apply { mkdirs() }

    /** Where to begin: a PDF page (1-based), an EPUB resource and how far into it, and/or a passage of text. */
    data class Start(val pdfPage: Int = 0, val href: String = "", val progression: Double = 0.0, val text: String = "")

    /** Starts reading [doc] at [from]. The language is the book's (or the one chosen for it); its voice must be downloaded. */
    fun start(ctx: Context, doc: DocumentEntity, from: Start) {
        app = ctx.applicationContext
        stopRun()
        AudiobookPlayer.detach()
        val myGen = ++gen
        state.value = State(docId = doc.id, preparing = "Getting the text ready…", speed = savedSpeed(ctx))
        docTitle = doc.title
        worker = Thread({ runWorker(ctx.applicationContext, doc, from, myGen) }, "read-aloud").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    private fun runWorker(ctx: Context, doc: DocumentEntity, from: Start, myGen: Int) {
        try {
            val isPdf = doc.filePath.endsWith(".pdf", true)
            val src = if (isPdf) PdfRaSource(ctx, doc, skips(ctx)) else EpubRaSource(doc, skips(ctx))
            source = src
            // Where to begin.
            var unit = when {
                isPdf && from.pdfPage > 0 -> from.pdfPage - 1
                src is EpubRaSource && from.href.isNotBlank() -> src.unitOfHref(from.href).coerceAtLeast(0)
                else -> 0
            }
            var first = src.sentences(unit)
            var startAt = 0
            if (from.text.isNotBlank()) {
                val q = RaSource.norm(from.text).take(40)
                fun findIn(list: List<RaSentence>) = list.indexOfFirst { val n = RaSource.norm(it.text); q.isNotEmpty() && (n.contains(q.take(30)) || q.contains(n.take(30))) }
                var i = findIn(first)
                if (i < 0 && !isPdf) for (u in 0 until src.unitCount) { val l = src.sentences(u); val k = findIn(l); if (k >= 0) { unit = u; first = l; i = k; break } }
                if (i >= 0) startAt = i
            } else if (!isPdf && from.progression > 0) startAt = (from.progression.coerceIn(0.0, 1.0) * (first.size - 1).coerceAtLeast(0)).toInt()
            // The voice: by the language chosen for the book, else what its text looks like.
            val sample = first.take(20).joinToString(" ") { it.text }
            val lang = langFor(ctx, doc.id) ?: (if (BritishMaleOfflineTtsManager.detectLanguageFromText(sample)?.startsWith("nb") == true || BritishMaleOfflineTtsManager.detectLanguageFromText(sample)?.startsWith("no") == true) "no" else "en")
            val voice = voiceFor(ctx, lang)
            if (!PiperVoices.installed(ctx, voice)) { post(myGen) { state.value = state.value.copy(preparing = "", needVoice = voice) }; return }
            post(myGen) { state.value = state.value.copy(voice = voice, preparing = "Loading the voice…") }
            val tts = engine(ctx, voice)
            synchronized(sentences) { sentences.clear(); pieces.clear() }
            cacheDir(ctx).listFiles()?.forEach { it.delete() }
            var queue = ArrayDeque(first.drop(startAt)); var nextUnit = unit + 1
            var pieceNo = 0
            while (gen == myGen) {
                // Stay three pieces ahead of what is playing.
                val playingPiece = currentPieceIndex()
                if (pieces.size - playingPiece > 3) { Thread.sleep(250); continue }
                while (queue.size < 12 && nextUnit < src.unitCount && gen == myGen) { queue.addAll(src.sentences(nextUnit)); nextUnit++ }
                if (queue.isEmpty()) { post(myGen) { state.value = state.value.copy(preparing = "") }; break }
                // A piece: sentences up to about 25 seconds of speech (≈ 400 characters).
                val batch = ArrayList<RaSentence>(); var chars = 0
                while (queue.isNotEmpty() && (batch.isEmpty() || chars + queue.first().speak.length < 400)) { val s = queue.removeFirst(); batch += s; chars += s.speak.length }
                val pcm = java.io.ByteArrayOutputStream(); val offsets = LongArray(batch.size); var rate = 22050
                batch.forEachIndexed { k, s ->
                    if (gen != myGen) return
                    offsets[k] = pcm.size() / 2L * 1000 / rate
                    val audio = tts.generate(s.speak, 0, 1.0f)
                    rate = audio.sampleRate
                    for (v in audio.samples) { val x = (v.coerceIn(-1f, 1f) * 32767).toInt(); pcm.write(x and 0xff); pcm.write((x shr 8) and 0xff) }
                    repeat(rate * 28 / 100) { pcm.write(0); pcm.write(0) }   // a short breath between sentences
                }
                if (gen != myGen) return
                val f = File(cacheDir(ctx), "p${myGen}_${pieceNo++}.wav"); writeWav(f, pcm.toByteArray(), rate)
                val firstIndex: Int
                synchronized(sentences) { firstIndex = sentences.size; sentences.addAll(batch); pieces += Piece(f, firstIndex, offsets) }
                post(myGen) { addPiece(ctx, f, pieces.size == 1) }
            }
        } catch (t: Throwable) {
            android.util.Log.w("ReadAloud", "failed", t)
            post(myGen) { state.value = state.value.copy(preparing = "", error = t.message ?: "Read aloud failed", playing = false) }
        }
    }

    private fun post(g: Int, f: () -> Unit) = main.post { if (g == gen) f() }

    private fun writeWav(f: File, data: ByteArray, rate: Int) {
        RandomAccessFile(f, "rw").use { w ->
            fun i32(v: Int) { w.write(v and 0xff); w.write(v shr 8 and 0xff); w.write(v shr 16 and 0xff); w.write(v shr 24 and 0xff) }
            fun i16(v: Int) { w.write(v and 0xff); w.write(v shr 8 and 0xff) }
            w.setLength(0); w.writeBytes("RIFF"); i32(36 + data.size); w.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            w.writeBytes("data"); i32(data.size); w.write(data)
        }
    }

    private var listening = false
    private fun addPiece(ctx: Context, f: File, first: Boolean) {
        val p = AudiobookPlayer.player(ctx)
        if (!listening) { listening = true
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (state.value.docId != 0L && isRunning()) tick() }
                override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED && isRunning() && worker?.isAlive != true) state.value = state.value.copy(playing = false, ended = true) }
            })
        }
        p.addMediaItem(MediaItem.Builder().setUri(android.net.Uri.fromFile(f)).setMediaId("ra/${f.name}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(docTitle).setArtist("Read aloud").build()).build())
        if (first) {
            p.setPlaybackSpeed(state.value.speed)
            p.prepare()
            runCatching { ctx.startService(Intent(ctx, AudiobookService::class.java)) }
            p.play()
            state.value = state.value.copy(preparing = "")
            startTicker()
        }
    }

    /** True while the shared player holds read-aloud pieces (not an audiobook). */
    fun isRunning() = pieces.isNotEmpty() && (AudiobookPlayer.playerOrNull()?.currentMediaItem?.mediaId?.startsWith("ra/") == true)

    private fun currentPieceIndex(): Int {
        var r = 0
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { r = AudiobookPlayer.playerOrNull()?.currentMediaItemIndex ?: 0; latch.countDown() }
        latch.await(500, TimeUnit.MILLISECONDS)
        return r
    }

    private var ticking = false
    private fun startTicker() {
        if (ticking) return; ticking = true
        main.post(object : Runnable { override fun run() {
            if (!isRunning()) { ticking = false; return }
            tick()
            val s = state.value
            if (s.playing && s.sleepAt > 0 && System.currentTimeMillis() >= s.sleepAt) { pause(); state.value = state.value.copy(sleepAt = 0) }
            main.postDelayed(this, if (s.playing) 300 else 1500)
        } })
    }

    private fun currentIndex(): Int {
        val p = AudiobookPlayer.playerOrNull() ?: return -1
        val piece = synchronized(sentences) { pieces.getOrNull(p.currentMediaItemIndex) } ?: return -1
        val ms = p.currentPosition; var k = 0
        while (k + 1 < piece.offsets.size && piece.offsets[k + 1] <= ms) k++
        return piece.first + k
    }

    private fun tick() {
        val p = AudiobookPlayer.playerOrNull() ?: return
        val i = currentIndex()
        val cur = synchronized(sentences) { sentences.getOrNull(i) }
        state.value = state.value.copy(playing = p.isPlaying, current = cur ?: state.value.current, speed = p.playbackParameters.speed)
    }

    fun play(ctx: Context) {
        val p = AudiobookPlayer.playerOrNull() ?: return
        if (!isRunning()) return
        runCatching { ctx.startService(Intent(ctx.applicationContext, AudiobookService::class.java)) }
        p.play(); tick()
    }
    fun pause() { if (isRunning()) AudiobookPlayer.playerOrNull()?.pause(); tick() }
    /** Next or previous sentence. */
    fun step(dir: Int) {
        val p = AudiobookPlayer.playerOrNull() ?: return
        val i = currentIndex() + dir
        val (pi, piece) = synchronized(sentences) { pieces.withIndex().lastOrNull { it.value.first <= i } }?.let { it.index to it.value } ?: return
        val k = (i - piece.first).coerceIn(0, piece.offsets.size - 1)
        p.seekTo(pi, piece.offsets[k]); tick()
    }
    fun setSpeed(ctx: Context, s: Float) { AudiobookPlayer.playerOrNull()?.setPlaybackSpeed(s); prefs(ctx).edit().putFloat("speed", s).apply(); tick() }
    fun sleep(minutes: Int) { state.value = state.value.copy(sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0) }

    private fun stopRun() {
        gen++
        if (isRunning()) AudiobookPlayer.playerOrNull()?.let { it.stop(); it.clearMediaItems() }
        synchronized(sentences) { sentences.clear(); pieces.clear() }
        source?.close(); source = null
    }

    /** Stops reading and forgets the run. */
    fun stop() { stopRun(); state.value = State() }
}
