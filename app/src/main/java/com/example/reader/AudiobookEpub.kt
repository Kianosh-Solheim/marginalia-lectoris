package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A copy of the book's EPUB with the audiobook built in (EPUB 3 Media Overlays): the audio files go inside, each
 * sentence of the sync map gets an id in the text (a <span>, or its paragraph when the sentence crosses other
 * markup), and one SMIL file per chapter says where in the audio each sentence is. The original EPUB is not touched.
 * The copy lies in the book's folder: “<Title> – with audio.epub”.
 */
object AudiobookEpub {
    private val BLOCKS = setOf("p", "li", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th", "dd", "dt", "figcaption", "section", "aside", "pre")

    data class Result(val file: File, val sentences: Int, val placed: Int)

    suspend fun export(ctx: Context, doc: DocumentEntity, progress: (String) -> Unit = {}): Result {
        val src = File(doc.filePath)
        require(src.extension.equals("epub", true)) { "Only an EPUB book can get the audio built in (this one is ${src.extension.uppercase(Locale.ROOT)})." }
        val mapFile = AudiobookSync.map(ctx, doc) ?: throw IllegalArgumentException("Sync the audiobook with the text first.")
        val map = JSONObject(mapFile.readText())
        require(map.optString("kind") == "epub") { "The sync map was made for another file. Sync again." }
        val parts = Audiobooks.files(ctx, doc.id)
        require(parts.isNotEmpty()) { "The audiobook has no files." }
        val sents = map.optJSONArray("sentences") ?: throw IllegalArgumentException("The sync map is empty.")
        val total = parts.sumOf { it.durationMs }

        ZipFile(src).use { zip ->
            // The package document (OPF) and its folder.
            val container = zip.getEntry("META-INF/container.xml") ?: throw IllegalArgumentException("Not a valid EPUB (no container).")
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(zip.getInputStream(container).readBytes().toString(Charsets.UTF_8))?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Not a valid EPUB (no package file).")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = Jsoup.parse(zip.getInputStream(zip.getEntry(opfPath)).readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser())
            opf.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml)
            val manifest = opf.selectFirst("manifest") ?: throw IllegalArgumentException("Not a valid EPUB (no manifest).")
            val items = manifest.select("item")
            fun full(href: String) = if (opfDir.isEmpty()) href else "$opfDir/$href"

            // Sentences by chapter file (its name), in order.
            data class S(val i: Int, val t: Long, val x: String, var id: String = "")
            val byFile = LinkedHashMap<String, MutableList<S>>()
            for (k in 0 until sents.length()) {
                val o = sents.getJSONObject(k); val l = o.optJSONObject("l") ?: continue
                val name = l.optString("h").substringAfterLast('/'); if (name.isBlank()) continue
                byFile.getOrPut(name) { ArrayList() } += S(k, o.optLong("t"), o.optString("x"))
            }
            val nextT = LongArray(sents.length()) { k -> if (k + 1 < sents.length()) sents.getJSONObject(k + 1).optLong("t") else total }

            // Audio inside the book: audio/01.mp3 …
            val audioNames = parts.mapIndexed { i, p ->
                val ext = p.file.extension.lowercase(Locale.ROOT).let { if (it == "m4b") "m4a" else it }
                "audio/%02d.%s".format(i + 1, ext)
            }
            fun clip(ms: Long): Pair<Int, Long> { val i = parts.indexOfLast { it.startMs <= ms }.coerceAtLeast(0); return i to (ms - parts[i].startMs).coerceAtLeast(0) }
            fun secs(ms: Long) = String.format(Locale.US, "%.3fs", ms / 1000.0)
            fun clock(ms: Long) = String.format(Locale.US, "%d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

            val changed = HashMap<String, ByteArray>()   // zip path → new bytes
            val smils = ArrayList<Triple<String, String, Long>>()  // (zip path, manifest id, duration)
            var placed = 0; var counter = 0
            for ((name, list) in byFile) {
                val item = items.firstOrNull { it.attr("href").substringBefore('#').substringAfterLast('/') == name } ?: continue
                val path = full(java.net.URLDecoder.decode(item.attr("href").substringBefore('#'), "UTF-8"))
                val entry = zip.getEntry(path) ?: continue
                progress("Marking the sentences in $name…")
                val html = Jsoup.parse(zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser())
                html.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml)
                val body = html.selectFirst("body") ?: continue
                // The text of the chapter, with where each piece comes from.
                val nodes = ArrayList<TextNode>(); val starts = ArrayList<Int>(); val all = StringBuilder()
                NodeTraversor.traverse(NodeVisitor { node, _ -> if (node is TextNode) { starts += all.length; nodes += node; all.append(node.wholeText) } }, body)
                val (norm, back) = normMap(all.toString())
                fun nodeAt(pos: Int): Int { var lo = 0; var hi = starts.size - 1; while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (starts[mid] <= pos) lo = mid else hi = mid - 1 }; return lo }
                data class Place(val s: S, val a: Int, val oa: Int, val b: Int, val ob: Int)
                val places = ArrayList<Place>(); var cursor = 0
                for (s in list.sortedBy { it.i }) {
                    val q = normMap(s.x).first.trim(); if (q.length < 3) continue
                    val at = norm.indexOf(q.take(40), cursor).takeIf { it >= 0 } ?: continue
                    var end = (at + q.length - 1).coerceAtMost(norm.length - 1)
                    if (q.length > 40) norm.indexOf(q.takeLast(20), at).takeIf { it in at..(at + q.length + 40) }?.let { end = it + 19 }
                    cursor = end + 1
                    val sa = back[at]; val sb = back[end] + 1
                    val a = nodeAt(sa); val b = nodeAt(sb - 1)
                    places += Place(s, a, sa - starts[a], b, sb - starts[b])
                }
                // Applied from the end, so the offsets found above stay right.
                for (p in places.asReversed()) {
                    val id = "mls${++counter}"
                    if (p.a == p.b) {
                        val n = nodes[p.a]
                        if (p.ob < n.wholeText.length) n.splitText(p.ob)
                        val mid = if (p.oa > 0) n.splitText(p.oa) else n
                        mid.wrap("<span id=\"$id\"></span>")
                        p.s.id = id
                    } else {
                        // Crossing other markup: the sentence's paragraph stands for it.
                        val parent = nodes[p.a].parent() as? Element ?: continue
                        val block = (listOf(parent) + parent.parents()).firstOrNull { it.normalName() in BLOCKS } ?: continue
                        if (block.id().isBlank()) block.attr("id", id)
                        p.s.id = block.id()
                    }
                    placed++
                }
                html.selectFirst("head")?.appendElement("style")?.text(".-epub-media-overlay-active { background-color: #fff3a0; }")
                changed[path] = html.outerHtml().toByteArray(Charsets.UTF_8)

                // The chapter's SMIL: one <par> per sentence, in reading order.
                val smilPath = (path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) + name.substringBeforeLast('.') + ".smil"
                val textRel = name
                val sb2 = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<smil xmlns=\"http://www.w3.org/ns/SMIL\" xmlns:epub=\"http://www.idpf.org/2007/ops\" version=\"3.0\">\n<body>\n<seq id=\"seq1\" epub:textref=\"$textRel\">\n")
                var dur = 0L; var par = 0
                for (s in list.filter { it.id.isNotBlank() }.sortedBy { it.t }) {
                    val (fi, from) = clip(s.t)
                    val to = minOf(nextT[s.i], parts[fi].startMs + parts[fi].durationMs) - parts[fi].startMs
                    if (to <= from) continue
                    dur += to - from
                    sb2.append("<par id=\"par${++par}\"><text src=\"").append(textRel).append('#').append(s.id).append("\"/><audio src=\"")
                        .append(relPath(smilPath.substringBeforeLast('/', ""), full(audioNames[fi]))).append("\" clipBegin=\"").append(secs(from)).append("\" clipEnd=\"").append(secs(to)).append("\"/></par>\n")
                }
                sb2.append("</seq>\n</body>\n</smil>\n")
                if (par == 0) continue
                val smilId = "mo-" + name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")
                changed[smilPath] = sb2.toString().toByteArray(Charsets.UTF_8)
                smils += Triple(smilPath, smilId, dur)
                item.attr("media-overlay", smilId)
                manifest.appendElement("item").attr("id", smilId).attr("href", relPath(opfDir, smilPath)).attr("media-type", "application/smil+xml")
            }
            require(smils.isNotEmpty()) { "None of the sentences could be found in the EPUB's text. Sync again with this EPUB as the main file." }

