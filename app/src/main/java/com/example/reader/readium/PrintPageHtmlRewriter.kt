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
 * How printed-page markers are rendered by the Readium engine.
 *
 * - [OFF]: resources are served untouched.
 * - [PAGINATED_STRICT]: "1:1 Print Edition". The DOM is split at every print page marker and each
 *   printed page is wrapped in its own `<section class="ml-printpage">` that starts on a fresh
 *   column (screen). An injected script shrinks a page's text (down to [MIN_FIT_SCALE]) until the
 *   whole printed page fits on one screen.
 * - [SCROLL_DIVIDERS]: vertical scroll. A thin rule with the page number underneath is inserted
 *   at every marker (mid-paragraph markers split the paragraph).
 */
enum class PrintPageLayoutMode {
    OFF, PAGINATED_STRICT, SCROLL_DIVIDERS;

    companion object {
        /**
         * Paginated + "1:1 Print Edition" on -> strict pages.
         * Vertical scroll + "Show Printed Page Numbers" on -> divider lines with the page number.
         */
        fun resolve(paginateByPrintedPages: Boolean, showPrintedPageNumbers: Boolean, viewMode: String): PrintPageLayoutMode {
            val scroll = viewMode.equals("VERTICAL_SCROLL", ignoreCase = true) || viewMode.equals("SCROLL", ignoreCase = true)
            return when {
                scroll && showPrintedPageNumbers -> SCROLL_DIVIDERS
                !scroll && showPrintedPageNumbers && paginateByPrintedPages -> PAGINATED_STRICT
                else -> OFF
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

        when (mode) {
            PrintPageLayoutMode.PAGINATED_STRICT -> applyStrict(body, markers)
            PrintPageLayoutMode.SCROLL_DIVIDERS -> applyDividers(markers)
            PrintPageLayoutMode.OFF -> return null
        }

        head.appendChild(styleElement(isXhtml, if (mode == PrintPageLayoutMode.PAGINATED_STRICT) STRICT_CSS else DIVIDER_CSS))
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

    private fun applyStrict(body: Element, markers: List<Marker>) {
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
            if (section == null || (startLabel != null && section.childNodes().any { hasContent(it) })) {
                section = Element("section").apply {
                    addClass("ml-printpage")
                    if (startLabel != null) attr("data-page", startLabel)
                }
                child.before(section)
            } else if (startLabel != null && !section.hasAttr("data-page")) {
                section.attr("data-page", startLabel)
            }
            section.appendChild(child)
        }
        body.addClass("ml-print-strict")
    }

    // ---------------------------------------------------------------- vertical scroll dividers

    private fun applyDividers(markers: List<Marker>) {
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
            cur.before(divider)
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
    """.trimIndent()

    private val DIVIDER_CSS = """
        .ml-cont { text-indent: 0 !important; margin-top: 0 !important; padding-top: 0 !important; }
        li.ml-cont { list-style-type: none !important; }
        .ml-split-head { margin-bottom: 0 !important; padding-bottom: 0 !important; }
        .ml-printpage-divider {
          display: block !important; clear: both; text-indent: 0 !important; text-align: center !important;
          margin: 1.1em 0 0.9em 0 !important; padding: 0 !important; height: auto; line-height: 1 !important;
          -webkit-user-select: none !important; user-select: none !important; pointer-events: none;
        }
        .ml-printpage-divider::before {
          content: ""; display: block; height: 0; border-top: 1px solid currentColor; opacity: 0.28; margin: 0 0 0.45em 0;
        }
        .ml-printpage-divider::after {
          content: attr(data-page); display: block;
          font-family: ui-monospace, "Roboto Mono", "Droid Sans Mono", monospace; font-size: 0.72rem;
          font-variant-numeric: tabular-nums; letter-spacing: 0.08em; opacity: 0.6;
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
