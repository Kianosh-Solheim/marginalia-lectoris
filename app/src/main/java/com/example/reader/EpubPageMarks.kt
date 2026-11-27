package com.example.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/**
 * An EPUB's printed-page markers, each with the words it is followed by (where the printed page begins).
 * From the EPUB 3 nav page-list, else the NCX pageList, else the pagebreak markers in the text.
 * Pure JVM (Jsoup), so it can be tested.
 */
object EpubPageMarks {
    /** [label] as printed ("12"), [file] its path in the EPUB, [text] the first words of the page. */
    data class Mark(val label: String, val file: String, val id: String, val text: String)

    private val BLOCKS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "td", "th", "dd", "dt", "figcaption", "br", "aside", "header", "footer")

    /** "Side 12", "Page 12", "p. 12", "s. 12" → "12". */
    fun cleanLabel(s: String): String = s.trim().replace(Regex("""^(?:side|page|pg\.?|s\.|p\.)\s*""", RegexOption.IGNORE_CASE), "").trim()

    private fun resolve(base: String, href: String): String {
        val dir = base.substringBeforeLast('/', "")
        val parts = (if (dir.isEmpty()) emptyList() else dir.split('/')).toMutableList()
        for (p in java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8").split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    fun read(epub: File, words: Int = 12): List<Mark> = ZipFile(epub).use { z ->
        fun text(path: String): String? = z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
        val container = Jsoup.parse(text("META-INF/container.xml") ?: return emptyList(), "", Parser.xmlParser())
        val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: return emptyList()
        val opf = Jsoup.parse(text(opfPath) ?: return emptyList(), "", Parser.xmlParser())
        val items = opf.select("manifest > item").associate { it.attr("id") to it }
        val spine = opf.select("spine > itemref").mapNotNull { items[it.attr("idref")]?.attr("href")?.let { h -> resolve(opfPath, h) } }

        // (label, file, id) in book order
        var targets = emptyList<Triple<String, String, String>>()
        items.values.firstOrNull { it.attr("properties").split(' ').contains("nav") }?.let { navItem ->
            val navPath = resolve(opfPath, navItem.attr("href"))
            val nav = Jsoup.parse(text(navPath) ?: "", "", Parser.xmlParser())
            val list = nav.select("nav").firstOrNull { it.attr("epub:type").split(' ').contains("page-list") || it.attr("role") == "doc-pagelist" }
            if (list != null) targets = list.select("a[href]").map { Triple(cleanLabel(it.text()), resolve(navPath, it.attr("href")), it.attr("href").substringAfter('#', "")) }
        }
        if (targets.isEmpty()) {
            val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
            val ncxItem = items[ncxId] ?: items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
            if (ncxItem != null) {
                val ncxPath = resolve(opfPath, ncxItem.attr("href"))
                val ncx = Jsoup.parse(text(ncxPath) ?: "", "", Parser.xmlParser())
                targets = ncx.select("pageList pageTarget").mapNotNull { pt ->
                    val src = pt.selectFirst("content")?.attr("src") ?: return@mapNotNull null
                    Triple(cleanLabel(pt.selectFirst("navLabel")?.text() ?: pt.attr("value")), resolve(ncxPath, src), src.substringAfter('#', ""))
                }
            }
        }
        val docs = HashMap<String, Document?>()
        fun doc(path: String) = docs.getOrPut(path) { text(path)?.let { Jsoup.parse(it, "", Parser.xmlParser()) } }
        fun isMarker(e: Element) = e.attr("epub:type").split(' ').contains("pagebreak") || e.attr("role") == "doc-pagebreak"
        if (targets.isEmpty()) {
            targets = spine.flatMap { path ->
                doc(path)?.allElements?.filter { isMarker(it) && it.id().isNotEmpty() }?.map { e ->
                    Triple(cleanLabel(e.attr("title").ifBlank { e.attr("aria-label") }.ifBlank { e.text() }), path, e.id())
                } ?: emptyList()
            }
        }

        /** The first [words] words after [marker] in its document (not its own label, nor other markers'). */
        fun after(d: Document, marker: Element): String {
            val out = StringBuilder(); var count = 0; var started = false
            fun walk(n: Node): Boolean {   // true: enough
                if (n === marker) { started = true; return false }
                if (n is Element && (n.tagName() == "script" || n.tagName() == "style" || (started && isMarker(n)))) return false
                if (n is TextNode) {
                    if (!started) return false
                    val t = n.wholeText
                    out.append(t)
                    count += t.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                    return count >= words
                }
                // the words stay within one block (a heading, a paragraph) once there are a few, so a reader can find them
                if (n is Element && n.tagName().lowercase() in BLOCKS && started && out.isNotEmpty()) {
                    if (count >= 3) return true
                    if (!out.endsWith(" ")) out.append(' ')
                }
                for (c in n.childNodes()) if (walk(c)) return true
                return false
            }
            walk(d.selectFirst("body") ?: d)
            return out.toString().replace(Regex("\\s+"), " ").trim().split(' ').take(words).joinToString(" ")
        }
        targets.mapNotNull { (label, file, id) ->
            if (label.isEmpty()) return@mapNotNull null
            val d = doc(file) ?: return@mapNotNull null
            val el = if (id.isNotEmpty()) d.getElementById(id) else null
            Mark(label, file, id, if (el != null) after(d, el) else "")
        }
    }
}
