package com.example.reader

import com.example.reader.readium.PrintPageHtmlRewriter
import com.example.reader.readium.PrintPageLayoutMode
import com.example.reader.readium.PrintPageTarget
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPageHtmlRewriterTest {

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>t</title></head><body>$body</body></html>"""

    private fun parse(s: String) = Jsoup.parse(s, "", Parser.xmlParser())

    private val targets = listOf(PrintPageTarget("p12", "12"), PrintPageTarget("p13", "13"))

    @Test
    fun strict_splitsParagraphAtMidParagraphMarker() {
        val out = PrintPageHtmlRewriter.rewrite(
            xhtml("""<p id="a">Before <span epub:type="pagebreak" id="p12"/>after</p>"""),
            true, targets, PrintPageLayoutMode.PAGINATED_STRICT
        )
        val doc = parse(out)
        val sections = doc.select("section.ml-printpage")
        assertEquals(2, sections.size)
        assertEquals("Before", sections[0].text())
        assertEquals("after", sections[1].text())
        assertEquals("12", sections[1].attr("data-page"))
        assertTrue(sections[1].selectFirst("p")!!.hasClass("ml-cont"))
        assertEquals("p12", sections[1].selectFirst("span")!!.id())
        assertEquals(1, doc.select("#a").size)
    }

    @Test
    fun strict_markerBetweenBlocks() {
        val out = PrintPageHtmlRewriter.rewrite(
            xhtml("""<p>One</p><span epub:type="pagebreak" id="p12"/><p>Two</p>"""),
            true, targets, PrintPageLayoutMode.PAGINATED_STRICT
        )
        val sections = parse(out).select("section.ml-printpage")
        assertEquals(listOf("One", "Two"), sections.map { it.text() })
    }

    @Test
    fun strict_markerFirstInBody_noEmptyLeadingSection() {
        val out = PrintPageHtmlRewriter.rewrite(
            xhtml("""<div><p><span epub:type="pagebreak" id="p12"/>Start</p><p>More</p></div>"""),
            true, targets, PrintPageLayoutMode.PAGINATED_STRICT
        )
        val sections = parse(out).select("section.ml-printpage")
        assertEquals(1, sections.size)
        assertEquals("12", sections[0].attr("data-page"))
    }

    @Test
    fun strict_nestedInlineMarker() {
        val out = PrintPageHtmlRewriter.rewrite(
            xhtml("""<p>A <em>b <span id="p12"/>c</em> d</p>"""),
            true, targets, PrintPageLayoutMode.PAGINATED_STRICT
        )
        val sections = parse(out).select("section.ml-printpage")
        assertEquals(listOf("A b", "c d"), sections.map { it.text() })
        assertEquals("c", sections[1].selectFirst("em")!!.text())
    }

    @Test
    fun strict_orderedListNumberingContinues() {
        val out = PrintPageHtmlRewriter.rewrite(
            xhtml("""<ol start="3"><li>x</li><li>y <span id="p12"/>z</li><li>w</li></ol>"""),
            true, targets, PrintPageLayoutMode.PAGINATED_STRICT
        )
        val ols = parse(out).select("ol")
        assertEquals(2, ols.size)
        assertEquals("4", ols[1].attr("start"))
        assertTrue(ols[1].child(0).hasClass("ml-cont"))
    }

    @Test
    fun dividers_insertedMidParagraph_withoutTextNodes() {
        val out = PrintPageHtmlRewriter.rewrite(
            xhtml("""<p>Before <span epub:type="pagebreak" id="p12"/>after</p>"""),
            true, targets, PrintPageLayoutMode.SCROLL_DIVIDERS
        )
        val doc = parse(out)
        val divider = doc.selectFirst("div.ml-printpage-divider")!!
        assertEquals("12", divider.attr("data-page"))
        assertEquals("", divider.text())
        assertEquals("p", divider.previousElementSibling()!!.tagName())
        assertEquals("p", divider.nextElementSibling()!!.tagName())
        assertTrue(doc.select("section.ml-printpage").isEmpty())
        assertFalse(doc.body().text().contains("12"))
    }

    @Test
    fun noMarkers_returnsInputUnchanged() {
        val src = xhtml("<p>Plain</p>")
        assertTrue(src === PrintPageHtmlRewriter.rewrite(src, true, targets, PrintPageLayoutMode.PAGINATED_STRICT))
    }

    @Test
    fun resolveMode() {
        assertEquals(PrintPageLayoutMode.PAGINATED_STRICT, PrintPageLayoutMode.resolve(true, true, "PAGINATED"))
        assertEquals(PrintPageLayoutMode.SCROLL_DIVIDERS, PrintPageLayoutMode.resolve(true, true, "VERTICAL_SCROLL"))
        assertEquals(PrintPageLayoutMode.SCROLL_DIVIDERS, PrintPageLayoutMode.resolve(false, true, "VERTICAL_SCROLL"))
        assertEquals(PrintPageLayoutMode.OFF, PrintPageLayoutMode.resolve(false, true, "PAGINATED"))
        assertEquals(PrintPageLayoutMode.OFF, PrintPageLayoutMode.resolve(true, false, "PAGINATED"))
    }
}
