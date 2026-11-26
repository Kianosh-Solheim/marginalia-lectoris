package com.example.reader.readium

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * How printed-page markers are rendered by the Readium engine. Two settings choose it, the same in the
 * app and in the web view: "Show page numbers" and "Like the printed book".
 *
 * - [OFF]: resources are served untouched.
 * - [INLINE_LABELS]: page numbers on, book off: a small label in the text where each printed page begins
 *   (paginated and scroll).
 * - [PAGINATED_STRICT]: "1:1 Print Edition". The DOM is split at every print page marker and each
 *   printed page is wrapped in its own `<section class="ml-printpage">` that starts on a fresh
 *   column (screen). An injected script shrinks a page's text (down to [MIN_FIT_SCALE]) until the
 *   whole printed page fits on one screen.
 * - [SCROLL_DIVIDERS]: vertical scroll. A thin rule with the page number underneath is inserted
 *   at every marker (mid-paragraph markers split the paragraph).
 */
enum class PrintPageLayoutMode {
    OFF, INLINE_LABELS, PAGINATED_STRICT, SCROLL_DIVIDERS;

    companion object {
        /**
         * Page numbers off -> OFF. On, not like the book -> labels in the text.
         * On and like the book -> strict pages (paginated) or divider lines (scroll).
         */
        fun resolve(paginateByPrintedPages: Boolean, showPrintedPageNumbers: Boolean, viewMode: String): PrintPageLayoutMode {
            val scroll = viewMode.equals("VERTICAL_SCROLL", ignoreCase = true) || viewMode.equals("SCROLL", ignoreCase = true)
            return when {
                !showPrintedPageNumbers -> OFF
                !paginateByPrintedPages -> INLINE_LABELS
                scroll -> SCROLL_DIVIDERS
                else -> PAGINATED_STRICT
            }
        }
    }
}

/** One print page marker inside a single XHTML resource. */
data class PrintPageTarget(val id: String, val label: String)

/**
 * Serve-time XHTML rewriter for printed-page layout. Pure JVM code (Jsoup only) so it can be unit tested.
 *
 * Original marker elements are always kept (with their ids) so page-list links, "go to page" and
 * the current-page label keep resolving.
 */
object PrintPageHtmlRewriter {

    const val MIN_FIT_SCALE = 0.70

    private val INLINE_TAGS = setOf(
        "a", "abbr", "b", "bdi", "bdo", "big", "cite", "code", "data", "del", "dfn", "em", "font",
        "i", "ins", "kbd", "label", "mark", "q", "s", "samp", "small", "span", "strike", "strong",
        "sub", "sup", "time", "tt", "u", "var"
    )

