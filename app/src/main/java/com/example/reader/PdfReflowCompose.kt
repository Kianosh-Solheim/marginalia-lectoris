package com.example.reader

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Reflowable PDF: page model → styled EPUB 3 (XHTML + CSS + nav with printed page list).
// Pure Kotlin (no Android / PDFBox) so the layout logic can be tested on its own.
// Coordinates are page ratios 0..1, top-left origin.
// ---------------------------------------------------------------------------------------------

data class RWord(
    val text: String,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val size: Float,
    val bold: Boolean = false,
    val italic: Boolean = false
)

data class RImage(val l: Float, val t: Float, val r: Float, val b: Float, val file: String)

/** One PDF page: its lines in reading order (columns already resolved), figures and the PDF's own page label. */
data class RPage(
    val pdf: Int,
    val lines: List<List<RWord>>,
    val gutters: List<Float> = emptyList(),
    val images: List<RImage> = emptyList(),
    val catalogLabel: String = "",
    val scannedImage: String = ""      // whole-page image for pages without a text layer
)

data class ROutline(val title: String, val page: Int, val level: Int)

data class ReflowPageEntry(val pdf: Int, val label: String, val href: String)

data class ReflowOutput(
    val files: List<Pair<String, String>>,     // file name → XHTML
    val nav: String,
    val css: String,
    val pages: List<ReflowPageEntry>,
    val pageText: Map<Int, String>,            // normalised text per PDF page (for locating quotes)
    val images: List<String>
)

object ReflowComposer {

    private val romanRx = Regex("^(?=[ivxlcdm]+$)m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$", RegexOption.IGNORE_CASE)
    private val arabicRx = Regex("^[1-9][0-9]{0,3}$")

    private fun isPageToken(s: String): Boolean {
        val t = s.trim().trim('(', ')', '[', ']', '-', '–', '—', '.').trim()
        return arabicRx.matches(t) || (t.length in 1..7 && romanRx.matches(t) && t == t.lowercase(Locale.ROOT))
    }
    private fun cleanToken(s: String) = s.trim().trim('(', ')', '[', ']', '-', '–', '—', '.').trim()

    fun lineText(l: List<RWord>) = l.joinToString(" ") { it.text }
    private fun top(l: List<RWord>) = l.minOf { it.t }
    private fun bottom(l: List<RWord>) = l.maxOf { it.b }
    private fun left(l: List<RWord>) = l.minOf { it.l }
    private fun right(l: List<RWord>) = l.maxOf { it.r }
    private fun lineSize(l: List<RWord>): Float {
        // size weighted by characters, so a small footnote marker doesn't change the line
        var best = 0f; var bestChars = -1
        l.groupBy { (it.size * 2).toInt() }.forEach { (_, ws) ->
            val c = ws.sumOf { it.text.length }
            if (c > bestChars) { bestChars = c; best = ws.first().size }
        }
        return best
    }

    /** The body text size: the size used for most characters. */
    fun bodySize(pages: List<RPage>): Float {
        val counts = HashMap<Int, Int>()
        pages.forEach { p -> p.lines.forEach { l -> l.forEach { w -> counts.merge((w.size * 2).toInt(), w.text.length, Int::plus) } } }
        val best = counts.maxByOrNull { it.value }?.key ?: return 10f
        return best / 2f
    }