            // The package: the audio, the SMIL files and their durations.
            audioNames.forEachIndexed { i, a ->
                val type = when (a.substringAfterLast('.')) { "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "application/octet-stream" }
                manifest.appendElement("item").attr("id", "mo-audio-${i + 1}").attr("href", a).attr("media-type", type)
            }
            val meta = opf.selectFirst("metadata")
            if (meta != null) {
                meta.select("meta[property^=media:]").remove()
                smils.forEach { (_, id, d) -> meta.appendElement("meta").attr("property", "media:duration").attr("refines", "#$id").text(clock(d)) }
                meta.appendElement("meta").attr("property", "media:duration").text(clock(smils.sumOf { it.third }))
                meta.appendElement("meta").attr("property", "media:active-class").text("-epub-media-overlay-active")
            }
            changed[opfPath] = opf.outerHtml().toByteArray(Charsets.UTF_8)

            // The new EPUB: mimetype first and stored, then everything else, then the audio (not compressed again).
            val out = File(ItemFiles.folderOf(ctx, doc), MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "Book" }.take(120) + " – with audio.epub")
            val tmp = File(out.parentFile, ".${out.name}.part")
            progress("Writing the EPUB…")
            ZipOutputStream(tmp.outputStream().buffered(1 shl 16)).use { zo ->
                val mt = "application/epub+zip".toByteArray()
                zo.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong(); crc = CRC32().apply { update(mt) }.value })
                zo.write(mt); zo.closeEntry()
                zo.setLevel(Deflater.DEFAULT_COMPRESSION)
                val seen = HashSet<String>()
                for (e in zip.entries()) {
                    if (e.name == "mimetype" || e.isDirectory || !seen.add(e.name)) continue
                    zo.putNextEntry(ZipEntry(e.name))
                    val repl = changed.remove(e.name)
                    if (repl != null) zo.write(repl) else zip.getInputStream(e).use { it.copyTo(zo, 1 shl 16) }
                    zo.closeEntry()
                }
                for ((name, bytes) in changed) { if (!seen.add(name)) continue; zo.putNextEntry(ZipEntry(name)); zo.write(bytes); zo.closeEntry() }
                zo.setLevel(Deflater.NO_COMPRESSION)
                parts.forEachIndexed { i, p ->
                    progress("Adding the audio ${i + 1} of ${parts.size}…")
                    zo.putNextEntry(ZipEntry(full(audioNames[i]))); p.file.inputStream().use { it.copyTo(zo, 1 shl 16) }; zo.closeEntry()
                }
            }
            if (out.exists()) out.delete()
            check(tmp.renameTo(out)) { "Could not save the EPUB." }
            return Result(out, sents.length(), placed)
        }
    }

    /** Text folded for matching (lowercase, no accents, letters and digits, single spaces), with each folded char's place in [s]. */
    fun normMap(s: String): Pair<String, IntArray> {
        val out = StringBuilder(s.length); val map = IntArray(s.length * 2 + 1); var n = 0
        for (i in s.indices) {
            val c = s[i]
            val f = if (Character.isLetterOrDigit(c)) java.text.Normalizer.normalize(c.lowercaseChar().toString(), java.text.Normalizer.Form.NFKD).filter { Character.isLetterOrDigit(it) } else " "
            for (ch in f) { if (ch == ' ' && (out.isEmpty() || out[out.length - 1] == ' ')) continue; if (n < map.size) map[n] = i; n++; out.append(ch) }
        }
        return out.toString() to map.copyOf(maxOf(n, 1))
    }

    /** The path of [target] (a zip path) relative to the folder [fromDir] (a zip path, "" for the root). */
    fun relPath(fromDir: String, target: String): String {
        val a = fromDir.split('/').filter { it.isNotEmpty() }; val b = target.split('/').filter { it.isNotEmpty() }
        var k = 0; while (k < a.size && k < b.size - 1 && a[k] == b[k]) k++
        return (List(a.size - k) { ".." } + b.drop(k)).joinToString("/")
    }
}
