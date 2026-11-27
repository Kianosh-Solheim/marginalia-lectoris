package com.example.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/**
 * A DAISY 2.02 talking book (as NLB and other libraries lend them): ncc.html lists the headings and the printed
 * pages, each pointing into a SMIL file that says which audio file it is in and where (clip-begin).
 * Read here into: the audio files in playing order, the headings and the printed pages with their times.
 * Pure JVM (Jsoup), so it can be tested.
 */
object DaisyBook {
    data class Heading(val title: String, val level: Int, val audio: String, val ms: Long)
    /**
     * A printed page: its number, where it begins, the headings it lies under (outermost first), and the heading
     * right after it, if no other page comes between (a chapter's first page is marked just before its heading).
     */
    data class Page(val label: String, val audio: String, val ms: Long, val headings: List<String>, val next: String? = null)
    data class Book(val title: String, val author: String, val root: File, val audio: List<String>, val headings: List<Heading>, val pages: List<Page>)

    private val AUDIO = setOf("mp3", "wav", "mp4", "m4a", "aac", "ogg", "opus")

    /** Is this zip a DAISY book (it has an ncc.html)? */
    fun isDaisyZip(zip: File): Boolean = runCatching {
        ZipFile(zip).use { z -> z.entries().asSequence().any { !it.isDirectory && it.name.substringAfterLast('/').equals("ncc.html", ignoreCase = true) } }
    }.getOrDefault(false)

    /** Unpacks what a DAISY book needs (its NCC, SMIL files and audio) into [into], safely. → the ncc.html */
    fun unzip(zip: File, into: File): File {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO && ext != "smil" && ext != "html" && ext != "htm") continue
                val out = File(into, e.name)
                require(out.canonicalPath.startsWith(base)) { "The zip has a file outside its folder (${e.name})." }
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            }
        }
        return into.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.equals("ncc.html", ignoreCase = true) }
            ?: throw IllegalArgumentException("This zip is not a DAISY book: it has no ncc.html.")
    }

    /** clip-begin values: "npt=82.383s", "npt=1:02:03.5", "82.383s", "1:02.5". */
    fun clipMs(v: String?): Long {
        val s = v.orEmpty().trim().removePrefix("npt=").removeSuffix("s").trim()
        if (s.isEmpty()) return 0
        val parts = s.split(':')
        var sec = 0.0
        for (p in parts) sec = sec * 60 + (p.toDoubleOrNull() ?: return 0)
        return Math.round(sec * 1000)
    }

    fun read(ncc: File): Book {
        val root = ncc.parentFile ?: File(".")
        val doc = Jsoup.parse(ncc, null)
        fun meta(name: String) = doc.select("meta[name]").firstOrNull { it.attr("name").equals(name, ignoreCase = true) }?.attr("content").orEmpty()
        val smils = HashMap<String, Document>()
        fun smil(name: String): Document? = smils.getOrPut(name) {
            val f = File(root, name)
            if (!f.isFile) return null
            Jsoup.parse(f.readText(), "", Parser.xmlParser())
        }
        /** Where an NCC entry begins: the first audio clip of the par (or seq) its SMIL target lies in. */
        fun target(href: String): Pair<String, Long>? {
            val file = href.substringBefore('#'); val id = href.substringAfter('#', "")
            val d = smil(file) ?: return null
            var el: Element? = if (id.isNotEmpty()) d.getElementById(id) else null
            if (el == null) el = d.selectFirst("audio") ?: return null
            var box: Element? = el
            while (box != null && box.tagName() != "par" && box.tagName() != "seq") box = box.parent()
            val audio = (if (el.tagName() == "audio") el else (box ?: el).selectFirst("audio")) ?: return null
            return audio.attr("src") to clipMs(audio.attr("clip-begin").ifBlank { audio.attr("clipBegin") })
        }
        val headings = ArrayList<Heading>(); val pages = ArrayList<Page>()
        val stack = ArrayList<Pair<Int, String>>()   // the headings above where we are: (level, title)
        val smilOrder = LinkedHashSet<String>()
        var lastWasPage = false
        for (e in doc.select("h1, h2, h3, h4, h5, h6, span.page-normal, span.page-front, span.page-special")) {
            val a = e.selectFirst("a[href]") ?: continue
            val href = a.attr("href"); smilOrder += href.substringBefore('#')
            val (audio, ms) = target(href) ?: continue
            val text = a.text().trim()
            if (e.tagName().startsWith("h")) {
                val level = e.tagName().drop(1).toIntOrNull() ?: 1
                while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
                stack += level to text
                headings += Heading(text, level, audio, ms)
                // the page just before this heading begins with it
                if (pages.isNotEmpty() && pages.last().next == null && lastWasPage) pages[pages.size - 1] = pages.last().copy(next = text)
                lastWasPage = false
            } else if (text.isNotEmpty()) { pages += Page(text, audio, ms, stack.map { it.second }); lastWasPage = true }
        }
        // the audio files in playing order: as the SMIL files (in the NCC's order) use them
        val audio = LinkedHashSet<String>()
        for (s in smilOrder) smil(s)?.select("audio[src]")?.forEach { audio += it.attr("src") }
        return Book(meta("dc:title").ifBlank { doc.title() }, meta("dc:creator"), root, audio.toList(), headings, pages)
    }
}