    private fun normEdge(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[0-9]+"), "#")
        .replace(Regex("\\b[ivxlcdm]+\\b"), "#").replace(Regex("[^\\p{L}#]+"), " ").trim()

    private fun isEdgeLine(l: List<RWord>) = l.size <= 16 && (bottom(l) <= 0.10f || top(l) >= 0.915f)

    /** Running headers/footers to drop, and the printed page number of every page. */
    data class EdgeAnalysis(val removed: Map<Int, Set<Int>>, val labels: Map<Int, String>)

    fun analyseEdges(pages: List<RPage>, body: Float): EdgeAnalysis {
        val repeat = HashMap<String, MutableSet<Int>>()
        pages.forEach { p -> p.lines.filter { isEdgeLine(it) }.forEach { l ->
            val n = normEdge(lineText(l)); if (n.length >= 3) repeat.getOrPut(n) { HashSet() } += p.pdf
        } }
        val minRepeat = max(3, (pages.size * 0.04f).toInt()).coerceAtMost(6)
        val removed = HashMap<Int, Set<Int>>()
        val cands = HashMap<Int, String>()
        pages.forEach { p ->
            val drop = HashSet<Int>()
            p.lines.forEachIndexed { i, l ->
                if (!isEdgeLine(l)) return@forEachIndexed
                val text = lineText(l).trim()
                val big = lineSize(l) > body * 1.6f
                if (l.size <= 3 && isPageToken(text.replace(" ", "")) && !big) {
                    drop += i; cands.putIfAbsent(p.pdf, cleanToken(text.replace(" ", ""))); return@forEachIndexed
                }
                val n = normEdge(text)
                if (n.length >= 3 && (repeat[n]?.size ?: 0) >= minRepeat) {
                    drop += i
                    val first = cleanToken(l.first().text); val last = cleanToken(l.last().text)
                    when {
                        isPageToken(last) -> cands.putIfAbsent(p.pdf, last)
                        isPageToken(first) -> cands.putIfAbsent(p.pdf, first)
                    }
                }
            }
            removed[p.pdf] = drop
        }
        // Printed page numbers: the PDF's own labels when they are real, else what the pages show,
        // checked against the neighbours (printed − PDF page is constant within a run).
        val catalogUseful = pages.count { it.catalogLabel.isNotBlank() && it.catalogLabel != it.pdf.toString() } >= pages.size * 0.3f
        val arabic = cands.mapNotNull { (p, c) -> c.toIntOrNull()?.let { p to it - p } }.toMap()
        val labels = HashMap<Int, String>()
        pages.forEach { p ->
            if (catalogUseful && p.catalogLabel.isNotBlank()) { labels[p.pdf] = p.catalogLabel; return@forEach }
            val cand = cands[p.pdf].orEmpty()
            if (cand.isNotBlank() && cand.toIntOrNull() == null) { labels[p.pdf] = cand; return@forEach }
            val offs = (-8..8).filter { it != 0 }.mapNotNull { d -> arabic[p.pdf + d] }
            val best = offs.groupingBy { it }.eachCount().maxByOrNull { it.value }
            val expected = best?.let { p.pdf + it.key }?.takeIf { it >= 1 }
            labels[p.pdf] = when {
                expected == null -> cand
                cand.isBlank() -> expected.toString()
                cand.toInt() == expected -> cand
                best.value >= 2 -> expected.toString()
                else -> cand
            }
        }
        return EdgeAnalysis(removed, labels.filterValues { it.isNotBlank() })
    }

    // ----------------------------------------------------------------------------------- XHTML

    fun esc(s: String) = buildString(s.length + 8) {
        s.forEach { c -> when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); else -> if (c.code >= 0x20 || c == '\n') append(c) } }
    }

    /** Words of one line as inline XHTML: italics, bold and superscripts (note markers) kept. */
    private fun inline(l: List<RWord>, lineSz: Float): List<Pair<String, String>> {
        // (style, text) runs
        val base = l.maxOf { it.b }
        val runs = mutableListOf<Pair<String, String>>()
        l.forEach { w ->
            val sup = w.size < lineSz * 0.8f && w.b < base - (base - l.minOf { it.t }) * 0.2f && w.text.length <= 4
            val st = buildString { if (sup) append('s'); if (w.italic) append('i'); if (w.bold) append('b') }
            runs += st to w.text
        }
        return runs
    }

    private class Para(val kind: String, val cls: String = "", val level: Int = 0) {
        val parts = mutableListOf<String>()      // XHTML fragments
        var lastText = ""                         // plain text of the last line (for hyphen/end checks)
        var lastShort = false
        var lineCount = 0
        var lastBottom = 0f
        var lastCol = -2
        var centered = false
    }

    private fun runsToXhtml(runs: List<Pair<String, String>>, joinFirst: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < runs.size) {
            val st = runs[i].first
            val words = mutableListOf<String>()
            var j = i
            while (j < runs.size && runs[j].first == st) { words += runs[j].second; j++ }
            var text = esc(words.joinToString(" "))
            if ('b' in st) text = "<strong>$text</strong>"
            if ('i' in st) text = "<em>$text</em>"
            if ('s' in st) text = "<sup>$text</sup>"
            if (sb.isNotEmpty() || (i == 0 && !joinFirst)) sb.append(if (sb.isEmpty()) "" else " ")
            sb.append(text)
            i = j
        }
        return sb.toString()
    }

    /**
     * Builds the book. [fileStarts] are the PDF pages where a new XHTML file begins (chapters),
     * [lang] the book language.
     */
    fun compose(pages: List<RPage>, outline: List<ROutline>, title: String, author: String, lang: String, fileStarts: List<Int>): ReflowOutput {
        val sorted = pages.sortedBy { it.pdf }
        val body = bodySize(sorted)
        val edges = analyseEdges(sorted, body)
        val starts = (fileStarts + listOfNotNull(sorted.firstOrNull()?.pdf)).filter { s -> sorted.any { it.pdf == s } }.distinct().sorted()
        fun fileFor(p: Int) = "p%04d.xhtml".format(starts.lastOrNull { it <= p } ?: starts.first())

        val files = LinkedHashMap<String, StringBuilder>()
        val entries = mutableListOf<ReflowPageEntry>()
        val pageText = HashMap<Int, String>()
        val images = mutableListOf<String>()
        var headingNo = 0
        val detectedHeadings = mutableListOf<Triple<String, String, Int>>() // href#id, text, level

        var currentPage = 0
        var out: StringBuilder? = null
        var cur: Para? = null
        var pendingMarker: String? = null   // page marker waiting for the next text
        var continueNext = false            // next body line continues an interrupted paragraph

        fun flush() {
            val p = cur ?: return
            val o = out ?: return
            if (p.parts.isNotEmpty()) {
                val content = p.parts.joinToString("")
                when (p.kind) {
                    "h" -> {
                        val id = "h${++headingNo}"
                        val cls = if (p.centered) " class=\"center\"" else ""
                        o.append("<h${p.level} id=\"$id\"$cls>").append(content).append("</h${p.level}>\n")
                        val plain = content.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
                        detectedHeadings += Triple("${fileFor(currentPage)}#$id", plain, p.level)
                    }
                    "q" -> o.append("<blockquote><p>").append(content).append("</p></blockquote>\n")
                    else -> o.append("<p").append(if (p.cls.isNotBlank()) " class=\"${p.cls}\"" else "").append(">").append(content).append("</p>\n")
                }
            }
            cur = null
        }

        fun marker(): String? { val m = pendingMarker; pendingMarker = null; return m }

        for (page in sorted) {
            currentPage = page.pdf
            val fname = fileFor(page.pdf)
            if (!files.containsKey(fname)) {
                flush(); continueNext = false
                out = StringBuilder().also { files[fname] = it }
            }
            val label = edges.labels[page.pdf].orEmpty()
            val markerId = "pdfpage-${page.pdf}"
            pendingMarker = "<span epub:type=\"pagebreak\" role=\"doc-pagebreak\" id=\"$markerId\"" +
                (if (label.isNotBlank()) " title=\"${esc(label)}\"" else "") + "></span>"
            entries += ReflowPageEntry(page.pdf, label, fname)

            if (page.scannedImage.isNotBlank()) {
                flush(); continueNext = false
                out!!.append(marker()).append("\n<figure class=\"page\"><img src=\"images/${page.scannedImage}\" alt=\"\"/></figure>\n")
                images += page.scannedImage
                pageText[page.pdf] = ""
                continue
            }

            val drop = edges.removed[page.pdf].orEmpty()
            val figures = page.images.filter { (it.r - it.l) * (it.b - it.t) >= 0.02f }
            val lines = page.lines.filterIndexed { i, l -> i !in drop && l.isNotEmpty() }
                .filter { l ->
                    val cx = (left(l) + right(l)) / 2; val cy = (top(l) + bottom(l)) / 2
                    figures.none { f -> cx in f.l..f.r && cy in f.t..f.b }
                }
            var pageStart = true
            pageText[page.pdf] = lines.joinToString(" ") { lineText(it) }.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

            // Column geometry
            fun colOf(l: List<RWord>): Int {
                val a = left(l); val b = right(l)
                if (page.gutters.any { g -> a < g - 0.01f && b > g + 0.01f }) return -1
                val c = (a + b) / 2; return page.gutters.count { c > it }
            }
            fun pct(v: List<Float>, q: Float): Float { if (v.isEmpty()) return 0f; val s = v.sorted(); return s[((s.size - 1) * q).toInt()] }
            val byCol = lines.groupBy { colOf(it) }
            val allL = pct(lines.map { left(it) }, 0.05f); val allR = pct(lines.map { right(it) }, 0.95f)
            val colBounds = byCol.mapValues { (c, ls) -> if (c < 0 || ls.size < 3) allL to allR else pct(ls.map { left(it) }, 0.15f) to pct(ls.map { right(it) }, 0.85f) }
            val gaps = lines.zipWithNext().filter { (a, b) -> colOf(a) == colOf(b) && top(b) > bottom(a) - 0.002f }.map { (a, b) -> top(b) - bottom(a) }
            val medGap = pct(gaps, 0.5f).coerceIn(0.0015f, 0.03f)
            val heights = lines.map { bottom(it) - top(it) }
            val medH = pct(heights, 0.5f).coerceAtLeast(0.008f)

            // Footnotes: small lines at the end of the page's reading order, low on the page.
            var fnStart = lines.size
            for (i in lines.indices.reversed()) {
                val l = lines[i]
                if (lineSize(l) <= body * 0.9f && top(l) > 0.5f) fnStart = i else break
            }
            if (fnStart < lines.size && lines.size - fnStart > 12) fnStart = lines.size // not notes: a small-type page
            val bodyLines = lines.subList(0, fnStart)
            val noteLines = lines.subList(fnStart, lines.size)

            // Figures go before the first line below their top edge.
            val figAt = figures.sortedBy { it.t }.map { f ->
                val idx = bodyLines.indexOfFirst { l -> top(l) >= f.t - 0.005f && (colOf(l) < 0 || right(l) > f.l && left(l) < f.r || f.r - f.l > 0.6f) }
                (if (idx < 0) bodyLines.size else idx) to f
            }

            fun quoteLike(i: Int): Boolean {
                val l = bodyLines.getOrNull(i) ?: return false
                val (cl, cr) = colBounds[colOf(l)] ?: (allL to allR)
                val w = (cr - cl).coerceAtLeast(0.1f)
                return left(l) - cl >= 0.05f * w && cr - right(l) >= 0.03f * w && lineSize(l) <= body * 1.03f
            }

            bodyLines.forEachIndexed { i, l ->
                figAt.filter { it.first == i }.forEach { (_, f) ->
                    flush()
                    out!!.append(marker() ?: "").append("<figure><img src=\"images/${f.file}\" alt=\"\"/></figure>\n")
                    images += f.file
                    continueNext = true
                }
                val col = colOf(l)
                val (cl, cr) = colBounds[col] ?: (allL to allR)
                val colW = (cr - cl).coerceAtLeast(0.1f)
                val sz = lineSize(l)
                val text = lineText(l)
                val indentL = left(l) - cl
                val shortR = cr - right(l)
                val centered = indentL > 0.04f * colW && shortR > 0.04f * colW && abs(indentL - shortR) < 0.05f * colW
                val allBold = l.all { it.bold }
                val letters = text.count { it.isLetterOrDigit() }
                // Vertical gap to the previous line; neutral across a column or page break.
                val gap = cur?.let { if (!pageStart && it.lastCol == col && top(l) >= it.lastBottom - 0.002f) top(l) - it.lastBottom else 0f } ?: 0f
                pageStart = false

                val level = when {
                    letters < 1 -> 0
                    sz >= body * 1.6f -> 1
                    sz >= body * 1.3f -> 2
                    sz >= body * 1.12f -> 3
                    allBold && l.size <= 12 && (centered || shortR > 0.15f * colW) && !text.trimEnd().endsWith(".") && letters >= 2 -> 4
                    else -> 0
                }
                val runs = inline(l, sz)
                if (level > 0) {
                    val c = cur
                    if (c != null && c.kind == "h" && gap < medGap * 4 + medH) {
                        c.parts += "<br/>" + runsToXhtml(runs, false)
                        c.lastBottom = bottom(l); c.lastCol = col
                        return@forEachIndexed
                    }
                    flush()
                    out!!.append(marker() ?: "")
                    cur = Para("h", level = min(level, 4)).also {
                        it.parts += runsToXhtml(runs, false); it.lastBottom = bottom(l); it.lastCol = col; it.centered = centered
                    }
                    continueNext = false
                    return@forEachIndexed
                }
                val isQuote = quoteLike(i) && (quoteLike(i - 1) || quoteLike(i + 1))
                val kind = when { isQuote -> "q"; centered && l.size <= 14 && !(cur?.kind == "p" && gap < medGap * 1.5f) -> "c"; else -> "p" }
                val c = cur
                val prevEndsSentence = c != null && Regex("[.!?:;\"”’)]$").containsMatchIn(c.lastText.trimEnd())
                val firstIndent = indentL >= 0.012f * 1.0f && indentL < 0.15f * colW && kind == "p"
                val bigGap = gap > medGap * 1.8f + medH * 0.25f
                val startsNew = when {
                    c == null -> true
                    c.kind != kind -> true
                    kind == "c" -> bigGap || !c.centered
                    bigGap -> true
                    firstIndent -> true
                    c.lastShort && prevEndsSentence -> true
                    else -> false
                }
                val m = marker()
                if (startsNew) {
                    val gapPara = bigGap && !firstIndent && kind == "p" && c != null && c.kind == "p"
                    val cls = when {
                        kind == "c" -> "center"
                        continueNext && !firstIndent && !bigGap -> "cont"
                        gapPara -> "gap"
                        firstIndent -> ""
                        else -> "noindent"
                    }
                    flush()
                    cur = Para(if (kind == "q") "q" else "p", cls).also { it.centered = kind == "c" }
                    cur!!.parts += (m ?: "") + runsToXhtml(runs, false)
                } else {
                    val p = cur!!
                    val prev = p.lastText.trimEnd()
                    val hyphen = prev.endsWith("-") && prev.length > 1 && prev[prev.length - 2].isLetter() && text.firstOrNull()?.isLowerCase() == true
                    if (hyphen) {
                        // remove the trailing hyphen from the previous fragment
                        val last = p.parts.removeAt(p.parts.lastIndex)
                        p.parts += last.removeSuffix("-").let { if (it.endsWith("-</em>")) it.removeSuffix("-</em>") + "</em>" else it }
                        p.parts += (m ?: "") + runsToXhtml(runs, true).trimStart()
                    } else {
                        p.parts += (if (kind == "c") "<br/>" else " ") + (m ?: "") + runsToXhtml(runs, false)
                    }
                }
                cur!!.apply {
                    lastText = text; lastShort = shortR > 0.08f * colW; lineCount++; lastBottom = bottom(l); lastCol = col
                }
                continueNext = false
            }
            figAt.filter { it.first >= bodyLines.size }.forEach { (_, f) ->
                flush()
                out!!.append(marker() ?: "").append("<figure><img src=\"images/${f.file}\" alt=\"\"/></figure>\n")
                images += f.file
                continueNext = true
            }
            if (noteLines.isNotEmpty()) {
                val open = cur
                val wasPara = open != null && open.kind == "p"
                flush()
                val o = out!!
                o.append(marker() ?: "")
                o.append("<aside class=\"fn\" epub:type=\"footnote\">")
                var noteBuf = StringBuilder()
                fun endNote() { if (noteBuf.isNotEmpty()) { o.append("<p>").append(noteBuf).append("</p>"); noteBuf = StringBuilder() } }
                noteLines.forEachIndexed { k, l ->
                    val t = lineText(l)
                    val startsNote = k == 0 || Regex("^([0-9]{1,3}|[*†‡§])[.)]?\\s").containsMatchIn(t) || l.first().let { it.text.length <= 3 && it.text.any(Char::isDigit) }
                    if (startsNote) endNote()
                    val prev = noteBuf.toString()
                    if (prev.endsWith("-") && t.firstOrNull()?.isLowerCase() == true) { noteBuf.setLength(noteBuf.length - 1); noteBuf.append(runsToXhtml(inline(l, lineSize(l)), true).trimStart()) }
                    else { if (noteBuf.isNotEmpty()) noteBuf.append(' '); noteBuf.append(runsToXhtml(inline(l, lineSize(l)), false)) }
                }
                endNote()
                o.append("</aside>\n")
                continueNext = wasPara
            }
            // A page with nothing but a marker still needs its anchor.
            marker()?.let { m -> flush(); out!!.append(m).append("\n") }
        }
        flush()

        // ---- XHTML files
        val bodyFiles = files.map { (name, sb) ->
            val firstPage = name.substring(1, 5).toInt()
            val head = outline.lastOrNull { it.page <= firstPage }?.title ?: title
            name to xhtml(head, lang, sb.toString())
        }

        // ---- nav: table of contents (PDF bookmarks, else detected headings) + printed page list
        val tocItems: List<Triple<String, String, Int>> = if (outline.isNotEmpty()) {
            outline.filter { o -> sorted.any { it.pdf == o.page } || sorted.any { it.pdf > o.page } && sorted.any { it.pdf < o.page } }
                .mapNotNull { o ->
                    val p = sorted.firstOrNull { it.pdf >= o.page } ?: return@mapNotNull null
                    Triple("${fileFor(p.pdf)}#pdfpage-${p.pdf}", o.title, o.level)
                }
        } else detectedHeadings.filter { it.third <= 2 }.map { Triple(it.first, it.second, it.third - 1) }
        val nav = navXhtml(title, lang, tocItems.ifEmpty { bodyFiles.map { Triple(it.first, "Pages from ${it.first.substring(1, 5).toInt()}", 0) } }, entries)
        return ReflowOutput(bodyFiles, nav, CSS, entries, pageText, images.distinct())
    }

    private fun xhtml(title: String, lang: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$lang" xml:lang="$lang">
<head><meta charset="utf-8"/><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
$body</body>
</html>
"""

    private fun navXhtml(title: String, lang: String, toc: List<Triple<String, String, Int>>, pages: List<ReflowPageEntry>): String {
        val sb = StringBuilder()
        val minLevel = toc.minOfOrNull { it.third } ?: 0
        var open = false
        var prev = 0
        toc.forEach { (href, text, lvl0) ->
            if (!open) {
                sb.append("<ol>"); open = true; prev = 0
            } else {
                val lvl = (lvl0 - minLevel).coerceIn(0, prev + 1)
                if (lvl > prev) sb.append("<ol>")
                else { sb.append("</li>"); repeat(prev - lvl) { sb.append("</ol></li>") } }
                prev = lvl
            }
            sb.append("<li><a href=\"$href\">").append(esc(text.ifBlank { "—" })).append("</a>")
        }
        if (open) { sb.append("</li>"); repeat(prev) { sb.append("</ol></li>") }; sb.append("</ol>") }
        val pl = StringBuilder("<ol>")
        pages.filter { it.label.isNotBlank() }.forEach { pl.append("<li><a href=\"${it.href}#pdfpage-${it.pdf}\">").append(esc(it.label)).append("</a></li>") }
        pl.append("</ol>")
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$lang" xml:lang="$lang">
<head><meta charset="utf-8"/><title>${esc(title)}</title></head>
<body>
<nav epub:type="toc" id="toc"><h1>${esc(title)}</h1>$sb</nav>
<nav epub:type="page-list" id="page-list" hidden="">$pl</nav>
</body>
</html>
"""
    }

    fun opf(title: String, author: String, lang: String, id: String, files: List<String>, images: List<String>, modified: String): String {
        val manifest = StringBuilder()
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        manifest.append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
        files.forEachIndexed { i, f -> manifest.append("<item id=\"c$i\" href=\"$f\" media-type=\"application/xhtml+xml\"/>\n") }
        images.forEachIndexed { i, f -> manifest.append("<item id=\"img$i\" href=\"images/$f\" media-type=\"image/jpeg\"/>\n") }
        val spine = files.indices.joinToString("\n") { "<itemref idref=\"c$it\"/>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">${esc(id)}</dc:identifier>
<dc:title>${esc(title)}</dc:title>
<dc:creator>${esc(author)}</dc:creator>
<dc:language>$lang</dc:language>
<meta property="dcterms:modified">$modified</meta>
</metadata>
<manifest>
$manifest</manifest>
<spine>
$spine
</spine>
</package>
"""
    }

    val CSS = """
body { font-family: serif; line-height: 1.45; margin: 0; padding: 0; }
p { margin: 0; text-indent: 1.4em; text-align: justify; hyphens: auto; -webkit-hyphens: auto; }
p.noindent, p.cont, h1 + p, h2 + p, h3 + p, h4 + p, figure + p, blockquote + p, aside + p { text-indent: 0; }
p.gap { margin-top: 0.8em; text-indent: 0; }
p.center, .center { text-align: center; text-indent: 0; }
p.center { margin: 0.4em 0; }
h1, h2, h3, h4 { font-weight: normal; line-height: 1.25; margin: 1.4em 0 0.7em; text-indent: 0; page-break-after: avoid; break-after: avoid; }
h1 { font-size: 1.7em; text-align: center; margin-top: 2em; }
h2 { font-size: 1.35em; }
h3 { font-size: 1.15em; }
h4 { font-size: 1em; font-weight: bold; }
h1 strong, h2 strong { font-weight: bold; }
blockquote { margin: 0.7em 1.6em; font-size: 0.94em; }
blockquote p { text-indent: 0; }
aside.fn { font-size: 0.82em; line-height: 1.35; border-top: 1px solid currentColor; margin: 0.9em 0 1.1em; padding-top: 0.3em; display: block; }
aside.fn p { text-indent: 0; margin: 0.2em 0; text-align: left; }
figure { margin: 1em 0; padding: 0; text-align: center; page-break-inside: avoid; break-inside: avoid; }
figure img { max-width: 100%; max-height: 92vh; height: auto; }
figure.page img { width: 100%; }
sup { font-size: 0.68em; line-height: 0; vertical-align: super; }
span[epub|type="pagebreak"] { display: inline; }
""".trimIndent()
}
