package com.example.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfToEbookParserTest {

    private fun buildLineWords(
        text: String,
        lineIndex: Int,
        topRatio: Float,
        heightRatio: Float = 0.018f,
        leftRatio: Float = 0.10f,
        rightRatio: Float = 0.88f
    ): List<PdfPageWord> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val totalWidth = (rightRatio - leftRatio).coerceAtLeast(0.05f)
        val step = totalWidth / tokens.size
        return tokens.mapIndexed { idx, token ->
            val l = leftRatio + idx * step
            val r = (l + step * 0.9f).coerceAtMost(rightRatio)
            PdfPageWord(
                text = token,
                leftRatio = l,
                topRatio = topRatio,
                rightRatio = r,
                bottomRatio = topRatio + heightRatio,
                lineIndex = lineIndex,
                wordIndex = idx
            )
        }
    }

    private fun buildPageData(
        pageIndex: Int,
        lines: List<Triple<String, Float, Pair<Float, Float>>>,
        printedPageLabel: String = ""
    ): PdfPageTextData {
        val allWords = mutableListOf<PdfPageWord>()
        var globalWordIdx = 0
        lines.forEachIndexed { lineIdx, (text, topRatio, horizRange) ->
            val words = buildLineWords(
                text = text,
                lineIndex = lineIdx,
                topRatio = topRatio,
                leftRatio = horizRange.first,
                rightRatio = horizRange.second
            )
            words.forEach { w ->
                allWords.add(w.copy(wordIndex = globalWordIdx++))
            }
        }
        return PdfPageTextData(
            pageIndex = pageIndex,
            words = allWords,
            paragraphs = lines.map { it.first },
            fullText = lines.joinToString("\n") { it.first },
            printedPageLabel = printedPageLabel
        )
    }

    @Test
    fun `test 1 and 2 - normal text book PDF with repeated headers footers and cross-page paragraph stitch`() {
        val page1 = buildPageData(
            pageIndex = 0,
            printedPageLabel = "42",
            lines = listOf(
                Triple("MARCUS AURELIUS", 0.04f, 0.35f to 0.65f),
                Triple("Chapter 1: Meditations on Duty", 0.14f, 0.10f to 0.72f),
                Triple("Begin the morning by saying to thyself, I shall meet with the busybody and the ungrateful.", 0.24f, 0.10f to 0.88f),
                Triple("When you wake up in the morning, tell yourself: the people I deal with", 0.84f, 0.10f to 0.88f),
                Triple("42", 0.94f, 0.48f to 0.52f)
            )
        )

        val page2 = buildPageData(
            pageIndex = 1,
            printedPageLabel = "43",
            lines = listOf(
                Triple("MARCUS AURELIUS", 0.04f, 0.35f to 0.65f),
                Triple("today will be meddling, ungrateful, arrogant, dishonest, jealous, and surly.", 0.14f, 0.10f to 0.85f),
                Triple("• Remember that they cannot harm your ruling center.", 0.26f, 0.12f to 0.80f),
                Triple("43", 0.94f, 0.48f to 0.52f)
            )
        )

        val page3 = buildPageData(
            pageIndex = 2,
            printedPageLabel = "44",
            lines = listOf(
                Triple("MARCUS AURELIUS", 0.04f, 0.35f to 0.65f),
                Triple("Virtue is the sole good. Virtue is the sole good in every circumstance of life.", 0.14f, 0.10f to 0.86f),
                Triple("44", 0.94f, 0.48f to 0.52f)
            )
        )

        val ebookDoc = PdfToEbookParser.parsePdfToEbookFromPageData(
            rawPages = listOf(page1, page2, page3),
            documentTitle = "Meditations",
            documentAuthor = "Marcus Aurelius"
        )

        // Verify running author header and page numbers were removed from the reflowed text
        val allReflowText = ebookDoc.blocks.joinToString("\n") { it.text }
        assertFalse("Running author header should be stripped", allReflowText.contains("MARCUS AURELIUS"))
        assertTrue("Meaningful repeated body text must be preserved", allReflowText.contains("Virtue is the sole good."))

        // Verify cross-page paragraph stitch between Page 1 (42) and Page 2 (43)
        val stitchedBlock = ebookDoc.blocks.find {
            it.text.contains("When you wake up in the morning") &&
                it.text.contains("today will be meddling, ungrateful")
        }
        assertNotNull("Paragraph split across PDF pages 1 and 2 should be merged into a single reflow block", stitchedBlock)
        assertEquals(1, stitchedBlock!!.startPdfPage)
        assertEquals(2, stitchedBlock.endPdfPage)
        assertEquals("42", stitchedBlock.startPrintedPage)
        assertEquals("43", stitchedBlock.endPrintedPage)

        // Verify highlight quote page span resolution for a highlight that spans across the page break
        val crossPageQuote = "people I deal with today will be meddling, ungrateful"
        val resolvedSpan = ebookDoc.resolvePageSpanForQuote(crossPageQuote, fallbackPdfPage = 1)
        assertEquals(1, resolvedSpan.startPdfPage)
        assertEquals(2, resolvedSpan.endPdfPage)
        assertEquals("42", resolvedSpan.startPrintedPage)
        assertEquals("43", resolvedSpan.endPrintedPage)
        assertEquals("42–43|PDF:1–2", resolvedSpan.toStoredPrintedPageLabel())

        // Verify human-readable info bar summary for spanning highlight
        val summary = resolvedSpan.formatHumanReadableSummary()
        assertTrue("Summary should show both PDF range and printed page range: $summary", summary.contains("PDF pages 1–2"))
        assertTrue("Summary should show printed page range 42–43: $summary", summary.contains("printed pages 42–43"))

        // Verify single-page quote inside the stitched block resolves to the specific page if it only touches page 1
        val page1OnlyQuote = "When you wake up in the morning"
        val page1Span = ebookDoc.resolvePageSpanForQuote(page1OnlyQuote, fallbackPdfPage = 1)
        assertEquals(1, page1Span.startPdfPage)
        assertEquals(1, page1Span.endPdfPage)
        assertEquals("42", page1Span.startPrintedPage)
        assertEquals("42", page1Span.endPrintedPage)
    }

    @Test
    fun `test 3 - multi-column academic paper reorders left column before right column`() {
        val lines = mutableListOf<Triple<String, Float, Pair<Float, Float>>>()
        for (idx in 0 until 6) {
            val y = 0.15f + idx * 0.06f
            lines.add(Triple("LeftColLine_${idx + 1} discusses experimental methodology in detail", y, 0.08f to 0.46f))
            lines.add(Triple("RightColLine_${idx + 1} presents empirical results and conclusion", y, 0.54f to 0.92f))
        }
        val academicPage = buildPageData(pageIndex = 0, lines = lines, printedPageLabel = "105")

        val ebookDoc = PdfToEbookParser.parsePdfToEbookFromPageData(
            rawPages = listOf(academicPage),
            documentTitle = "Neural Architectures",
            documentAuthor = "Dr. Smith"
        )

        val combinedText = ebookDoc.blocks.joinToString(" ") { it.text }
        val lastLeftIndex = combinedText.indexOf("LeftColLine_6")
        val firstRightIndex = combinedText.indexOf("RightColLine_1")
        assertTrue("All left column lines should appear before right column lines in reflowed reading order", lastLeftIndex in 0 until firstRightIndex)
    }

    @Test
    fun `test 4 and 5 - scanned page, complex table, and math equation produce unsupported element blocks without losing content`() {
        // Page 1: Scanned / image-only page (no extractable words)
        val scannedPage = PdfPageTextData(
            pageIndex = 0,
            words = emptyList(),
            paragraphs = emptyList(),
            fullText = "",
            printedPageLabel = ""
        )

        // Page 2: Page containing a multi-column data table and a mathematical equation
        val tableWords = listOf(
            PdfPageWord("Model", 0.10f, 0.30f, 0.22f, 0.32f, 0, 0),
            PdfPageWord("Accuracy", 0.38f, 0.30f, 0.52f, 0.32f, 0, 1),
            PdfPageWord("Latency", 0.68f, 0.30f, 0.82f, 0.32f, 0, 2),
            PdfPageWord("Transformer", 0.10f, 0.35f, 0.25f, 0.37f, 1, 3),
            PdfPageWord("98.4%", 0.38f, 0.35f, 0.48f, 0.37f, 1, 4),
            PdfPageWord("12.3ms", 0.68f, 0.35f, 0.78f, 0.37f, 1, 5),
            PdfPageWord("∑(x_i)", 0.20f, 0.50f, 0.32f, 0.53f, 2, 6),
            PdfPageWord("=", 0.34f, 0.50f, 0.37f, 0.53f, 2, 7),
            PdfPageWord("∫", 0.39f, 0.50f, 0.43f, 0.53f, 2, 8),
            PdfPageWord("f(x)", 0.45f, 0.50f, 0.53f, 0.53f, 2, 9),
            PdfPageWord("≈", 0.55f, 0.50f, 0.59f, 0.53f, 2, 10),
            PdfPageWord("√(2π)", 0.61f, 0.50f, 0.70f, 0.53f, 2, 11),
            PdfPageWord("≠", 0.72f, 0.50f, 0.75f, 0.53f, 2, 12),
            PdfPageWord("0", 0.77f, 0.50f, 0.80f, 0.53f, 2, 13)
        )
        val tableAndMathPage = PdfPageTextData(
            pageIndex = 1,
            words = tableWords,
            paragraphs = listOf("Model Accuracy Latency", "Transformer 98.4% 12.3ms", "∑(x_i) = ∫ f(x) ≈ √(2π) ≠ 0"),
            fullText = "Model Accuracy Latency\nTransformer 98.4% 12.3ms\n∑(x_i) = ∫ f(x) ≈ √(2π) ≠ 0",
            printedPageLabel = "12"
        )

        val ebookDoc = PdfToEbookParser.parsePdfToEbookFromPageData(
            rawPages = listOf(scannedPage, tableAndMathPage),
            documentTitle = "Scientific Handbook",
            documentAuthor = "Prof. Turing"
        )

        val unsupportedBlocks = ebookDoc.blocks.filter { it.type == PdfReflowBlockType.UNSUPPORTED_ELEMENT }
        assertTrue("Should detect unsupported blocks for scanned page, table, and math equation", unsupportedBlocks.size >= 3)
        assertTrue(
            "Should include SCANNED_OR_IMAGE_ONLY_PAGE on page 1",
            unsupportedBlocks.any { it.unsupportedReason == PdfUnsupportedReason.SCANNED_OR_IMAGE_ONLY_PAGE && it.startPdfPage == 1 }
        )
        assertTrue(
            "Should include COMPLEX_TABLE on page 2 with preserved fallback text",
            unsupportedBlocks.any {
                it.unsupportedReason == PdfUnsupportedReason.COMPLEX_TABLE &&
                    it.startPdfPage == 2 &&
                    it.text.contains("Transformer")
            }
        )
        assertTrue(
            "Should include MATH_EQUATION_OR_FORMULA on page 2 with preserved formula text",
            unsupportedBlocks.any {
                it.unsupportedReason == PdfUnsupportedReason.MATH_EQUATION_OR_FORMULA &&
                    it.startPdfPage == 2 &&
                    it.text.contains("∑")
            }
        )

        // Verify pagination maps unsupported blocks to their corresponding PdfEbookReflowPage
        val (reflowPages, _) = PdfToEbookParser.paginatePdfEbook(ebookDoc)
        val totalUnsupportedOnPages = reflowPages.sumOf { page ->
            page.blocksOnPage.count { it.type == PdfReflowBlockType.UNSUPPORTED_ELEMENT }
        }
        assertTrue("Paginated PdfEbookReflowPage list should include the unsupported blocks for visual snapshot rendering", totalUnsupportedOnPages >= 3)
    }
}
