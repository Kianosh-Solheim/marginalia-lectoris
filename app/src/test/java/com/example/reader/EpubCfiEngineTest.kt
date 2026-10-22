package com.example.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCfiEngineTest {

    private fun buildSamplePages(): List<EpubPage> {
        val p1Block1 = EpubSemanticBlock(
            type = EpubSemanticBlockType.PARAGRAPH,
            text = "First paragraph of chapter one with some sample prose.",
            anchorIds = listOf("p1"),
            paragraphNumberInChapter = 1,
            pageEquivalent = "1",
            fileKey = "chap01.xhtml"
        )
        val p1Block2 = EpubSemanticBlock(
            type = EpubSemanticBlockType.PARAGRAPH,
            text = "Second paragraph of chapter one continuing the narrative.",
            anchorIds = listOf("p2"),
            paragraphNumberInChapter = 2,
            pageEquivalent = "1",
            fileKey = "chap01.xhtml"
        )
        val p2Block1 = EpubSemanticBlock(
            type = EpubSemanticBlockType.PARAGRAPH,
            text = "Third paragraph of chapter one on the second page.",
            anchorIds = listOf("p3"),
            paragraphNumberInChapter = 3,
            pageEquivalent = "2",
            fileKey = "chap01.xhtml"
        )

        val page0 = EpubPage(
            globalIndex = 0,
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            pageInChapter = 1,
            totalPagesInChapter = 2,
            paragraphs = listOf(p1Block1.text, p1Block2.text),
            pageEquivalentLabel = "1",
            startParagraphNumber = 1,
            endParagraphNumber = 2,
            startStableLocation = 1,
            endStableLocation = 1,
            semanticBlocks = listOf(p1Block1, p1Block2),
            anchorIds = setOf("p1", "p2"),
            fileKey = "chap01.xhtml"
        )
        val page1 = EpubPage(
            globalIndex = 1,
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            pageInChapter = 2,
            totalPagesInChapter = 2,
            paragraphs = listOf(p2Block1.text),
            pageEquivalentLabel = "2",
            startParagraphNumber = 3,
            endParagraphNumber = 3,
            startStableLocation = 2,
            endStableLocation = 2,
            semanticBlocks = listOf(p2Block1),
            anchorIds = setOf("p3"),
            fileKey = "chap01.xhtml"
        )
        return listOf(page0, page1)
    }

    @Test
    fun pointCfi_buildParseResolveRoundTrip() {
        val pages = buildSamplePages()

        // 1. Build point CFI: spineIndex = 0 (/6/2[chap01]), elementPath = "/4/6[p3]", charOffset = 6
        val pointCfi = EpubCfiEngine.buildPointCfi(
            spineIndex = 0,
            elementPath = "/4/6[p3]",
            charOffset = 6,
            spineId = "chap01"
        )
        assertEquals("epubcfi(/6/2[chap01]!/4/6[p3]/1:6)", pointCfi)

        // 2. Parse point CFI
        val parseResult = EpubCfiEngine.parseCfi(pointCfi)
        assertTrue("Expected Success but got $parseResult", parseResult is EpubCfiParseResult.Success)
        val parsed = (parseResult as EpubCfiParseResult.Success).parsed
        assertEquals(0, parsed.spineIndex)
        assertEquals("chap01", parsed.spineIdAssertion)
        assertEquals("p3", parsed.startBlockIdAssertion)
        assertEquals(6, parsed.startCharOffset)

        // 3. Resolve point CFI to spineIndex, elementPath, charOffset, and EpubPage
        val resolved = EpubCfiEngine.resolveCfi(pointCfi, pages)
        assertNotNull(resolved)
        assertEquals(0, resolved!!.spineIndex)
        assertEquals("/4/6[p3]", resolved.elementPath)
        assertEquals(6, resolved.charOffset)
        assertNotNull(resolved.page)
        assertEquals(1, resolved.page!!.globalIndex)
    }

    @Test
    fun rangeCfi_buildParseResolveRoundTrip() {
        val pages = buildSamplePages()

        // 1. Build range CFI across paragraphs 1 and 2 on page 0
        val startPoint = EpubCfiPoint(
            spineIndex = 0,
            spineId = "chap01",
            elementPath = "/4/2[p1]",
            charOffset = 6
        )
        val endPoint = EpubCfiPoint(
            spineIndex = 0,
            spineId = "chap01",
            elementPath = "/4/4[p2]",
            charOffset = 16
        )
        val rangeCfi = EpubCfiEngine.buildRangeCfi(startPoint, endPoint)
        assertEquals("epubcfi(/6/2[chap01]!/4,/2[p1]/1:6,/4[p2]/1:16)", rangeCfi)

        // 2. Parse range CFI
        val parseResult = EpubCfiEngine.parseCfi(rangeCfi)
        assertTrue("Expected Success but got $parseResult", parseResult is EpubCfiParseResult.Success)
        val parsed = (parseResult as EpubCfiParseResult.Success).parsed
        assertTrue(parsed.isRange)
        assertEquals(0, parsed.spineIndex)
        assertEquals(0, parsed.startBlockIndex)
        assertEquals(1, parsed.endBlockIndex)
        assertEquals(6, parsed.startCharOffset)
        assertEquals(16, parsed.endCharOffset)

        // 3. Resolve range CFI to spineIndex, elementPath, charOffset, and EpubPage
        val resolved = EpubCfiEngine.resolveCfi(rangeCfi, pages)
        assertNotNull(resolved)
        assertEquals(0, resolved!!.spineIndex)
        assertEquals("/4/2[p1]", resolved.elementPath)
        assertEquals(6, resolved.charOffset)
        assertNotNull(resolved.page)
        assertEquals(0, resolved.page!!.globalIndex)
    }

    @Test
    fun malformedCfi_returnsErrorWithoutThrowing() {
        val pages = buildSamplePages()
        val malformedInputs = listOf(
            "",
            "   ",
            "not-a-cfi",
            "epubcfi()",
            "epubcfi(/6/2)", // missing '!' indirection
            "epubcfi(/8/2!/4/2/1:0)", // invalid package step (must start with /6)
            "epubcfi(/6/3!/4/2/1:0)", // odd spine step
            "epubcfi(/6/2!/4/2/1:-5)" // negative character offset
        )

        for (badInput in malformedInputs) {
            val result = EpubCfiEngine.parseCfi(badInput)
            assertTrue(
                "Expected Error for malformed input '$badInput' but got $result",
                result is EpubCfiParseResult.Error
            )
            // Also verify resolveCfi returns null gracefully without throwing
            val resolved = EpubCfiEngine.resolveCfi(badInput, pages)
            assertEquals(null, resolved)
        }
    }

    @Test
    fun phase4StructuralBlocks_preserveParagraphNumberingAndStableLocations() {
        val baseXhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body>
                <h1>Chapter 1: Foundations</h1>
                <p id="p1">First canonical body paragraph with a footnote ref <a epub:type="noteref" href="#fn1">[1]</a> and inline <code>code()</code>.</p>
                <p id="p2">Second canonical body paragraph explaining the scholarly edition apparatus.</p>
                <p id="p3">Third canonical body paragraph concluding the section.</p>
            </body>
            </html>
        """.trimIndent()

        val enrichedPhase4Xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body>
                <h1>Chapter 1: Foundations</h1>
                <p id="p1">First canonical body paragraph with a footnote ref <a epub:type="noteref" href="#fn1">[1]</a> and inline <code>code()</code>.</p>
                <table>
                    <thead><tr><th>Edition</th><th>Year</th></tr></thead>
                    <tbody><tr><td>First</td><td>1887</td></tr></tbody>
                </table>
                <pre><code>val cfi = "epubcfi(/6/2!/4/2/1:0)"</code></pre>
                <p id="p2">Second canonical body paragraph explaining the scholarly edition apparatus.</p>
                <dl>
                    <dt>Apparatus Criticus</dt>
                    <dd>Critical textual notes accompanying an edition.</dd>
                </dl>
                <aside epub:type="sidebar">
                    <p>Editorial Callout: Textual variants follow the 1892 printing.</p>
                </aside>
                <figure>
                    <img src="images/plate1.png" alt="Plate I" />
                    <figcaption>Figure 1.1: Manuscript folio 14r.</figcaption>
                </figure>
                <p id="p3">Third canonical body paragraph concluding the section.</p>
                <aside id="fn1" epub:type="footnote">
                    <p>1. Cf. Codex Vaticanus fol. 12v for the variant reading.</p>
                </aside>
            </body>
            </html>
        """.trimIndent()

        val baseParagraphs = listOf(
            "## Chapter 1: Foundations",
            "First canonical body paragraph with a footnote ref [1] and inline code().",
            "Second canonical body paragraph explaining the scholarly edition apparatus.",
            "Third canonical body paragraph concluding the section."
        )
        val beforeExtracted = EpubSemanticHelper.extractSemanticStructureForChapter(
            rawHtml = baseXhtml,
            fileKey = "OEBPS/chap01.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1: Foundations",
            existingParagraphs = baseParagraphs,
            existingPageEquivalents = listOf("", "41", "41", "42"),
            existingFootnotes = emptyMap()
        )
        val afterExtracted = EpubSemanticHelper.extractSemanticStructureForChapter(
            rawHtml = enrichedPhase4Xhtml,
            fileKey = "OEBPS/chap01.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1: Foundations",
            existingParagraphs = baseParagraphs,
            existingPageEquivalents = listOf("", "41", "41", "42"),
            existingFootnotes = emptyMap()
        )

        // Verify counted body paragraphs (¶ 1..3) are identical before and after adding Phase 4 blocks
        val beforeCounted = beforeExtracted.semanticBlocks.filter { it.paragraphNumberInChapter != null }
        val afterCounted = afterExtracted.semanticBlocks.filter { it.paragraphNumberInChapter != null }

        assertEquals(3, beforeCounted.size)
        assertEquals(3, afterCounted.size)
        for (i in 0 until 3) {
            assertEquals(i + 1, beforeCounted[i].paragraphNumberInChapter)
            assertEquals(i + 1, afterCounted[i].paragraphNumberInChapter)
            assertEquals(beforeCounted[i].text, afterCounted[i].text)
        }

        // Verify Phase 4 blocks were parsed with their distinct types and null paragraphNumberInChapter
        assertTrue(afterExtracted.semanticBlocks.any { it.type == EpubSemanticBlockType.TABLE && it.paragraphNumberInChapter == null })
        assertTrue(afterExtracted.semanticBlocks.any { it.type == EpubSemanticBlockType.PREFORMATTED && it.paragraphNumberInChapter == null })
        assertTrue(afterExtracted.semanticBlocks.any { it.type == EpubSemanticBlockType.DEFINITION_LIST && it.paragraphNumberInChapter == null })
        assertTrue(afterExtracted.semanticBlocks.any { it.type == EpubSemanticBlockType.CALLOUT && it.paragraphNumberInChapter == null })
        assertTrue(afterExtracted.semanticBlocks.any { it.type == EpubSemanticBlockType.FIGURE && it.paragraphNumberInChapter == null })
        assertTrue(afterExtracted.footnoteEntries.containsKey("fn1"))

        // Verify stable location (loc.) consistency across pagination
        val bookBefore = EpubBook(
            title = "Test Book",
            author = "Author",
            description = "",
            chapters = listOf(
                EpubChapter(
                    id = "chap01",
                    title = "Chapter 1: Foundations",
                    content = baseParagraphs.joinToString("\n\n"),
                    paragraphs = baseParagraphs,
                    paragraphPageEquivalents = listOf("", "41", "41", "42"),
                    semanticBlocks = beforeExtracted.semanticBlocks,
                    footnoteEntries = beforeExtracted.footnoteEntries,
                    fileKey = "chap01.xhtml"
                )
            )
        )
        val bookAfter = EpubBook(
            title = "Test Book",
            author = "Author",
            description = "",
            chapters = listOf(
                EpubChapter(
                    id = "chap01",
                    title = "Chapter 1: Foundations",
                    content = baseParagraphs.joinToString("\n\n"),
                    paragraphs = baseParagraphs,
                    paragraphPageEquivalents = listOf("", "41", "41", "42"),
                    semanticBlocks = afterExtracted.semanticBlocks,
                    footnoteEntries = afterExtracted.footnoteEntries,
                    fileKey = "chap01.xhtml"
                )
            )
        )
        val (pagesBefore, _) = EpubParser.paginateBook(bookBefore, 18f, 1.5f, 20)
        val (pagesAfter, _) = EpubParser.paginateBook(bookAfter, 18f, 1.5f, 20)
        assertEquals(pagesBefore.first().startStableLocation, pagesAfter.first().startStableLocation)
        assertEquals(pagesBefore.last().endStableLocation, pagesAfter.last().endStableLocation)
    }
}
