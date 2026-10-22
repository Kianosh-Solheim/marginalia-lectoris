package com.example.reader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubParserTest {

    @Test
    fun parseSmilClockValueMs_parsesAllSupportedClockFormatsAndRejectsInvalid() {
        assertEquals(12_500L, EpubParser.parseSmilClockValueMs("12.5s"))
        assertEquals(500L, EpubParser.parseSmilClockValueMs("500ms"))
        assertEquals(62_500L, EpubParser.parseSmilClockValueMs("1:02.500"))
        assertEquals(62_500L, EpubParser.parseSmilClockValueMs("00:01:02.500"))
        assertNull(EpubParser.parseSmilClockValueMs("abc"))
    }

    @Test
    fun parseEpub_detectsRtlFromSpinePageProgressionDirection() {
        val opfXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:uuid:rtl-test-1</dc:identifier>
                <dc:title>RTL Sample Book</dc:title>
                <dc:language>en</dc:language>
              </metadata>
              <manifest>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine page-progression-direction="rtl">
                <itemref idref="ch1"/>
              </spine>
            </package>
        """.trimIndent()

        val epubBytes = buildMinimalEpubBytes(opfXml)
        val spec = EpubParser.detectEpubSpecification(ByteArrayInputStream(epubBytes))
        assertEquals("rtl", spec.pageProgressionDirection)

        val book = EpubParser.parseEpub(ByteArrayInputStream(epubBytes))
        assertEquals("rtl", book.pageProgressionDirection)
    }

    @Test
    fun parseEpub_detectsFixedLayoutFromRenditionLayoutPrePaginated() {
        val opfXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:uuid:fxl-test-1</dc:identifier>
                <dc:title>Fixed Layout Sample Book</dc:title>
                <dc:language>en</dc:language>
                <meta property="rendition:layout">pre-paginated</meta>
                <meta property="rendition:orientation">landscape</meta>
                <meta property="rendition:spread">both</meta>
              </metadata>
              <manifest>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
              </spine>
            </package>
        """.trimIndent()

        val epubBytes = buildMinimalEpubBytes(opfXml)
        val spec = EpubParser.detectEpubSpecification(ByteArrayInputStream(epubBytes))
        assertTrue(spec.isFixedLayout)

        val book = EpubParser.parseEpub(ByteArrayInputStream(epubBytes))
        assertTrue(book.isFixedLayout)
    }

    @Test
    fun paragraphStyle_preservesIdenticalTextAndCharacterOffsetsUnderAllFourStyles() {
        val rawParagraphText = "In the beginning of the inquiry, character offsets must remain invariant."
        val styles = listOf("INDENT", "SPACED", "BOTH", "NONE")

        val results = styles.map { style ->
            com.example.ui.buildStyledParagraphAnnotatedString(
                displayParagraph = rawParagraphText,
                paragraphStyleSetting = style,
                fontSizeSp = 18f,
                isBodyParagraphBlock = true,
                isFirstAfterHeading = false
            )
        }

        val expectedOffsets = rawParagraphText.indices.toList()
        for (styled in results) {
            assertEquals(rawParagraphText, styled.text)
            assertEquals(rawParagraphText.length, styled.length)
            assertEquals(expectedOffsets, styled.text.indices.toList())
            assertEquals(rawParagraphText.indexOf("inquiry"), styled.text.indexOf("inquiry"))
        }
    }

    private fun buildMinimalEpubBytes(opfXml: String): ByteArray {
        val containerXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val chapterXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head>
                <title>Chapter 1</title>
                <meta name="viewport" content="width=1200, height=1600"/>
              </head>
              <body>
                <p id="p1">First paragraph of the sample chapter.</p>
              </body>
            </html>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(chapterXhtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    @Test
    fun benchmarkLargeEpubAndDaisyLoadPerf() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val numChapters = 30
        val parasPerChapter = 45
        val epubBytes = buildLargeSampleEpubBytes(numChapters, parasPerChapter)

        val t0 = System.nanoTime()
        val book = EpubParser.parseEpub(ByteArrayInputStream(epubBytes))
        val parseMs = (System.nanoTime() - t0) / 1_000_000L

        val t1 = System.nanoTime()
        val (pages, chapters) = EpubParser.paginateBook(book, 18f, 1.4f, 20, 400, 760)
        val paginateMs = (System.nanoTime() - t1) / 1_000_000L

        val citation = EpubParser.resolvePassageCitation(
            epubBook = book,
            quote = "philosophical reflection on the nature of human understanding and time",
            fallbackChapterTitle = "Chapter 5"
        )
        println("BENCHMARK_EPUB: parseMs=${parseMs}ms, paginateMs=${paginateMs}ms, total=${parseMs + paginateMs}ms, pages=${pages.size}, chapters=${chapters.size}, loc=${book.totalStableLocations}, cfi=${citation.epubCfi}, locus=${citation.plainLocus()}")

        assertTrue(pages.size > 50)
        assertTrue(book.totalStableLocations > 50)
        assertEquals("p. 1, Ch. 1 ¶ 1, EPUB loc. 1–2", citation.plainLocus())
        assertEquals("epubcfi(/6/2[ch1]!/4/4[c1_p1],/1:47,/1:117)", citation.epubCfi)

        // Benchmark DAISY zip load
        val tempDaisyZip = java.io.File.createTempFile("large_daisy_perf", ".zip")
        try {
            tempDaisyZip.writeBytes(buildLargeSampleDaisyBytes(20, 30))
            val d0 = System.nanoTime()
            val daisyIdx = DaisyParserEngine.parseDaisyBook(tempDaisyZip, "DAISY Treatise", "Immanuel Kant")
            val daisyParseMs = (System.nanoTime() - d0) / 1_000_000L
            val d1 = System.nanoTime()
            val (daisyPages, _) = EpubParser.paginateBook(daisyIdx.epubBook, 18f, 1.4f, 20, 400, 760)
            val daisyPaginateMs = (System.nanoTime() - d1) / 1_000_000L
            println("BENCHMARK_DAISY: parseMs=${daisyParseMs}ms, paginateMs=${daisyPaginateMs}ms, total=${daisyParseMs + daisyPaginateMs}ms, clips=${daisyIdx.smilClips.size}, pages=${daisyPages.size}")
            assertTrue(daisyIdx.smilClips.size >= 600)
        } finally {
            tempDaisyZip.delete()
        }
    }

    private fun buildLargeSampleDaisyBytes(numSmils: Int, parsPerSmil: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val nccLinks = (1..numSmils).joinToString("\n") { i ->
                """<h1 id="ncc_$i"><a href="smil$i.smil#par_${i}_1">Chapter $i</a></h1>"""
            }
            val nccHtml = """<html><head><meta name="dc:title" content="DAISY Treatise"/><meta name="dc:creator" content="Immanuel Kant"/></head><body>$nccLinks</body></html>"""
            zos.putNextEntry(ZipEntry("ncc.html"))
            zos.write(nccHtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val contentSb = StringBuilder("<html><body>")
            for (i in 1..numSmils) {
                contentSb.append("<h1 id=\"h_$i\">Chapter $i</h1>")
                for (p in 1..parsPerSmil) {
                    contentSb.append("<p id=\"txt_${i}_$p\">Paragraph $p of chapter $i in DAISY full-text synchronization test.</p>")
                }
            }
            contentSb.append("</body></html>")
            zos.putNextEntry(ZipEntry("content.html"))
            zos.write(contentSb.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val dummyMp3 = ByteArray(4 * 1024 * 1024) { (it % 199).toByte() }
            zos.putNextEntry(ZipEntry("audio1.mp3"))
            zos.write(dummyMp3)
            zos.closeEntry()

            for (i in 1..numSmils) {
                val smilSb = StringBuilder("<smil><body><seq>")
                for (p in 1..parsPerSmil) {
                    val b = (p - 1) * 3
                    val e = p * 3
                    smilSb.append("""<par id="par_${i}_$p"><text src="content.html#txt_${i}_$p"/><audio src="audio1.mp3" clip-begin="npt=${b}s" clip-end="npt=${e}s"/></par>""")
                }
                smilSb.append("</seq></body></smil>")
                zos.putNextEntry(ZipEntry("smil$i.smil"))
                zos.write(smilSb.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun buildLargeSampleEpubBytes(numChapters: Int, parasPerChapter: Int): ByteArray {
        val containerXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val manifestItems = (1..numChapters).joinToString("\n") { i ->
            """<item id="ch$i" href="ch$i.xhtml" media-type="application/xhtml+xml"/>"""
        } + "\n" + """<item id="audio1" href="audio/narration1.mp3" media-type="audio/mpeg"/>""" +
            "\n" + """<item id="audio2" href="audio/narration2.mp3" media-type="audio/mpeg"/>"""

        val spineItems = (1..numChapters).joinToString("\n") { i ->
            """<itemref idref="ch$i"/>"""
        }

        val opfXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:uuid:large-perf-book</dc:identifier>
                <dc:title>Comprehensive Treatise on Epistemology</dc:title>
                <dc:creator>Immanuel Kant</dc:creator>
                <dc:language>en</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                $manifestItems
              </manifest>
              <spine>
                $spineItems
              </spine>
            </package>
        """.trimIndent()

        val navLinks = (1..numChapters).joinToString("\n") { i ->
            """<li><a href="ch$i.xhtml">Chapter $i: On Transcendental Logic $i</a></li>"""
        }
        val pageLinks = (1..(numChapters * 3)).joinToString("\n") { p ->
            val ch = ((p - 1) / 3) + 1
            val anchor = "p$p"
            """<li><a href="ch$ch.xhtml#$anchor">$p</a></li>"""
        }
        val navXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc"><ol>$navLinks</ol></nav>
                <nav epub:type="page-list"><ol>$pageLinks</ol></nav>
              </body>
            </html>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
            zos.write(navXhtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Simulate two 8MB MP3 audio tracks inside the archive
            val dummyAudio = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
            zos.putNextEntry(ZipEntry("OEBPS/audio/narration1.mp3"))
            zos.write(dummyAudio)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/audio/narration2.mp3"))
            zos.write(dummyAudio)
            zos.closeEntry()

            for (ch in 1..numChapters) {
                val sb = StringBuilder()
                sb.append("""<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>""")
                sb.append("<h1>Chapter $ch: On Transcendental Logic $ch</h1>")
                for (p in 1..parasPerChapter) {
                    val globalPage = (ch - 1) * 3 + ((p - 1) / 15) + 1
                    val pageSpan = if ((p - 1) % 15 == 0) """<span id="p$globalPage" epub:type="pagebreak" title="$globalPage"/>""" else ""
                    sb.append("""<p id="c${ch}_p$p">$pageSpan Paragraph $p of chapter $ch presents a systematic philosophical reflection on the nature of human understanding and time, examining how synthetic a priori judgments structure empirical experience across manifold representations in consciousness [1].</p>""")
                }
                sb.append("""<aside id="fn1" epub:type="footnote"><p>1. Cf. Critique of Pure Reason, B19.</p></aside>""")
                sb.append("</body></html>")
                zos.putNextEntry(ZipEntry("OEBPS/ch$ch.xhtml"))
                zos.write(sb.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}


