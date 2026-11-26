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
        assertEquals(PrintPageLayoutMode.INLINE_LABELS, PrintPageLayoutMode.resolve(false, true, "VERTICAL_SCROLL"))
        assertEquals(PrintPageLayoutMode.INLINE_LABELS, PrintPageLayoutMode.resolve(false, true, "PAGINATED"))
        assertEquals(PrintPageLayoutMode.OFF, PrintPageLayoutMode.resolve(true, false, "PAGINATED"))
        assertEquals(PrintPageLayoutMode.OFF, PrintPageLayoutMode.resolve(false, false, "VERTICAL_SCROLL"))
    }

    private val blankTargets = listOf(PrintPageTarget("page-14", "14"), PrintPageTarget("page-15", "15"), PrintPageTarget("page-16", "16"))
    private val chapterStart = """<div><span epub:type="pagebreak" id="page-14"/><span epub:type="pagebreak" id="page-15"/><h1>Chapter Two</h1><p>Text <span epub:type="pagebreak" id="page-16"/>more</p></div>"""

    @Test
    fun strict_blankPageGetsItsOwnSection_andTheOpeningIsMarked() {
        val doc = parse(PrintPageHtmlRewriter.rewrite(xhtml(chapterStart), true, blankTargets, PrintPageLayoutMode.PAGINATED_STRICT))
        val sections = doc.select("section.ml-printpage")
        assertEquals(listOf("14", "15", "16"), sections.map { it.attr("data-page") })
        assertTrue(sections[0].hasClass("ml-blank"))
        assertEquals("page-14", sections[0].selectFirst("span")!!.id())
        assertEquals("", sections[0].text())
        assertTrue(sections[1].hasClass("ml-opening"))
        assertTrue(sections[1].text().startsWith("Chapter Two"))
        assertFalse(sections[2].hasClass("ml-opening"))
        assertEquals(1, doc.select("#page-14").size)
    }

    @Test
    fun dividers_blankPageIsSaidToBeBlank() {
        val doc = parse(PrintPageHtmlRewriter.rewrite(xhtml(chapterStart), true, blankTargets, PrintPageLayoutMode.SCROLL_DIVIDERS))
        assertEquals(listOf("Page 14 · blank page", "Page 15", "Page 16"), doc.select("div.ml-printpage-divider").map { it.attr("data-caption") })
    }

    @Test
    fun labels_markEachEmptyMarker() {
        val doc = parse(PrintPageHtmlRewriter.rewrite(
            xhtml("""<p>Before <span epub:type="pagebreak" id="p12"/>after <span epub:type="pagebreak" id="p13">13</span></p>"""),
            true, targets, PrintPageLayoutMode.INLINE_LABELS))
        assertEquals("12", doc.selectFirst("#p12")!!.attr("data-ml-page"))
        assertTrue(doc.selectFirst("#p12")!!.hasClass("ml-pagelabel"))
        assertFalse(doc.selectFirst("#p13")!!.hasClass("ml-pagelabel"))   // shows its own number already
        assertTrue(doc.select("section.ml-printpage, div.ml-printpage-divider").isEmpty())
    }
}