    /** Text-level blocks that may be split so a divider can sit between the two halves (scroll mode). */
    private val PARAGRAPH_BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "dt", "dd", "figcaption", "pre")

    /** Containers we never split across; the marker is treated as starting the enclosing row/cell. */
    private val NO_SPLIT_CONTAINERS = setOf("td", "th", "caption", "tr", "thead", "tfoot")

    private val MEDIA_TAGS = setOf("img", "image", "svg", "video", "audio", "object", "embed", "iframe", "math", "canvas", "hr", "br")

    /**
     * Rewrites one HTML resource. Returns the input unchanged if no marker is found or parsing fails.
     *
     * @param html raw resource text
     * @param isXhtml parse/serialize as XML (true for application/xhtml+xml)
     * @param targets markers for this resource from the publication page-list (may be empty)
     */
    fun rewrite(html: String, isXhtml: Boolean, targets: List<PrintPageTarget>, mode: PrintPageLayoutMode): String {
        if (mode == PrintPageLayoutMode.OFF) return html
        return try {
            rewriteOrNull(html, isXhtml, targets, mode) ?: html
        } catch (_: Throwable) {
            html
        }
    }

    private fun rewriteOrNull(html: String, isXhtml: Boolean, targets: List<PrintPageTarget>, mode: PrintPageLayoutMode): String? {
        val doc: Document = if (isXhtml) Jsoup.parse(html, "", Parser.xmlParser()) else Jsoup.parse(html)
        val body = doc.getElementsByTag("body").firstOrNull() ?: return null
        val head = doc.getElementsByTag("head").firstOrNull()
            ?: doc.getElementsByTag("html").firstOrNull()?.prependElement("head")
            ?: return null

        val markers = findMarkers(body, targets)
        if (markers.isEmpty()) return null

        val blanks = blankMarkers(body, markers)
        when (mode) {
            PrintPageLayoutMode.PAGINATED_STRICT -> applyStrict(body, markers, blanks)
            PrintPageLayoutMode.SCROLL_DIVIDERS -> applyDividers(markers, blanks)
            PrintPageLayoutMode.INLINE_LABELS -> applyLabels(markers)
            PrintPageLayoutMode.OFF -> return null
        }

        head.appendChild(styleElement(isXhtml, when (mode) {
            PrintPageLayoutMode.PAGINATED_STRICT -> STRICT_CSS
            PrintPageLayoutMode.INLINE_LABELS -> LABEL_CSS
            else -> DIVIDER_CSS
        }))
        if (mode == PrintPageLayoutMode.PAGINATED_STRICT) {
            head.appendChild(scriptElement(isXhtml, FIT_SCRIPT))
        }

        doc.outputSettings().apply {
            prettyPrint(false)
            charset(Charsets.UTF_8)
            if (isXhtml) {
                syntax(Document.OutputSettings.Syntax.xml)
                escapeMode(Entities.EscapeMode.xhtml)
            }
        }
        return doc.outerHtml()
    }

    // ---------------------------------------------------------------- markers

    private data class Marker(val element: Element, val label: String)

    private fun findMarkers(body: Element, targets: List<PrintPageTarget>): List<Marker> {
        val labelById = LinkedHashMap<String, String>()
        targets.forEach { if (it.id.isNotBlank()) labelById[it.id] = it.label }
        val result = mutableListOf<Marker>()
        val seen = HashSet<Element>()
        for (el in body.getAllElements()) {
            if (el === body) continue
            val id = el.attr("id")
            val fromList = id.isNotEmpty() && labelById.containsKey(id)
            val epubType = el.attr("epub:type").split(Regex("\\s+"))
            val role = el.attr("role").split(Regex("\\s+"))
            val semantic = "pagebreak" in epubType || "doc-pagebreak" in role
            if ((fromList || semantic) && seen.add(el)) {
                val label = labelById[id]?.takeIf { it.isNotBlank() }
                    ?: el.attr("title").takeIf { it.isNotBlank() }
                    ?: el.attr("aria-label").takeIf { it.isNotBlank() }
                    ?: el.text().trim().takeIf { it.isNotBlank() && it.length <= 12 }
                    ?: id.substringAfterLast('_').substringAfterLast('-').takeIf { it.isNotBlank() && it.length <= 8 }
                    ?: ""
                result.add(Marker(el, cleanLabel(label)))
            }
        }
        return result
    }

    private fun cleanLabel(raw: String): String {
        var s = raw.trim()
        for (p in listOf("Side ", "Page ", "p. ", "p ", "S. ")) {
            if (s.startsWith(p, ignoreCase = true)) { s = s.substring(p.length).trim(); break }
        }
        return s
    }

    // ---------------------------------------------------------------- tree helpers

    private fun hasContent(node: Node): Boolean = when (node) {
        is TextNode -> !node.isBlank
        is Comment, is DataNode -> false
        is Element -> {
            val tag = node.normalName()
            when {
                tag in MEDIA_TAGS -> tag != "br"
                node.hasAttr("epub:type") && "pagebreak" in node.attr("epub:type") -> false
                node.attr("role").contains("doc-pagebreak") -> false
                else -> node.childNodes().any { hasContent(it) }
            }
        }
        else -> false
    }

    private fun hasContentBefore(node: Node): Boolean {
        var sib = node.previousSibling()
        while (sib != null) {
            if (hasContent(sib)) return true
            sib = sib.previousSibling()
        }
        return false
    }

    /**
     * Splits [parent] so that [child] and everything after it move into a shallow clone of [parent]
     * inserted directly after it. Returns the clone.
     */
    private fun splitAt(parent: Element, child: Node): Element {
        val clone = parent.shallowClone()
        clone.removeAttr("id")
        clone.addClass("ml-cont")
        parent.addClass("ml-split-head")
        if (parent.normalName() == "ol") {
            val start = parent.attr("start").toIntOrNull() ?: 1
            val liBefore = generateSequence(child.previousSibling()) { it.previousSibling() }
                .count { it is Element && it.normalName() == "li" }
            // A split <li> keeps counting as one item; its continuation is hidden but still increments.
            val continuing = child is Element && child.normalName() == "li" && child.hasClass("ml-cont")
            clone.attr("start", (start + liBefore - if (continuing) 1 else 0).toString())
        }
        val toMove = mutableListOf<Node>()
        var n: Node? = child
        while (n != null) { toMove.add(n); n = n.nextSibling() }
        parent.after(clone)
        toMove.forEach { clone.appendChild(it) }
        return clone
    }

    // ---------------------------------------------------------------- strict 1:1 pagination

    /**
     * Markers of blank printed pages: the next marker follows with no text or picture between them
     * (a blank page before a chapter, written just before the next page's marker).
     */
    private fun blankMarkers(body: Element, markers: List<Marker>): Set<Element> {
        val isMarker = markers.map { it.element }.toHashSet()
        val order = mutableListOf<Any>()   // marker Elements, and CONTENT for text or media between them
        fun walk(n: Node) {
            when {
                n is Element && n in isMarker -> order.add(n)
                n is TextNode -> if (!n.isBlank) order.add(CONTENT)
                n is Element && n.normalName() in MEDIA_TAGS && n.normalName() != "br" -> order.add(CONTENT)
                n is Element -> n.childNodes().forEach { walk(it) }
            }
        }
        walk(body)
        val out = HashSet<Element>()
        for (i in 0 until order.size - 1) if (order[i] is Element && order[i + 1] is Element) out.add(order[i] as Element)
        return out
    }

    private val CONTENT = Any()

    private fun applyStrict(body: Element, allMarkers: List<Marker>, blanks: Set<Element>) {
        // blank pages get their own (empty) screen page, before the page that follows them
        val markers = allMarkers.filter { it.element !in blanks }
        val blanksBefore = HashMap<String, MutableList<Marker>>()
        var pending = mutableListOf<Marker>()
        for (m in allMarkers) {
            if (m.element in blanks) pending.add(m)
            else if (pending.isNotEmpty()) { blanksBefore[m.label] = pending; pending = mutableListOf() }
        }
        val starts = LinkedHashMap<Node, String>()
        for (m in markers) {
            var cur: Node = m.element
            while (true) {
                val parent = cur.parent() as? Element ?: break
                if (parent === body) break
                if (parent.normalName() in NO_SPLIT_CONTAINERS || !hasContentBefore(cur)) {
                    cur = parent
                    continue
                }
                cur = splitAt(parent, cur)
            }
            if (cur.parent() === body) starts[cur] = m.label
        }

        // Group body children into one <section> per printed page.
        val children = body.childNodes().toList()
        var section: Element? = null
        for (child in children) {
            val startLabel = starts[child]
            // blank pages before this printed page: each its own section, just before the page's section
            fun blanksBefore(at: Node, label: String) = blanksBefore.remove(label)?.forEach { b ->
                val blank = Element("section").addClass("ml-printpage").addClass("ml-blank").attr("data-page", b.label)
                at.before(blank)
                blank.appendChild(b.element)   // moved here, so "go to page" lands on the blank page
                blank.appendElement("div").addClass("ml-blank-fill")
            }
            if (section == null || (startLabel != null && section.childNodes().any { hasContent(it) })) {
                if (startLabel != null) blanksBefore(child, startLabel)
                section = Element("section").apply {
                    addClass("ml-printpage")
                    if (startLabel != null) attr("data-page", startLabel)
                }
                child.before(section)
            } else if (startLabel != null && !section.hasAttr("data-page")) {
                blanksBefore(section, startLabel)
                section.attr("data-page", startLabel)
            }
            section.appendChild(child)
        }
        // a chapter's first page (no running head on it, as in print): the file's first page with text, when the
        // file begins with a printed page (nothing before its marker) or that page has a heading
        body.children().firstOrNull { it.hasClass("ml-printpage") && !it.hasClass("ml-blank") && it.childNodes().any { n -> hasContent(n) } }
            ?.takeIf { it.hasAttr("data-page") || it.selectFirst("h1, h2, h3") != null }?.addClass("ml-opening")
        body.addClass("ml-print-strict")
    }

    // ---------------------------------------------------------------- vertical scroll dividers

    private fun applyDividers(markers: List<Marker>, blanks: Set<Element>) {
        for (m in markers) {
            var cur: Node = m.element
            while (true) {
                val parent = cur.parent() as? Element ?: break
                val tag = parent.normalName()
                if (tag == "body" || (tag !in INLINE_TAGS && tag !in PARAGRAPH_BLOCKS)) break
                cur = if (hasContentBefore(cur)) splitAt(parent, cur) else parent
            }
            val divider = Element("div")
                .addClass("ml-printpage-divider")
                .attr("aria-hidden", "true")
                .attr("data-page", m.label)
                .attr("data-caption", "Page ${m.label}" + if (m.element in blanks) " · blank page" else "")
            cur.before(divider)
        }
    }

    // ---------------------------------------------------------------- labels in the text

    /** A small label where each printed page begins (a marker that shows its own number is left as it is). */
    private fun applyLabels(markers: List<Marker>) {
        for (m in markers) {
            if (m.element.text().isNotBlank() || m.label.isBlank()) continue
            m.element.addClass("ml-pagelabel").attr("data-ml-page", m.label)
        }
    }

    // ---------------------------------------------------------------- style / script

    private fun styleElement(isXhtml: Boolean, css: String): Element =
        Element("style").attr("type", "text/css").attr("id", "ml-printpage-style")
            .appendChild(DataNode(if (isXhtml) "/*<![CDATA[*/\n$css\n/*]]>*/" else css))

    private fun scriptElement(isXhtml: Boolean, js: String): Element =
        Element("script").attr("type", "text/javascript").attr("id", "ml-printpage-fit")
            .appendChild(DataNode(if (isXhtml) "//<![CDATA[\n$js\n//]]>" else js))

    private val STRICT_CSS = """
        section.ml-printpage { display: block !important; margin: 0 !important; padding: 0 !important; }
        section.ml-printpage + section.ml-printpage {
          /* NB: no page-break-before here - it aliases break-before:page, which Chromium ignores inside columns */
          -webkit-column-break-before: always !important; break-before: column !important;
        }
        section.ml-printpage * {
          break-before: auto !important; -webkit-column-break-before: auto !important; page-break-before: auto !important;
          break-after: auto !important; -webkit-column-break-after: auto !important; page-break-after: auto !important;
        }
        .ml-cont { text-indent: 0 !important; margin-top: 0 !important; padding-top: 0 !important; border-top: 0 !important; }
        li.ml-cont { list-style-type: none !important; }
        .ml-split-head { margin-bottom: 0 !important; padding-bottom: 0 !important; border-bottom: 0 !important; }
        section.ml-printpage > :first-child { margin-top: 0 !important; }
        section.ml-blank .ml-blank-fill { height: 1px; }
    """.trimIndent()

    private val LABEL_CSS = """
        .ml-pagelabel::before {
          content: attr(data-ml-page); display: inline-block; text-indent: 0; font-style: normal; font-weight: 600;
          font-family: ui-monospace, "Roboto Mono", "Droid Sans Mono", monospace; font-size: 0.62em; line-height: 1.3;
          padding: 0 0.3em; margin: 0 0.2em; border: 1px solid currentColor; border-radius: 0.3em; opacity: 0.55;
          vertical-align: 0.15em; white-space: nowrap; -webkit-user-select: none; user-select: none;
        }
    """.trimIndent()

    private val DIVIDER_CSS = """
        .ml-cont { text-indent: 0 !important; margin-top: 0 !important; padding-top: 0 !important; }
        li.ml-cont { list-style-type: none !important; }
        .ml-split-head { margin-bottom: 0 !important; padding-bottom: 0 !important; }
        .ml-printpage-divider {
          display: flex !important; align-items: center; gap: 0.7em; clear: both; text-indent: 0 !important;
          margin: 1.3em 0 1em 0 !important; padding: 0 !important; height: auto; line-height: 1 !important;
          -webkit-user-select: none !important; user-select: none !important; pointer-events: none;
        }
        .ml-printpage-divider::before {
          content: attr(data-caption); flex: none; opacity: 0.6;
          font-family: ui-monospace, "Roboto Mono", "Droid Sans Mono", monospace; font-size: 0.7rem; font-weight: 600;
          font-variant-numeric: tabular-nums;
        }
        .ml-printpage-divider::after {
          content: ""; flex: 1; height: 0; border-top: 1px solid currentColor; opacity: 0.25;
        }
    """.trimIndent()

    /**
     * Shrink-to-fit for strict 1:1 pages. Readium paginates with CSS columns (one column = one screen),
     * so a printed page that overflows is fragmented across columns, which makes its bounding box wider
     * than one screen. For such pages we binary-search a CSS zoom between 1 and MIN_FIT_SCALE.
     * Re-runs when Readium changes user settings (it writes CSS variables on <html style>), on resize,
     * and when web fonts finish loading.
     */
    private val FIT_SCRIPT = """
        (function () {
          var MIN = $MIN_FIT_SCALE;
          var timer = null;
          function isScroll() {
            var v = getComputedStyle(document.documentElement).getPropertyValue('--USER__view');
            return v && v.indexOf('scroll') >= 0;
          }
          function overflows(s) {
            var w = window.innerWidth;
            if (s.getClientRects().length > 1) return true;
            var r = s.getBoundingClientRect();
            return r.width > w * 1.25;
          }
          function fit() {
            var sections = document.querySelectorAll('section.ml-printpage');
            var i;
            for (i = 0; i < sections.length; i++) sections[i].style.zoom = '';
            if (isScroll()) { document.documentElement.removeAttribute('data-ml-scaled'); return; }
            var scaled = 0;
            for (i = 0; i < sections.length; i++) {
              var s = sections[i];
              if (!overflows(s)) continue;
              var lo = MIN, hi = 1.0, best = MIN;
              s.style.zoom = String(MIN);
              if (overflows(s)) { scaled++; continue; }
              for (var k = 0; k < 7; k++) {
                var mid = (lo + hi) / 2;
                s.style.zoom = String(mid);
                if (overflows(s)) { hi = mid; } else { best = mid; lo = mid; }
              }
              s.style.zoom = String(Math.floor(best * 100) / 100);
              scaled++;
            }
            if (scaled > 0) document.documentElement.setAttribute('data-ml-scaled', String(scaled));
            else document.documentElement.removeAttribute('data-ml-scaled');
          }
          function schedule() {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function () { requestAnimationFrame(fit); }, 120);
          }
          window.mlPrintPageFit = fit;
          document.addEventListener('DOMContentLoaded', function () {
            schedule();
            if (document.fonts && document.fonts.ready) document.fonts.ready.then(schedule);
            new MutationObserver(schedule).observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class'] });
          });
          window.addEventListener('load', schedule);
          window.addEventListener('resize', schedule);
        })();
    """.trimIndent()
}
