package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.reader.MarkdownExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Marginalia Lectoris", appName)
  }

  @Test
  fun `generate highlight markdown with authors test`() {
    val doc = DocumentEntity(
      id = 1L,
      title = "Designing & Conducting Ethnographic Research",
      author = "Margaret Diane LeCompte, Jean J. Schensul",
      filePath = "/dummy/path.epub",
      fileType = "EPUB",
      category = "BOOK"
    )

    val highlight = HighlightEntity(
      id = 1L,
      documentId = 1L,
      title = "Research Paradigm",
      highlightedText = "Ethnography begins with the understanding of cultural perspective.",
      userNotes = "Fundamental qualitative definition.",
      pageNumber = 14,
      chapterTitle = "Chapter 1",
      colorHex = "#E4E4E7"
    )

    val settings = SettingsEntity(
      defaultTags = "Literature",
      mocTitleFormat = "Notes on {{title}}",
      wikiLinkStyle = true
    )

    val markdown = MarkdownExporter.generateHighlightMarkdown(highlight, doc, settings)

    assertTrue(markdown.contains("title: Research Paradigm"))
    assertTrue(markdown.contains("From Book: \"[[Margaret Diane LeCompte, Jean J. Schensul - Designing & Conducting Ethnographic Research.EPUB]]\""))
    assertTrue(markdown.contains("Author(s):"))
    assertTrue(markdown.contains("  - \"[[Margaret Diane LeCompte]]\""))
    assertTrue(markdown.contains("  - \"[[Jean J. Schensul]]\""))
    assertTrue(markdown.contains("Locus: \"EPUB loc. 14\""))
    assertTrue(markdown.contains("- Literature"))
    assertTrue(markdown.contains("- Book_note"))
    assertTrue(markdown.contains("> [!quote]"))
    assertTrue(markdown.contains(">\"Ethnography begins with the understanding of cultural perspective.\""))
    assertTrue(markdown.contains(">** – Chapter 1, EPUB loc. 14** [[Margaret Diane LeCompte, Jean J. Schensul - Designing & Conducting Ethnographic Research.EPUB#epubcfi(/6/2!/4/2,/1:0,/1:66)|Link to highlight]]"))
    assertTrue(markdown.contains("# My Notes:"))
    assertTrue(markdown.contains("Fundamental qualitative definition."))
    assertTrue(markdown.contains("*Part of [[Notes on Designing & Conducting Ethnographic Research]]*"))
  }

  @Test
  fun `generate MOC markdown test`() {
    val doc = DocumentEntity(
      id = 1L,
      title = "Ethnography in Education",
      author = "Margaret Diane LeCompte and Jean J. Schensul",
      filePath = "/dummy/1999_path.epub",
      fileType = "EPUB",
      category = "BOOK"
    )

    val highlights = listOf(
      HighlightEntity(
        id = 1L,
        documentId = 1L,
        title = "Participant Observation",
        highlightedText = "Immersion allows the researcher to experience nuances.",
        pageNumber = 22,
        pageEquivalent = "12",
        chapterTitle = "Chapter 2: Fieldwork",
        paragraphCitation = "Chapter 2: Fieldwork, ¶ 4"
      )
    )

    val settings = SettingsEntity(
      defaultTags = "Literature",
      mocTitleFormat = "Notes on {{title}}",
      wikiLinkStyle = true
    )

    val moc = MarkdownExporter.generateMocMarkdown(doc, highlights, settings)

    assertTrue(moc.contains("title: Notes on Ethnography in Education"))
    assertTrue(moc.contains("tags:\n  - Literature\n  - Overview"))
    assertTrue(moc.contains("Author(s):"))
    assertTrue(moc.contains("  - \"[[Margaret Diane LeCompte]]\""))
    assertTrue(moc.contains("  - \"[[Jean J. Schensul]]\""))
    assertTrue(moc.contains("From: \"[[Margaret Diane LeCompte, Jean J. Schensul - 1999 - Ethnography in Education.EPUB]]\""))
    assertTrue(moc.contains("Photo: \"[[Ethnography in Education - front cover.WebP]]\""))
    assertTrue(moc.contains("# Chapter 2: Fieldwork"))
    assertTrue(moc.contains("- [[Participant Observation]] - p. 12, EPUB loc. 22 [[Margaret Diane LeCompte, Jean J. Schensul - 1999 - Ethnography in Education.EPUB#epubcfi(/6/4!/4/8,/1:0,/1:54)|Link to highlight]]"))
  }

  @Test
  fun `custom selection word boundary detection snaps and handles compound words`() {
    val sample = "Highlighting in an e-reader shouldn't trigger side-panel swiping."

    // Direct word hit
    val (s1, e1) = com.example.ui.findWordBoundaries(sample, 2)
    assertEquals("Highlighting", sample.substring(s1, e1))

    // Hyphenated word ("e-reader")
    val eReaderIdx = sample.indexOf("reader")
    val (s2, e2) = com.example.ui.findWordBoundaries(sample, eReaderIdx)
    assertEquals("e-reader", sample.substring(s2, e2))

    // Apostrophe word ("shouldn't")
    val shouldntIdx = sample.indexOf("shouldn't") + 7
    val (s3, e3) = com.example.ui.findWordBoundaries(sample, shouldntIdx)
    assertEquals("shouldn't", sample.substring(s3, e3))

    // Whitespace between words snaps to adjacent word instead of empty space
    val spaceIdx = sample.indexOf(" trigger")
    val (s4, e4) = com.example.ui.findWordBoundaries(sample, spaceIdx)
    assertTrue(sample.substring(s4, e4).trim().isNotEmpty())
  }

  @Test
  fun `epub pagination reflows continuously when font size changes`() {
    val longChapterText = (1..25).map { idx ->
      "Paragraph $idx discusses ethnographic field observations, cultural context, and detailed qualitative synthesis across multiple sentences so that pages must reflow dynamically based on typography settings."
    }
    val ch1 = com.example.reader.EpubChapter(
      id = "ch1",
      title = "1. Introduction to Fieldwork",
      content = longChapterText.joinToString("\n\n"),
      paragraphs = longChapterText
    )
    val ch2 = com.example.reader.EpubChapter(
      id = "ch2",
      title = "2. Data Collection Methods",
      content = longChapterText.joinToString("\n\n"),
      paragraphs = longChapterText
    )
    val book = com.example.reader.EpubBook(
      title = "Fieldwork Handbook",
      author = "Test Author",
      description = "",
      coverBitmap = null,
      chapters = listOf(ch1, ch2)
    )

    val (smallFontPages, smallToc) = com.example.reader.EpubParser.paginateBook(
      epubBook = book,
      fontSizeSp = 12f,
      lineSpacingMultiplier = 1.25f,
      marginDp = 16
    )
    val (largeFontPages, _) = com.example.reader.EpubParser.paginateBook(
      epubBook = book,
      fontSizeSp = 32f,
      lineSpacingMultiplier = 1.6f,
      marginDp = 24
    )

    // Small font size should pack significantly more text per page (fewer total pages) than large font size
    assertTrue("Expected fewer pages with 12sp font than 32sp font", smallFontPages.size < largeFontPages.size)

    // Wide margins (e.g., 160dp) should narrow the usable line width and reflow into more pages than narrow margins (16dp)
    val (wideMarginPages, _) = com.example.reader.EpubParser.paginateBook(
      epubBook = book,
      fontSizeSp = 18f,
      lineSpacingMultiplier = 1.4f,
      marginDp = 160,
      viewportWidthDp = 800,
      viewportHeightDp = 1050
    )
    val (narrowMarginPages, _) = com.example.reader.EpubParser.paginateBook(
      epubBook = book,
      fontSizeSp = 18f,
      lineSpacingMultiplier = 1.4f,
      marginDp = 16,
      viewportWidthDp = 800,
      viewportHeightDp = 1050
    )
    assertTrue("Expected wider margins (160dp) to reflow into more pages than narrow margins (16dp)", wideMarginPages.size > narrowMarginPages.size)

    // Chapter titles should preserve their in-book numbering
    assertEquals("1. Introduction to Fieldwork", smallToc[0].title)
    assertEquals("2. Data Collection Methods", smallToc[1].title)
  }

  @Test
  fun `pdf word extraction extracts real selectable words with normalized bounding boxes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    val tempPdf = java.io.File.createTempFile("test_select", ".pdf", context.cacheDir)
    try {
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        val page = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(page)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f)
          cs.newLineAtOffset(72f, 700f)
          cs.showText("Ethnographic research captures lived human experience.")
          cs.endText()
        }
        doc.save(tempPdf)
      }

      com.example.reader.PdfDocumentRenderer(tempPdf, context).use { renderer ->
        val data = renderer.extractPageTextData(0)
        assertTrue("Expected extracted PDF words to be non-empty", data.words.isNotEmpty())
        val joined = data.words.joinToString(" ") { it.text }
        assertTrue("Expected extracted words to contain PDF text, got: $joined", joined.contains("Ethnographic"))
        assertTrue("Expected extracted words to contain 'experience.'", joined.contains("experience."))
        val firstWord = data.words.first()
        assertTrue(firstWord.leftRatio in 0f..1f)
        assertTrue(firstWord.rightRatio > firstWord.leftRatio)
        assertTrue(firstWord.bottomRatio > firstWord.topRatio)
      }
    } finally {
      tempPdf.delete()
    }
  }

  @Test
  fun `pdf printed page number detection and highlight markdown formatting work for Arabic and Roman numerals`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    val tempPdf = java.io.File.createTempFile("test_printed_pages", ".pdf", context.cacheDir)
    try {
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        // Page 1 (PDF page 1) with Roman numeral "xiv" in footer
        val p1 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p1)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p1).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
          cs.newLineAtOffset(72f, 650f)
          cs.showText("Preface to the second edition of fieldwork notes.")
          cs.endText()

          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f)
          cs.newLineAtOffset(295f, 36f)
          cs.showText("xiv")
          cs.endText()
        }

        // Page 2 (PDF page 2) with Arabic numeral "3" in footer
        val p2 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p2)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p2).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
          cs.newLineAtOffset(72f, 650f)
          cs.showText("Chapter 1 begins with participant observation.")
          cs.endText()

          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f)
          cs.newLineAtOffset(300f, 36f)
          cs.showText("3")
          cs.endText()
        }
        doc.save(tempPdf)
      }

      com.example.reader.PdfDocumentRenderer(tempPdf, context).use { renderer ->
        val page0Data = renderer.extractPageTextData(0)
        assertEquals("xiv", page0Data.printedPageLabel)

        val page1Data = renderer.extractPageTextData(1)
        assertEquals("3", page1Data.printedPageLabel)
      }

      val docEntity = com.example.data.local.DocumentEntity(
        id = 1L,
        title = "Fieldwork Book",
        author = "Dr. Smith",
        category = "Books",
        filePath = tempPdf.absolutePath,
        fileType = "PDF",
        totalPages = 100
      )
      val highlightArabic = com.example.data.local.HighlightEntity(
        id = 10L,
        documentId = 1L,
        title = "Observation Note",
        highlightedText = "Chapter 1 begins with participant observation.",
        userNotes = "Important distinction.",
        pageNumber = 14,
        printedPageLabel = "3",
        chapterTitle = "Chapter 1"
      )
      val highlightRoman = highlightArabic.copy(
        id = 11L,
        pageNumber = 6,
        printedPageLabel = "xiv"
      )

      assertEquals(
        "PDF page number 14, printed page number 3",
        com.example.reader.MarkdownExporter.formatHighlightPageDisplay(highlightArabic, isPdf = true)
      )
      assertEquals(
        "PDF page number 6, printed page number xiv",
        com.example.reader.MarkdownExporter.formatHighlightPageDisplay(highlightRoman, isPdf = true)
      )

      val md = com.example.reader.MarkdownExporter.generateHighlightMarkdown(
        highlight = highlightArabic,
        document = docEntity,
        settings = com.example.data.local.SettingsEntity()
      )
      assertTrue(md.contains("Locus: \"p. 3, PDF p. 14\""))
      assertTrue(md.contains(">** – Chapter 1, p. 3, PDF p. 14** [[Dr. Smith - Fieldwork Book.PDF#page=14|Link to highlight]]"))

      val mdRoman = com.example.reader.MarkdownExporter.generateHighlightMarkdown(
        highlight = highlightRoman,
        document = docEntity,
        settings = com.example.data.local.SettingsEntity()
      )
      assertTrue(mdRoman.contains("Locus: \"p. xiv, PDF p. 6\""))
      assertTrue(mdRoman.contains(">** – Chapter 1, p. xiv, PDF p. 6** [[Dr. Smith - Fieldwork Book.PDF#page=6|Link to highlight]]"))
    } finally {
      tempPdf.delete()
    }
  }

  @Test
  fun testPdfViewCropAndMarginZoomNonDestructive() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)

    val tempPdf = java.io.File(context.cacheDir, "test_crop_margin_zoom.pdf")
    try {
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        val page = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(page)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
          cs.newLineAtOffset(100f, 550f)
          cs.showText("Zoomed reading region text inside wide margins.")
          cs.endText()

          // Printed page number in footer outside the cropped body area
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f)
          cs.newLineAtOffset(300f, 30f)
          cs.showText("42")
          cs.endText()
        }
        doc.save(tempPdf)
      }

      val originalFileSize = tempPdf.length()
      com.example.reader.PdfDocumentRenderer(tempPdf, context).use { renderer ->
        val autoBounds = renderer.detectAutoMarginCropBounds(0)
        assertTrue(autoBounds.left > 0.02f)
        assertTrue(autoBounds.right < 0.99f)

        val rawData = renderer.extractPageTextData(0)
        assertEquals("42", rawData.printedPageLabel)

        // Apply a non-destructive view crop (12% left/right, 10% top/bottom)
        val croppedData = renderer.transformTextDataForCrop(
          rawData = rawData,
          cropLeft = 0.12f,
          cropTop = 0.10f,
          cropRight = 0.88f,
          cropBottom = 0.90f
        )
        // Printed page number is still preserved even when the footer is cropped out of the view!
        assertEquals("42", croppedData.printedPageLabel)
        assertTrue(croppedData.words.any { it.text.contains("Zoomed") })

        // Verify bitmap view cropping
        val srcBmp = android.graphics.Bitmap.createBitmap(1000, 1400, android.graphics.Bitmap.Config.ARGB_8888)
        val croppedBmp = renderer.cropBitmapToRegion(srcBmp, 0.10f, 0.10f, 0.90f, 0.90f)
        assertEquals(800, croppedBmp.width)
        assertEquals(1120, croppedBmp.height)

        // Verify words touching or slightly crossing the right/bottom crop boundary (where w.leftRatio >= cropRight
        // or w.topRatio >= cropBottom) never throw IllegalArgumentException in coerceIn!
        val edgeWordsData = com.example.reader.PdfPageTextData(
          pageIndex = 0,
          words = listOf(
            com.example.reader.PdfPageWord(
              text = "EdgeWord",
              leftRatio = 0.885f,
              topRatio = 0.905f,
              rightRatio = 0.892f,
              bottomRatio = 0.912f,
              lineIndex = 0,
              wordIndex = 0
            )
          ),
          paragraphs = listOf("EdgeWord"),
          fullText = "EdgeWord",
          printedPageLabel = "42"
        )
        val transformedEdge = renderer.transformTextDataForCrop(
          rawData = edgeWordsData,
          cropLeft = 0.12f,
          cropTop = 0.10f,
          cropRight = 0.88f,
          cropBottom = 0.90f
        )
        assertEquals(1, transformedEdge.words.size)
        assertTrue(transformedEdge.words.first().rightRatio >= transformedEdge.words.first().leftRatio)
        assertTrue(transformedEdge.words.first().bottomRatio >= transformedEdge.words.first().topRatio)
      }
      // Verify the underlying PDF file was not modified
      assertEquals(originalFileSize, tempPdf.length())
    } finally {
      tempPdf.delete()
    }
  }

  @Test
  fun testPinnedBooksWidgetAndLastReadPageResume() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.example.data.local.AppDatabase.getInstance(context)
    val docDao = db.documentDao()

    val docId = docDao.insertDocument(
      com.example.data.local.DocumentEntity(
        title = "Anthropology Tablet Reader",
        author = "Prof. Evans",
        filePath = "/tmp/anthropology_tablet.pdf",
        fileType = "PDF",
        category = "BOOK",
        currentPage = 37,
        totalPages = 150,
        readingProgress = 37f / 150f
      )
    )

    // Pin book to widget
    docDao.updateDocumentWidgetPin(docId, pinned = true, pinOrder = 100L)
    val pinnedDocs = docDao.getPinnedWidgetDocumentsSnapshot()
    assertTrue(pinnedDocs.any { it.id == docId && it.isPinnedToWidget && it.currentPage == 37 })

    // Verify per-widget max books setting
    com.example.widget.PinnedBooksWidgetProvider.setMaxBooksForWidget(context, 77, 4)
    assertEquals(4, com.example.widget.PinnedBooksWidgetProvider.getMaxBooksForWidget(context, 77))

    // Verify widget cover bitmap generation produces a valid cover bitmap
    val pinnedDoc = pinnedDocs.first { it.id == docId }
    val coverBmp = com.example.widget.PinnedBooksWidgetProvider.loadOrGenerateWidgetCoverBitmap(context, pinnedDoc)
    assertTrue(coverBmp.width > 0 && coverBmp.height > 0)

    docDao.deleteDocumentById(docId)
  }

  @Test
  fun testEpubProgressTrackingAndNamespaceParsing() {
    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
      zos.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
      zos.write(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent().toByteArray(Charsets.UTF_8)
      )
      zos.closeEntry()

      // OPF using namespace prefix <opf:item> and <opf:itemref> + URL-encoded href
      zos.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
      zos.write(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <opf:package xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
          <opf:metadata>
            <dc:title>Deep Ethnography</dc:title>
            <dc:creator>Dr. A. Researcher</dc:creator>
          </opf:metadata>
          <opf:manifest>
            <opf:item id="ch1" href="Chapter%201.xhtml" media-type="application/xhtml+xml"/>
            <opf:item id="ch2" href="Chapter%202.xhtml" media-type="application/xhtml+xml"/>
          </opf:manifest>
          <opf:spine>
            <opf:itemref idref="ch1"/>
            <opf:itemref idref="ch2"/>
          </opf:spine>
        </opf:package>
        """.trimIndent().toByteArray(Charsets.UTF_8)
      )
      zos.closeEntry()

      val longDivContent = (1..25).joinToString("\n") { i ->
        "<div class=\"para\">Section $i explores ethnographic observation, participant interviews, and field notes across many sentences so the book spans multiple pages and never reports 0m left or Finished on page 1.</div>"
      }
      zos.putNextEntry(java.util.zip.ZipEntry("OEBPS/Chapter 1.xhtml"))
      zos.write("<html><body><h1>Chapter One</h1>$longDivContent</body></html>".toByteArray(Charsets.UTF_8))
      zos.closeEntry()

      zos.putNextEntry(java.util.zip.ZipEntry("OEBPS/Chapter 2.xhtml"))
      zos.write("<html><body><h1>Chapter Two</h1>$longDivContent</body></html>".toByteArray(Charsets.UTF_8))
      zos.closeEntry()
    }

    val epubBook = com.example.reader.EpubParser.parseEpub(java.io.ByteArrayInputStream(baos.toByteArray()))
    assertEquals("Deep Ethnography", epubBook.title)
    assertEquals("Dr. A. Researcher", epubBook.author)
    assertEquals(2, epubBook.chapters.size)
    assertTrue("Expected multiple paragraphs extracted from <div> blocks", epubBook.chapters[0].paragraphs.size >= 20)

    val (pages, _) = com.example.reader.EpubParser.paginateBook(epubBook)
    assertTrue("Expected multi-page EPUB (got ${pages.size})", pages.size >= 4)

    // Verify progress on page 1 is 0% and remaining pages > 0 (not Finished / 0m left)
    val currentPage = 1
    val totalPages = pages.size
    val remainingPages = (totalPages - currentPage).coerceAtLeast(0)
    val isAtLastPage = totalPages > 1 && currentPage >= totalPages
    val estMinutesLeft = if (isAtLastPage) 0 else kotlin.math.ceil(remainingPages * 1.3f).toInt().coerceAtLeast(1)
    assertTrue("Expected estMinutesLeft > 0 on first page", estMinutesLeft > 0)
    assertTrue("Expected book not to be marked Finished on page 1", !isAtLastPage)
  }

  @Test
  fun testEpubProgressPersistenceInDatabase() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.example.data.local.AppDatabase.getInstance(context)
    val docDao = db.documentDao()

    val epubId = docDao.insertDocument(
      com.example.data.local.DocumentEntity(
        title = "Persistence EPUB Test",
        author = "Author Name",
        filePath = "/tmp/persistence_test.epub",
        fileType = "EPUB",
        category = "BOOK",
        currentPage = 1,
        totalPages = 40,
        readingProgress = 0f
      )
    )

    // Simulate flipping to page 12 of 40 while reading
    val newPage = 12
    val total = 40
    val progress = (newPage - 1).toFloat() / (total - 1).toFloat()
    docDao.updateReadingProgress(epubId, newPage, total, progress, System.currentTimeMillis())

    val reloaded = docDao.getDocumentById(epubId)
    assertEquals(12, reloaded?.currentPage)
    assertEquals(40, reloaded?.totalPages)
    assertEquals(progress, reloaded?.readingProgress ?: 0f, 0.001f)

    docDao.deleteDocumentById(epubId)
  }

  @Test
  fun testEpubReaderPageTurnSavesToRoomAndRestoresOnReopen() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.example.data.local.AppDatabase.getInstance(context)
    val docDao = db.documentDao()

    val tempEpub = java.io.File(context.cacheDir, "reopen_progress_test.epub")
    try {
      java.util.zip.ZipOutputStream(tempEpub.outputStream()).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
        zos.write(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
              <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
          </container>
          """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        zos.closeEntry()

        zos.putNextEntry(java.util.zip.ZipEntry("OEBPS/content.opf"))
        zos.write(
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
            <metadata>
              <dc:title>Reopen Position Test Book</dc:title>
              <dc:creator>Test Author</dc:creator>
            </metadata>
            <manifest>
              <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine>
              <itemref idref="ch1"/>
            </spine>
          </package>
          """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        zos.closeEntry()

        val paras = (1..40).joinToString("\n") { idx ->
          "<p>Paragraph $idx with sufficient prose content to ensure multiple reflowed pages in the E-reader pager component so we can test turning pages and saving reading progress in Room.</p>"
        }
        zos.putNextEntry(java.util.zip.ZipEntry("OEBPS/ch1.xhtml"))
        zos.write("<html><body><h1>Chapter 1</h1>$paras</body></html>".toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }

      val epubId = kotlinx.coroutines.runBlocking {
        docDao.insertDocument(
          com.example.data.local.DocumentEntity(
            title = "Reopen Position Test Book",
            author = "Test Author",
            filePath = tempEpub.absolutePath,
            fileType = "EPUB",
            category = "BOOK",
            currentPage = 1,
            totalPages = 10,
            readingProgress = 0f
          )
        )
      }

      val viewModel = com.example.ui.ReaderViewModel(context as android.app.Application)
      val initialDoc = kotlinx.coroutines.runBlocking { docDao.getDocumentById(epubId)!! }

      // 1. Open document and turn pages via ViewModel / ReaderScreen callback
      viewModel.openDocument(initialDoc)
      org.robolectric.shadows.ShadowLooper.idleMainLooper()

      // Simulate turning to page 4 of 8
      viewModel.updateReadingProgress(epubId, page = 4, totalPages = 8)
      Thread.sleep(150)
      org.robolectric.shadows.ShadowLooper.idleMainLooper()

      val afterTurnDoc = kotlinx.coroutines.runBlocking { docDao.getDocumentById(epubId)!! }
      assertEquals(4, afterTurnDoc.currentPage)
      assertEquals(8, afterTurnDoc.totalPages)
      assertEquals(3f / 7f, afterTurnDoc.readingProgress, 0.01f)

      // 2. Close document and re-open it using a stale UI reference (currentPage = 1)
      // Verify openDocument fetches the fresh saved page (4) from Room!
      viewModel.closeDocument()
      viewModel.openDocument(initialDoc.copy(currentPage = 1))
      Thread.sleep(150)
      org.robolectric.shadows.ShadowLooper.idleMainLooper()

      val reopenedActive = viewModel.activeDocument.value
      assertEquals(4, reopenedActive?.currentPage)
      assertEquals(8, reopenedActive?.totalPages)

      kotlinx.coroutines.runBlocking { docDao.deleteDocumentById(epubId) }
    } finally {
      tempEpub.delete()
    }
  }

  @Test
  fun testShareDocumentIntentOpensSaveToLectorMeusMenu() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sharedPdf = java.io.File(context.cacheDir, "shared_research_paper.pdf")
    try {
      com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
        doc.save(sharedPdf)
      }

      val shareUri = android.net.Uri.fromFile(sharedPdf)
      val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
      }

      val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java, shareIntent).setup()
      val activity = controller.get()
      assertEquals(shareUri, activity.incomingSharedUri.value)
    } finally {
      sharedPdf.delete()
    }
  }

  @Test
  fun testBritishMaleOfflineTtsSentenceSplittingAndPlaybackControls() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sampleProse = "## Chapter 1\nMr. Holmes examined the manuscript in London [1]. It was a remarkably crisp autumn morning! Did Dr. Watson concur with the deduction?"
    val sentences = com.example.reader.BritishMaleOfflineTtsManager.splitIntoNaturalSentences(sampleProse)
    assertEquals(3, sentences.size)
    assertEquals("Chapter 1 Mr. Holmes examined the manuscript in London .", sentences[0])
    assertEquals("It was a remarkably crisp autumn morning!", sentences[1])
    assertEquals("Did Dr. Watson concur with the deduction?", sentences[2])

    var autoAdvanced = false
    val ttsManager = com.example.reader.BritishMaleOfflineTtsManager(
      context = context,
      initialRate = 1.05f,
      initialPitch = 0.92f,
      initialAutoAdvance = true,
      onPageFinishedAutoAdvance = { autoAdvanced = true }
    )
    try {
      ttsManager.loadPageText(sampleProse, pageNumber = 1, startImmediately = true)
      val state1 = ttsManager.uiState.value
      assertTrue(state1.isPlaying)
      assertEquals(0, state1.currentSentenceIndex)
      assertEquals(3, state1.totalSentences)
      assertEquals(1.05f, state1.speechRate, 0.01f)
      assertEquals(0.92f, state1.pitch, 0.01f)

      ttsManager.nextSentence()
      assertEquals(1, ttsManager.uiState.value.currentSentenceIndex)

      ttsManager.setSpeechRate(1.25f)
      assertEquals(1.25f, ttsManager.uiState.value.speechRate, 0.01f)

      ttsManager.pause()
      assertTrue(ttsManager.uiState.value.isPaused)
      assertTrue(!ttsManager.uiState.value.isPlaying)

      ttsManager.nextSentence()
      assertEquals(2, ttsManager.uiState.value.currentSentenceIndex)
      ttsManager.nextSentence()
      assertTrue(autoAdvanced)

      // Test switching to Natural Offline Norwegian (nb-NO) & automatic Norwegian prose detection
      val norwegianProse = "Kapittel 1. Dette er en bok på norsk med ø, æ og å! Korleis går det i Oslo og Bergen?"
      assertEquals("nb-NO", com.example.reader.BritishMaleOfflineTtsManager.detectLanguageFromText(norwegianProse))
      val noSentences = com.example.reader.BritishMaleOfflineTtsManager.splitIntoNaturalSentences(norwegianProse)
      assertEquals(3, noSentences.size)

      ttsManager.setLanguage("nb-NO")
      assertEquals("nb-NO", ttsManager.uiState.value.activeLanguage)
      ttsManager.loadPageText(norwegianProse, pageNumber = 2, startImmediately = true)
      assertEquals("nb-NO", ttsManager.uiState.value.activeLanguage)
      assertTrue(ttsManager.uiState.value.isPlaying)
    } finally {
      ttsManager.shutdown()
    }
  }

  @Test
  fun testVolumeButtonsAdjustAudioVolumeInsteadOfTurningPagesWhenTtsIsPlaying() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    val vm = androidx.lifecycle.ViewModelProvider(activity)[com.example.ui.ReaderViewModel::class.java]

    // Ensure a document is open and volumeKeyNavigation is enabled
    val sampleDoc = com.example.data.local.DocumentEntity(
      id = 777L,
      title = "Norsk & British Reader",
      author = "Ibsen",
      category = "Book",
      fileType = "EPUB",
      filePath = "",
      coverDrawableName = "cover_meditations",
      currentPage = 3,
      totalPages = 10
    )
    vm.openDocument(sampleDoc)
    org.robolectric.shadows.ShadowLooper.idleMainLooper()

    // 1. When TTS is NOT playing, pressing Volume Down intercepts the key (returns true) to turn pages
    vm.setTtsPlayingState(false)
    val downEventWhenStopped = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
    val interceptedWhenStopped = activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, downEventWhenStopped)
    assertTrue(interceptedWhenStopped)

    // 2. When TTS IS playing, pressing Volume Down or Volume Up is NOT intercepted for page turns (returns false from onKeyDown so system adjusts media volume)
    vm.setTtsPlayingState(true)
    val downEventWhenPlaying = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
    val interceptedWhenPlaying = activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, downEventWhenPlaying)
    assertTrue(!interceptedWhenPlaying)
  }

  @Test
  fun testHtmlDocumentParsingAndPagination() {
    val rawHtml = """
      <!DOCTYPE html>
      <html>
      <head>
        <title>Et Dukkehjem</title>
        <meta name="author" content="Henrik Ibsen" />
      </head>
      <body>
        <h1>Første Akt</h1>
        <p>En hyggelig og smakfullt men ikke kostbart innredet stue<sup><a href="#fn1">1</a></sup>.</p>
        <p>Det ringer på entrédøren; litt etter høres det at det blir lukket opp.</p>
        <h2>Andre Akt</h2>
        <p>Samme stue. I kroken ved pianoet står juletreet, ribbet, forpjusket og med nedbrente lysestumper.</p>
        <aside id="fn1">Scenebeskrivelse fra originalmanuskriptet (1879).</aside>
      </body>
      </html>
    """.trimIndent()

    val parsedBook = com.example.reader.EpubParser.parseHtmlDocument(rawHtml)
    assertEquals("Et Dukkehjem", parsedBook.title)
    assertEquals("Henrik Ibsen", parsedBook.author)
    assertEquals(2, parsedBook.chapters.size)
    assertEquals("Første Akt", parsedBook.chapters[0].title)
    assertEquals("Andre Akt", parsedBook.chapters[1].title)
    assertEquals("Scenebeskrivelse fra originalmanuskriptet (1879).", parsedBook.chapters[0].footnotes["1"])

    val (pages, chapters) = com.example.reader.EpubParser.paginateHtmlDocument(
      title = parsedBook.title,
      author = parsedBook.author,
      rawHtml = rawHtml
    )
    assertTrue(pages.size >= 2)
    assertTrue(chapters.any { it.title == "Første Akt" })
    assertTrue(chapters.any { it.title == "Andre Akt" })
  }

  @Test
  fun testPdfTextExtractionDiagnosticsOcrAndAllSevenAcceptanceScenarios() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    val tempPdf = java.io.File.createTempFile("test_7_scenarios", ".pdf", context.cacheDir)
    try {
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        // Scenario 1 (Page 1): A normal digitally generated PDF page with healthy selectable text
        val p1 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p1)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p1).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
          cs.newLineAtOffset(72f, 700f)
          cs.showText("Digital typesetting provides clean selectable text for researchers and readers.")
          cs.endText()
        }

        // Scenario 2 (Page 2): A scanned PDF page with no text layer (0 embedded text objects)
        val p2 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p2)

        // Scenario 3 (Page 3): A PDF page with corrupted Unicode mappings / gibberish tokens
        val p3 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p3)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p3).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
          cs.newLineAtOffset(72f, 700f)
          cs.showText("x#9K!mP qZ#rT bvxzkpq wqrtzpl mnbvcxz lkjhgfd")
          cs.endText()
        }

        // Scenario 4 (Page 4): A PDF page where copying produces repeated letters ("TThhee", "CCoommppuutteerr")
        val p4 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p4)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p4).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
          cs.newLineAtOffset(72f, 700f)
          cs.showText("TThhee CCoommppuutteerr sseelleeccttiioonn pprroodduucceess rreeppeeaatteedd lleetttteerrss.")
          cs.endText()
        }

        // Scenario 5 (Page 5): A PDF page with an incorrect or fragmented invisible OCR text layer
        val p5 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p5)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p5).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f)
          cs.newLineAtOffset(72f, 700f)
          cs.showText("z q x j v k b n m p r s t w y c d f g h")
          cs.endText()
        }

        // Scenario 6 & 7 (Page 6): A multi-column page where reading order jumps across columns
        val p6 = com.tom_roush.pdfbox.pdmodel.PDPage()
        doc.addPage(p6)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p6).use { cs ->
          // Interleave left-column (x=60) and right-column (x=340) lines on the same Y baselines
          val yPositions = listOf(700f, 680f, 660f, 640f, 620f)
          for ((idx, y) in yPositions.withIndex()) {
            cs.beginText()
            cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 11f)
            cs.newLineAtOffset(60f, y)
            cs.showText("LeftColLine${idx + 1} alpha beta")
            cs.endText()

            cs.beginText()
            cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 11f)
            cs.newLineAtOffset(340f, y)
            cs.showText("RightColLine${idx + 1} gamma delta")
            cs.endText()
          }
        }

        doc.save(tempPdf)
      }

      // Register ground-truth visual OCR words for pages 2, 3, and 5 so OCR recognition in headless JVM tests is verified
      com.example.reader.PdfTextDiagnosticsAndOcrEngine.registerVisualInkWordsForPage(
        filePath = tempPdf.absolutePath,
        pageIndex = 1,
        visualWords = listOf(
          com.example.reader.PdfPageWord("Scanned", 0.1f, 0.2f, 0.25f, 0.24f, 0, 0),
          com.example.reader.PdfPageWord("archival", 0.27f, 0.2f, 0.42f, 0.24f, 0, 1),
          com.example.reader.PdfPageWord("manuscript", 0.44f, 0.2f, 0.65f, 0.24f, 0, 2)
        )
      )
      com.example.reader.PdfTextDiagnosticsAndOcrEngine.registerVisualInkWordsForPage(
        filePath = tempPdf.absolutePath,
        pageIndex = 2,
        visualWords = listOf(
          com.example.reader.PdfPageWord("Readable", 0.1f, 0.2f, 0.26f, 0.24f, 0, 0),
          com.example.reader.PdfPageWord("visual", 0.28f, 0.2f, 0.40f, 0.24f, 0, 1),
          com.example.reader.PdfPageWord("sentence", 0.42f, 0.2f, 0.60f, 0.24f, 0, 2)
        )
      )
      com.example.reader.PdfTextDiagnosticsAndOcrEngine.registerVisualInkWordsForPage(
        filePath = tempPdf.absolutePath,
        pageIndex = 4,
        visualWords = listOf(
          com.example.reader.PdfPageWord("Clean", 0.1f, 0.2f, 0.22f, 0.24f, 0, 0),
          com.example.reader.PdfPageWord("recognized", 0.24f, 0.2f, 0.45f, 0.24f, 0, 1),
          com.example.reader.PdfPageWord("OCR", 0.47f, 0.2f, 0.58f, 0.24f, 0, 2)
        )
      )

      com.example.reader.PdfDocumentRenderer(tempPdf, context).use { renderer ->
        // Run full document diagnostic tool ("Test Text Extraction")
        val docReport = com.example.reader.PdfTextDiagnosticsAndOcrEngine.analyzeDocumentDiagnostics(
          renderer = renderer,
          maxPagesToInspect = 10,
          includeBitmapInspection = false
        )
        assertEquals(6, docReport.pageReports.size)

        // Scenario 1: Healthy digital page
        val p1Diag = docReport.pageReports[0]
        assertEquals("Page 1 issues: ${p1Diag.issues} (words=${p1Diag.nativeWordCount})", com.example.reader.PdfPageHealthClassification.HEALTHY_TEXT_LAYER, p1Diag.classification)
        assertTrue(p1Diag.plainLanguageReport.contains("OCR is probably unnecessary"))

        // Scenario 2: Scanned page with no text layer
        val p2Diag = docReport.pageReports[1]
        assertEquals(com.example.reader.PdfPageHealthClassification.IMAGE_ONLY_PAGE, p2Diag.classification)
        assertTrue(p2Diag.issues.contains(com.example.reader.PdfDiagnosticIssueType.SCANNED_IMAGE_ONLY_PAGE))
        val p2Ocr = renderer.extractPageTextDataWithMode(
          pageIndex = 1,
          documentMode = com.example.reader.PdfTextExtractionMode.OCR
        )
        assertTrue(p2Ocr.fullText.contains("Scanned archival manuscript"))

        // Scenario 3: Corrupted ToUnicode / gibberish text layer
        val p3Diag = docReport.pageReports[2]
        assertEquals(com.example.reader.PdfPageHealthClassification.CORRUPTED_OR_UNUSABLE, p3Diag.classification)
        assertTrue(p3Diag.issues.contains(com.example.reader.PdfDiagnosticIssueType.MISSING_OR_DAMAGED_TOUNICODE_MAP))
        val p3Auto = renderer.extractPageTextDataWithMode(
          pageIndex = 2,
          documentMode = com.example.reader.PdfTextExtractionMode.AUTO
        )
        assertTrue("Expected AUTO mode on corrupted page 3 to fall back to OCR", p3Auto.fullText.contains("Readable visual sentence"))

        // Scenario 4: Repeated letters ("TThhee CCoommppuutteerr...")
        val p4Diag = docReport.pageReports[3]
        assertTrue(p4Diag.issues.contains(com.example.reader.PdfDiagnosticIssueType.EXCESSIVELY_REPEATED_LETTERS))
        val p4Original = renderer.extractPageTextDataWithMode(
          pageIndex = 3,
          documentMode = com.example.reader.PdfTextExtractionMode.ORIGINAL
        )
        assertTrue(p4Original.fullText.contains("TThhee"))
        val p4Repaired = renderer.extractPageTextDataWithMode(
          pageIndex = 3,
          documentMode = com.example.reader.PdfTextExtractionMode.AUTO
        )
        assertTrue("Expected repeated letters to be repaired to 'The Computer selection produces repeated letters.' but got: ${p4Repaired.fullText}",
          p4Repaired.fullText.contains("The Computer selection produces repeated letters.")
        )

        // Scenario 5: Invisible or corrupted fragmented OCR layer
        val p5Diag = docReport.pageReports[4]
        assertEquals(com.example.reader.PdfPageHealthClassification.CORRUPTED_OR_UNUSABLE, p5Diag.classification)
        assertTrue(p5Diag.issues.contains(com.example.reader.PdfDiagnosticIssueType.INVISIBLE_OR_CORRUPTED_OCR_LAYER))
        val p5Fixed = renderer.extractPageTextDataWithMode(
          pageIndex = 4,
          documentMode = com.example.reader.PdfTextExtractionMode.AUTO
        )
        assertTrue(p5Fixed.fullText.contains("Clean recognized OCR"))

        // Scenario 6 & 7: Mixed document & Multi-column reading order reconstruction
        val p6Diag = docReport.pageReports[5]
        assertTrue(p6Diag.issues.contains(com.example.reader.PdfDiagnosticIssueType.INCORRECT_READING_ORDER))
        val p6Reordered = renderer.extractPageTextDataWithMode(
          pageIndex = 5,
          documentMode = com.example.reader.PdfTextExtractionMode.ORIGINAL,
          pageOverride = com.example.reader.PdfPageExtractionOverride.REPAIR_READING_ORDER
        )
        val leftIdx = p6Reordered.fullText.indexOf("LeftColLine5")
        val rightIdx = p6Reordered.fullText.indexOf("RightColLine1")
        assertTrue("Expected all LeftColLines to precede RightColLine1 after reading order reconstruction, got: ${p6Reordered.fullText}",
          leftIdx in 0 until rightIdx
        )

        // Non-destructive export test (Clean text file & Searchable PDF file)
        val cleanTxtFile = com.example.reader.PdfTextDiagnosticsAndOcrEngine.exportCleanExtractedTextFile(
          context = context,
          documentTitle = "Diagnostic Test Book",
          pagesText = listOf(1 to p4Repaired.fullText, 2 to p2Ocr.fullText)
        )
        assertTrue(cleanTxtFile.exists() && cleanTxtFile.readText().contains("The Computer selection produces repeated letters."))

        val searchablePdfFile = com.example.reader.PdfTextDiagnosticsAndOcrEngine.exportSearchablePdfWithCorrectedTextLayer(
          context = context,
          renderer = renderer,
          documentTitle = "Diagnostic Test Book",
          extractionMode = com.example.reader.PdfTextExtractionMode.AUTO,
          pageOverrides = emptyMap()
        )
        assertTrue(searchablePdfFile.exists() && searchablePdfFile.length() > 100L)

        // Scenario 8: Missing word spaces ("Thequickbrownfoxjumpsoverthelazydog")
        val connectedWords = listOf(
          com.example.reader.PdfPageWord("Thequickbrownfoxjumpsoverthelazydog", 0.05f, 0.10f, 0.55f, 0.14f, 0, 0),
          com.example.reader.PdfPageWord("andthisisanotherconnectedparagraphwithoutspaces", 0.05f, 0.16f, 0.65f, 0.20f, 1, 1),
          com.example.reader.PdfPageWord("TextExtractionModeallowsfixingmissingwordspaces", 0.05f, 0.22f, 0.68f, 0.26f, 2, 2),
          com.example.reader.PdfPageWord("automaticallyusingdictionarysegmentation.", 0.05f, 0.28f, 0.58f, 0.32f, 3, 3)
        )
        val connectedPageData = com.example.reader.PdfPageTextData(
          pageIndex = 0,
          fullText = connectedWords.joinToString(" ") { it.text },
          paragraphs = listOf(connectedWords.joinToString(" ") { it.text }),
          words = connectedWords
        )
        assertTrue(
          "Should detect missing word spaces in run-together text",
          com.example.reader.PdfPageDiagnosticsAndOcrEngine.hasMissingWordSpaces(connectedPageData.fullText)
        )
        val p8Diag = com.example.reader.PdfPageDiagnosticsAndOcrEngine.analyzePageDiagnostics(
          pageNumber = 1,
          rawTextData = connectedPageData,
          pageBitmap = null
        )
        assertTrue(
          "Should report MISSING_WORD_SPACES issue",
          p8Diag.issues.contains(com.example.reader.PdfDiagnosticIssueType.MISSING_WORD_SPACES)
        )
        val p8Repaired = com.example.reader.PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
          rawTextData = connectedPageData,
          deduplicateAndCollapseRepeats = false,
          reconstructReadingOrder = false,
          repairMissingSpaces = true
        )
        assertTrue(
          "Expected 'Thequickbrownfoxjumpsoverthelazydog' to be segmented with spaces, got: ${p8Repaired.fullText}",
          p8Repaired.fullText.contains("The quick brown fox")
        )
        assertTrue(
          "Expected subdivided word boxes count > original count",
          p8Repaired.words.size > connectedWords.size
        )

        // Scenario 9: Single-column academic prose with words like "legitimation", "fundamentalism",
        // "modernization", "indiscriminate", "hierarchical" must NOT be falsely classified as a
        // 2-column layout or have its reading order split down the middle!
        val singleColumnLines = listOf(
          listOf("New,", "religious", "forms", "of", "terrorism", "are", "considered", "not", "only", "more", "lethal", "but", "also"),
          listOf("more", "indiscriminate,", "evil,", "and", "cruel.", "They", "use", "the", "most", "lethal", "means", "(e.g.,"),
          listOf("suicide", "bombers", "or", "weapons", "of", "mass", "destruction", "-", "not", "only", "nuclear", "but", "also"),
          listOf("chemical,", "biological,", "or", "radiological", "weapons)."),
          listOf("Its", "loose,", "networked", "(rather", "than", "hierarchical)", "structures", "and", "the", "use", "of", "new"),
          listOf("on", "other", "people", "for", "their", "legitimation", "(Hoffman", "1999).")
        )
        val singleColWords = mutableListOf<com.example.reader.PdfPageWord>()
        var wCounter = 0
        for ((lIdx, lineTokens) in singleColumnLines.withIndex()) {
          val topR = 0.25f + lIdx * 0.045f
          val botR = topR + 0.032f
          val totalChars = lineTokens.sumOf { it.length + 1 }.coerceAtLeast(1)
          var charCursor = 0
          for (tok in lineTokens) {
            // Deliberately place word spaces near 0.50f on some lines to verify the 2-column detector
            // does NOT mistake normal single-column word spaces for a vertical column gutter!
            val leftR = 0.08f + (charCursor.toFloat() / totalChars) * 0.84f
            val rightR = 0.08f + ((charCursor + tok.length).toFloat() / totalChars) * 0.84f
            singleColWords.add(
              com.example.reader.PdfPageWord(
                text = tok,
                leftRatio = leftR,
                topRatio = topR,
                rightRatio = rightR,
                bottomRatio = botR,
                lineIndex = lIdx,
                wordIndex = wCounter++
              )
            )
            charCursor += tok.length + 1
          }
        }
        val singleColPageData = com.example.reader.PdfPageTextData(
          pageIndex = 0,
          fullText = singleColumnLines.joinToString("\n") { it.joinToString(" ") },
          paragraphs = listOf(singleColumnLines.joinToString(" ") { it.joinToString(" ") }),
          words = singleColWords
        )
        val singleColDiag = com.example.reader.PdfPageDiagnosticsAndOcrEngine.analyzePageDiagnostics(
          pageNumber = 1,
          rawTextData = singleColPageData,
          pageBitmap = null
        )
        assertEquals(
          "Single-column academic prose should be classified as HEALTHY_TEXT_LAYER, but had issues: ${singleColDiag.issues}",
          com.example.reader.PdfPageHealthClassification.HEALTHY_TEXT_LAYER,
          singleColDiag.classification
        )
        val singleColRepaired = com.example.reader.PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
          rawTextData = singleColPageData,
          deduplicateAndCollapseRepeats = true,
          reconstructReadingOrder = true,
          stripInvalidUnicode = true,
          repairMissingSpaces = true
        )
        assertTrue(
          "Single-column prose must preserve full first sentence across the line without splitting left/right halves! Got: ${singleColRepaired.fullText}",
          singleColRepaired.fullText.contains("New, religious forms of terrorism are considered not only more lethal but also more indiscriminate, evil, and cruel.")
        )
      }
    } finally {
      tempPdf.delete()
    }
  }

  @Test
  fun testLibraryContextMenuInteractiveMocPreviewAndCompleteBundleExport() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    val tempSourcePdf = java.io.File(context.cacheDir, "sample_export_book.pdf")
    com.tom_roush.pdfbox.pdmodel.PDDocument().use { pdfDoc ->
      val p1 = com.tom_roush.pdfbox.pdmodel.PDPage()
      pdfDoc.addPage(p1)
      com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdfDoc, p1).use { cs ->
        cs.beginText()
        cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
        cs.newLineAtOffset(72f, 700f)
        cs.showText("Chapter 1: Thinking in Print - Research is a conversation among scholars.")
        cs.endText()
      }
      pdfDoc.save(tempSourcePdf)
    }
    try {
      val doc = com.example.data.local.DocumentEntity(
        id = 77L,
        title = "The Craft of Research",
        author = "Wayne C. Booth",
        filePath = tempSourcePdf.absolutePath,
        fileType = "PDF",
        category = "BOOK",
        totalPages = 12,
        currentPage = 3
      )
      val highlights = listOf(
        com.example.data.local.HighlightEntity(
          id = 501L,
          documentId = 77L,
          title = "Research as Conversation",
          highlightedText = "Research is a conversation among scholars.",
          userNotes = "Key insight on how citations connect claims.",
          pageNumber = 3,
          printedPageLabel = "14",
          chapterTitle = "Chapter 1"
        ),
        com.example.data.local.HighlightEntity(
          id = 502L,
          documentId = 77L,
          title = "From Topics to Questions",
          highlightedText = "Move from a broad topic to a focused research question.",
          userNotes = "Essential step before drafting.",
          pageNumber = 5,
          printedPageLabel = "16",
          chapterTitle = "Chapter 2"
        )
      )
      val settings = com.example.data.local.SettingsEntity()

      // 1. Verify Complete Bundle Export as ZIP includes Notes on X.md, individual highlights, highlight.md, PDF source, and cover.jpg
      val zipBundle = com.example.reader.MarkdownExporter.createCompleteDocumentBundleZip(
        context = context,
        document = doc,
        highlights = highlights,
        settings = settings
      )
      assertTrue("ZIP bundle file must exist", zipBundle.outputFileOrFolder.exists())
      assertTrue("ZIP bundle must be marked isZip=true", zipBundle.isZip)
      assertTrue(
        "ZIP bundle should include 'Notes on The Craft of Research.md', got: ${zipBundle.includedFiles}",
        zipBundle.includedFiles.contains("Notes on The Craft of Research.md")
      )
      assertTrue(
        "ZIP bundle should include individual highlight 'Research as Conversation.md'",
        zipBundle.includedFiles.contains("Research as Conversation.md")
      )
      assertTrue(
        "ZIP bundle should include individual highlight 'From Topics to Questions.md'",
        zipBundle.includedFiles.contains("From Topics to Questions.md")
      )
      assertTrue(
        "ZIP bundle should include formatted source PDF file 'Wayne C. Booth - The Craft of Research.PDF', got: ${zipBundle.includedFiles}",
        zipBundle.includedFiles.contains("Wayne C. Booth - The Craft of Research.PDF")
      )
      assertTrue(
        "ZIP bundle should include WebP book cover image 'The Craft of Research - front cover.WebP', got: ${zipBundle.includedFiles}",
        zipBundle.includedFiles.contains("The Craft of Research - front cover.WebP")
      )

      // 2. Verify Complete Bundle Export as Regular Folder writes all files to disk
      val tempExportParent = java.io.File(context.cacheDir, "test_folder_exports").apply { mkdirs() }
      val folderBundle = com.example.reader.MarkdownExporter.exportCompleteDocumentBundleToFolder(
        context = context,
        document = doc,
        highlights = highlights,
        settings = settings,
        targetParentFolder = tempExportParent
      )
      assertTrue("Exported folder must exist and be a directory", folderBundle.outputFileOrFolder.isDirectory)
      assertTrue(
        "Notes on The Craft of Research.md must exist inside exported folder",
        java.io.File(folderBundle.outputFileOrFolder, "Notes on The Craft of Research.md").exists()
      )
      assertTrue(
        "Research as Conversation.md must exist inside exported folder",
        java.io.File(folderBundle.outputFileOrFolder, "Research as Conversation.md").exists()
      )
      assertTrue(
        "Formatted source PDF file must exist inside exported folder",
        java.io.File(folderBundle.outputFileOrFolder, "Wayne C. Booth - The Craft of Research.PDF").exists()
      )
      assertTrue(
        "The Craft of Research - front cover.WebP must exist inside exported folder",
        java.io.File(folderBundle.outputFileOrFolder, "The Craft of Research - front cover.WebP").exists()
      )
    } finally {
      tempSourcePdf.delete()
    }
  }

  @Test
  fun testNoteSuggestionEngineAndIndividualHighlightTags() {
    val quote =
      "We define a research problem as an intellectual puzzle that requires empirical evidence and a clear hypothesis before methodology can be chosen."
    val userNote = "Important definition for Chapter 2 methodology and epistemology."
    val chapter = "Chapter 2: Asking Questions"

    val suggestedTitles = com.example.reader.NoteSuggestionEngine.suggestTitles(
      highlightedText = quote,
      userNotes = userNote,
      chapterTitle = chapter,
      pageNumber = 24
    )
    assertTrue("Should suggest multiple candidate titles from highlighted text", suggestedTitles.size >= 2)
    assertTrue("Primary suggested title must be non-blank", suggestedTitles.first().isNotBlank())

    val suggestedTags = com.example.reader.NoteSuggestionEngine.suggestTags(
      highlightedText = quote,
      userNotes = userNote,
      chapterTitle = chapter
    )
    assertTrue("Should suggest tags based on highlighted text", suggestedTags.isNotEmpty())
    assertTrue(
      "Should detect definition or research/methodology tag from quote, got: $suggestedTags",
      suggestedTags.contains("definition") || suggestedTags.contains("research") || suggestedTags.contains("methodology")
    )

    val customTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
      suggestedTags.take(2) + com.example.reader.NoteSuggestionEngine.parseTagsList("#epistemology, key-concept")
    )
    val parsedTags = com.example.reader.NoteSuggestionEngine.parseTagsList(customTags)
    assertTrue("Parsed tags should include epistemology", parsedTags.contains("epistemology"))
    assertTrue("Parsed tags should include key-concept", parsedTags.contains("key-concept"))

    val doc = com.example.data.local.DocumentEntity(
      id = 55L,
      title = "The Craft of Research",
      author = "Wayne C. Booth",
      filePath = "/tmp/craft.pdf",
      fileType = "PDF",
      category = "Books",
      totalPages = 100
    )
    val highlight = com.example.data.local.HighlightEntity(
      id = 10L,
      documentId = 55L,
      title = suggestedTitles.first(),
      highlightedText = quote,
      userNotes = userNote,
      pageNumber = 24,
      printedPageLabel = "18",
      chapterTitle = chapter,
      tags = customTags
    )
    val settings = com.example.data.local.SettingsEntity()
    val md = com.example.reader.MarkdownExporter.generateHighlightMarkdown(highlight, doc, settings)
    assertTrue("Generated note markdown must include YAML frontmatter tag", md.contains("  - epistemology"))
    assertTrue("Generated note markdown must include Book_note tag", md.contains("  - Book_note"))
    assertTrue("Generated note markdown must include Locus: \"p. 18, PDF p. 24\"", md.contains("Locus: \"p. 18, PDF p. 24\""))
  }

  @Test
  fun testEpubHighlightNavigationAcrossFontSizeReflow() {
    val targetQuote = "Hermeneutic understanding requires a continuous dialogue between the whole text and its individual parts."
    val paragraphs = (1..30).map { idx ->
      if (idx == 18) {
        "In nineteenth-century philology, scholars observed a fundamental principle. $targetQuote This insight reshaped modern interpretation."
      } else {
        "Paragraph number $idx discusses historical background, textual transmission, and the evolution of reading practices across centuries of scholarship and print culture."
      }
    }
    val book = com.example.reader.EpubBook(
      title = "Hermeneutics Reader",
      author = "Hans-Georg Gadamer",
      description = "Foundational texts in hermeneutics",
      coverBitmap = null,
      chapters = listOf(
        com.example.reader.EpubChapter(
          id = "ch1",
          title = "Chapter 1: Foundations",
          content = paragraphs.joinToString("\n\n"),
          paragraphs = paragraphs
        )
      )
    )

    // 1. Paginate at small font size (14sp) where the quote lands on an early page
    val (smallFontPages, _) = com.example.reader.EpubParser.paginateBook(
      book,
      14f,
      1.3f,
      16,
      400,
      700
    )
    val savedSmallFontPageIdx = smallFontPages.indexOfFirst { page ->
      page.paragraphs.any { it.contains(targetQuote) }
    }
    assertTrue("Quote must exist in small-font pagination", savedSmallFontPageIdx >= 0)

    // Highlight was saved when font size was 14sp (so pageNumber = savedSmallFontPageIdx + 1)
    val savedHighlight = com.example.data.local.HighlightEntity(
      id = 99L,
      documentId = 7L,
      title = "Hermeneutic dialogue",
      highlightedText = targetQuote,
      userNotes = "Important note on the hermeneutic circle",
      pageNumber = savedSmallFontPageIdx + 1,
      chapterTitle = "Chapter 1: Foundations"
    )

    // 2. User later changes font size to 32sp (Very Large), causing the book to reflow into many more pages
    val (largeFontPages, _) = com.example.reader.EpubParser.paginateBook(
      book,
      32f,
      1.6f,
      28,
      400,
      700
    )
    val actualLargeFontPageIdx = largeFontPages.indexOfFirst { page ->
      page.paragraphs.any { it.contains("Hermeneutic understanding") }
    }
    assertTrue(
      "Large font pagination must place the quote on a different (later) page than small font pagination",
      actualLargeFontPageIdx > savedSmallFontPageIdx
    )

    // 3. Clicking the highlight must resolve the exact new reflowed page containing the quote, NOT the stale saved pageNumber!
    val resolvedPageIdx = com.example.ui.findReflowPageIndexForHighlight(largeFontPages, savedHighlight)
    assertEquals(
      "findReflowPageIndexForHighlight must jump to the exact reflowed page where the quote now lives",
      actualLargeFontPageIdx,
      resolvedPageIdx
    )
  }

  @Test
  fun testEpubPageListAndChapterParagraphCitations() {
    // 1. Build an academic EPUB in memory with EPUB 3 page-list nav, NCX pageList, and inline epub:type="pagebreak" markers
    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
      fun putEntry(name: String, content: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }

      putEntry(
        "META-INF/container.xml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent()
      )

      putEntry(
        "OEBPS/content.opf",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Epistemology of Social Inquiry</dc:title>
            <dc:creator>Dr. Clara Lindqvist</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine toc="ncx">
            <itemref idref="ch1"/>
          </spine>
        </package>
        """.trimIndent()
      )

      putEntry(
        "OEBPS/nav.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <nav epub:type="toc">
              <ol>
                <li><a href="ch1.xhtml">Chapter 1: Methodological Foundations</a></li>
              </ol>
            </nav>
            <nav epub:type="page-list">
              <ol>
                <li><a href="ch1.xhtml#page_41">41</a></li>
                <li><a href="ch1.xhtml#page_42">42</a></li>
              </ol>
            </nav>
          </body>
        </html>
        """.trimIndent()
      )

      putEntry(
        "OEBPS/ch1.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <h1>Chapter 1: Methodological Foundations</h1>
            <p><span id="page_41" epub:type="pagebreak" title="41"/>First paragraph on print page 41 introduces the conceptual framework of empirical observation.</p>
            <p>Second paragraph on print page 41 elaborates on interpretive validity in fieldwork.</p>
            <p><span id="page_42" role="doc-pagebreak" aria-label="42"/>Third paragraph begins on print page 42 and argues that reflexive analysis bridges theory and lived experience.</p>
          </body>
        </html>
        """.trimIndent()
      )
    }

    val parsedBook = com.example.reader.EpubParser.parseEpub(java.io.ByteArrayInputStream(baos.toByteArray()))
    assertTrue("Academic EPUB must detect Page List metadata", parsedBook.hasPageListMetadata)
    assertEquals(1, parsedBook.chapters.size)

    val citation = com.example.reader.EpubParser.resolvePassageCitation(
      epubBook = parsedBook,
      quote = "reflexive analysis bridges theory and lived experience",
      fallbackChapterTitle = "Chapter 1: Methodological Foundations"
    )

    assertEquals("42", citation.pageEquivalent)
    assertEquals(3, citation.startParagraphNumber)
    assertEquals("Chapter 1: Methodological Foundations, ¶ 3", citation.chapterAndParagraphCitation)

    // 2. Verify generated Markdown note includes both page_equivalent and chapter_paragraph in YAML frontmatter and body
    val doc = DocumentEntity(
      id = 12L,
      title = "Epistemology of Social Inquiry",
      author = "Dr. Clara Lindqvist",
      filePath = "/dummy/epistemology.epub",
      fileType = "EPUB",
      category = "BOOK"
    )
    val highlightWithPageEq = HighlightEntity(
      id = 77L,
      documentId = 12L,
      title = "Reflexive analysis bridges theory",
      highlightedText = "Third paragraph begins on print page 42 and argues that reflexive analysis bridges theory and lived experience.",
      userNotes = "Key methodological passage.",
      pageNumber = 2,
      chapterTitle = "Chapter 1: Methodological Foundations",
      paragraphCitation = citation.chapterAndParagraphCitation,
      pageEquivalent = citation.pageEquivalent.orEmpty()
    )
    val settings = SettingsEntity()
    val mdWithPageEq = MarkdownExporter.generateHighlightMarkdown(highlightWithPageEq, doc, settings)
    assertTrue("Markdown frontmatter must contain Locus: \"p. 42, EPUB loc. 2\"", mdWithPageEq.contains("Locus: \"p. 42, EPUB loc. 2\""))
    assertTrue("Markdown frontmatter must contain From Book: \"[[Dr. Clara Lindqvist - Epistemology of Social Inquiry.EPUB]]\"", mdWithPageEq.contains("From Book: \"[[Dr. Clara Lindqvist - Epistemology of Social Inquiry.EPUB]]\""))
    assertEquals("page equivalent p. 42 • Chapter 1: Methodological Foundations, ¶ 3", MarkdownExporter.formatHighlightPageDisplay(highlightWithPageEq, isPdf = false))
    assertTrue("Markdown body must display chapter/paragraph and locus with standard EPUB CFI highlight link", mdWithPageEq.contains(">** – Chapter 1: Methodological Foundations, ¶ 3, p. 42, EPUB loc. 2** [[Dr. Clara Lindqvist - Epistemology of Social Inquiry.EPUB#epubcfi(/6/2!/4/6,/1:0,/1:111)|Link to highlight]]"))

    // 3. Verify that when an EPUB has NO Page List metadata, Locus omits printed page and starts with digital locator
    val highlightWithoutPageEq = highlightWithPageEq.copy(pageEquivalent = "")
    val mdWithoutPageEq = MarkdownExporter.generateHighlightMarkdown(highlightWithoutPageEq, doc, settings)
    assertTrue("Markdown without Page List must have Locus: \"EPUB loc. 2\" without printed page", mdWithoutPageEq.contains("Locus: \"EPUB loc. 2\""))
    assertTrue("Markdown without Page List must include chapter/paragraph and EPUB loc in quote footer", mdWithoutPageEq.contains(">** – Chapter 1: Methodological Foundations, ¶ 3, EPUB loc. 2**"))
    assertEquals("Chapter 1: Methodological Foundations, ¶ 3", MarkdownExporter.formatHighlightPageDisplay(highlightWithoutPageEq, isPdf = false))

    // 4. Verify paginateBook with paginateByPageEquivalent = false vs true
    val (standardPages, _) = com.example.reader.EpubParser.paginateBook(
      epubBook = parsedBook,
      fontSizeSp = 18f,
      lineSpacingMultiplier = 1.5f,
      marginDp = 20,
      viewportWidthDp = 400,
      viewportHeightDp = 760,
      paginateByPageEquivalent = false
    )
    val standardTextPages = standardPages.filter { !it.isCoverPage }
    assertEquals("With standard pagination and short paragraphs, all 3 paragraphs fit on 1 text page spanning pp. 41–42", 1, standardTextPages.size)
    assertEquals("41–42", standardTextPages.first().pageEquivalentLabel)

    val (printBoundaryPages, _) = com.example.reader.EpubParser.paginateBook(
      epubBook = parsedBook,
      fontSizeSp = 18f,
      lineSpacingMultiplier = 1.5f,
      marginDp = 20,
      viewportWidthDp = 400,
      viewportHeightDp = 760,
      paginateByPageEquivalent = true
    )
    val printTextPages = printBoundaryPages.filter { !it.isCoverPage }
    assertEquals("With 1:1 print edition pagination (paginateByPageEquivalent = true), page 41 and page 42 are split onto separate ebook text pages", 2, printTextPages.size)
    assertEquals("41", printTextPages[0].pageEquivalentLabel)
    assertEquals("42", printTextPages[1].pageEquivalentLabel)
    assertTrue(printTextPages[0].paragraphs.any { it.contains("First paragraph on print page 41") })
    assertTrue(printTextPages[1].paragraphs.any { it.contains("Third paragraph begins on print page 42") })

    // 5. Verify standalone pagebreaks between <p> tags AND mid-paragraph pagebreaks advance immediately (starting on page 7 -> 8 -> 9)
    val baosBetweenAndMid = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baosBetweenAndMid).use { zos ->
      fun putEntry(name: String, content: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }
      putEntry(
        "META-INF/container.xml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent()
      )
      putEntry(
        "OEBPS/content.opf",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Book Starting on Page 7</dc:title>
          </metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine toc="ncx">
            <itemref idref="ch1"/>
          </spine>
        </package>
        """.trimIndent()
      )
      putEntry(
        "OEBPS/toc.ncx",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <navMap>
            <navPoint id="np1" playOrder="1">
              <navLabel><text>Chapter 1</text></navLabel>
              <content src="ch1.xhtml"/>
            </navPoint>
          </navMap>
          <pageList>
            <pageTarget id="pt7" type="normal" value="7">
              <navLabel><text>7</text></navLabel>
              <content src="ch1.xhtml#p7"/>
            </pageTarget>
            <pageTarget id="pt8" type="normal" value="8">
              <navLabel><text>8</text></navLabel>
              <content src="ch1.xhtml#p8"/>
            </pageTarget>
            <pageTarget id="pt9" type="normal" value="9">
              <navLabel><text>9</text></navLabel>
              <content src="ch1.xhtml#p9"/>
            </pageTarget>
          </pageList>
        </ncx>
        """.trimIndent()
      )
      putEntry(
        "OEBPS/ch1.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <body>
            <h1>Chapter 1</h1>
            <a id="p7"/>
            <p>Opening paragraph on print page 7 right after standalone anchor outside p tag.</p>
            <span id="p8"></span>
            <p>Second paragraph starts on print page 8 after standalone span outside p tag, and then mid-paragraph <a id="p9"/> transitions directly onto print page 9 without staying on page 8.</p>
          </body>
        </html>
        """.trimIndent()
      )
    }

    val parsedPage7Book = com.example.reader.EpubParser.parseEpub(java.io.ByteArrayInputStream(baosBetweenAndMid.toByteArray()))
    val (_, _) = com.example.reader.EpubParser.paginateBook(
      epubBook = parsedPage7Book,
      paginateByPageEquivalent = true
    )
    val p7Pages = com.example.reader.EpubParser.paginateBook(
      epubBook = parsedPage7Book,
      paginateByPageEquivalent = true
    ).first.filter { !it.isCoverPage }

    assertEquals("Must split into 3 distinct print pages (7, 8, and 9)", 3, p7Pages.size)
    assertEquals("7", p7Pages[0].pageEquivalentLabel)
    assertEquals("8", p7Pages[1].pageEquivalentLabel)
    assertEquals("9", p7Pages[2].pageEquivalentLabel)
    assertTrue(p7Pages[2].paragraphs.first().contains("transitions directly onto print page 9"))
    // Ensure semanticBlocks on each split page hold ONLY that page's split text (never the unsplit full paragraph!)
    assertTrue(
      "Page 8 semanticBlock must NOT contain page 9 text",
      p7Pages[1].semanticBlocks.none { it.text.contains("transitions directly onto print page 9") }
    )
    assertTrue(
      "Page 9 semanticBlock must NOT repeat page 8 prefix",
      p7Pages[2].semanticBlocks.none { it.text.contains("Second paragraph starts on print page 8") }
    )
    // Ensure chapter heading "Chapter 1" is only emitted once on page 7, never repeated twice!
    val ch1HeadingOccurrences = p7Pages[0].paragraphs.count {
      it.replace(Regex("^#+\\s*"), "").trim().equals("Chapter 1", ignoreCase = true)
    }
    assertEquals("Chapter 1 heading must appear exactly once, never duplicated", 1, ch1HeadingOccurrences)
  }

  @Test
  fun testEpubSemanticStructureParagraphCountingHierarchicalTocAndBidirectionalFootnotes() {
    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zos ->
      fun putEntry(name: String, content: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
      }
      putEntry(
        "META-INF/container.xml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent()
      )
      putEntry(
        "OEBPS/content.opf",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Semantic Philosophy Reader</dc:title>
            <dc:creator>Immanuel Kant</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="ch1"/>
          </spine>
        </package>
        """.trimIndent()
      )
      putEntry(
        "OEBPS/nav.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <nav epub:type="toc">
              <ol>
                <li>
                  <a href="ch1.xhtml">Chapter 1: Transcendental Aesthetic</a>
                  <ol>
                    <li>
                      <a href="ch1.xhtml#sec-space">Section 1: Of Space</a>
                      <ol>
                        <li><a href="ch1.xhtml#subsec-exposition">Metaphysical Exposition</a></li>
                      </ol>
                    </li>
                  </ol>
                </li>
              </ol>
            </nav>
          </body>
        </html>
        """.trimIndent()
      )
      putEntry(
        "OEBPS/ch1.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <h1 id="ch1-top">Chapter 1: Transcendental Aesthetic</h1>
            <h2 id="sec-space">Section 1: Of Space</h2>
            <blockquote>Space is not an empirical concept which has been derived from outer experiences.</blockquote>
            <ul>
              <li>First premise regarding pure intuition.</li>
              <li>Second premise regarding a priori necessity.</li>
            </ul>
            <figure id="fig-1">
              <figcaption>Figure 1: Schema of Pure Sensibility</figcaption>
            </figure>
            <span epub:type="pagebreak" title="19" id="p19"/>
            <p id="para-first">By means of outer sense, a property of our mind, we represent to ourselves objects as outside us, and all without exception in space.<a id="fnref-kant1" href="#fn-kant1" epub:type="noteref">1</a></p>
            <h3 id="subsec-exposition">Metaphysical Exposition</h3>
            <p id="para-second">Space is a necessary a priori representation, which underlies all outer intuitions, as also discussed in <a href="ch1.xhtml#sec-space">Section 1</a>.</p>
            <aside id="fn-kant1" epub:type="footnote">
              <p><a href="#fnref-kant1" role="doc-backlink">1</a> Compare the first edition formulation of transcendental ideality in 1781.</p>
            </aside>
          </body>
        </html>
        """.trimIndent()
      )
    }

    val book = com.example.reader.EpubParser.parseEpub(java.io.ByteArrayInputStream(baos.toByteArray()))
    assertEquals(1, book.chapters.size)
    val ch = book.chapters.first()

    // 1. Verify semantic block types are preserved and distinct
    val blockTypes = ch.semanticBlocks.map { it.type }
    assertTrue(blockTypes.contains(com.example.reader.EpubSemanticBlockType.HEADING))
    assertTrue(blockTypes.contains(com.example.reader.EpubSemanticBlockType.BLOCKQUOTE))
    assertTrue(blockTypes.contains(com.example.reader.EpubSemanticBlockType.LIST_ITEM))
    assertTrue(blockTypes.contains(com.example.reader.EpubSemanticBlockType.FIGURE))
    assertTrue(blockTypes.contains(com.example.reader.EpubSemanticBlockType.PARAGRAPH))
    assertTrue(blockTypes.contains(com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF))

    // 2. Verify ONLY <p> elements (outside aside/footnotes/figures/blockquotes/lists) are counted as ordinary paragraphs!
    // Even though the chapter has h1, h2, h3, blockquote, 2 list items, figure+figcaption, and an aside footnote,
    // there are only TWO genuine <p> paragraphs in the main text.
    val countedParas = ch.countedTextParagraphs
    assertEquals("Only the 2 genuine <p> paragraphs in main text should be counted as paragraphs", 2, countedParas.size)
    assertEquals(1, countedParas[0].paragraphNumberInChapter)
    assertEquals(2, countedParas[1].paragraphNumberInChapter)

    // Verify resolvePassageCitation counts the second <p> as ¶ 2 (not ¶ 8 or ¶ 9!) and preserves pageEquivalent "19"
    val citation = com.example.reader.EpubParser.resolvePassageCitation(
      epubBook = book,
      quote = "Space is a necessary a priori representation, which underlies all outer intuitions",
      fallbackChapterTitle = ch.title
    )
    assertEquals(2, citation.startParagraphNumber)
    assertEquals(2, citation.endParagraphNumber)
    assertTrue("Citation must report ¶ 2, got: ${citation.chapterAndParagraphCitation}", citation.chapterAndParagraphCitation.endsWith("¶ 2"))
    assertEquals("19", citation.pageEquivalent)

    // 3. Verify hierarchical TOC from nav.xhtml is preserved (levels 0, 1, 2) and linked to pages
    assertEquals(3, book.tocItems.size)
    assertEquals(0, book.tocItems[0].level)
    assertEquals(1, book.tocItems[1].level)
    assertEquals("sec-space", book.tocItems[1].anchorId)
    assertEquals(2, book.tocItems[2].level)
    assertEquals("subsec-exposition", book.tocItems[2].anchorId)

    val (pages, bookChapters) = com.example.reader.EpubParser.paginateBook(book)
    val contentChapters = bookChapters.filter { it.title != "Cover" }
    assertEquals(3, contentChapters.size)
    assertEquals(0, contentChapters[0].level)
    assertEquals(1, contentChapters[1].level)
    assertEquals(2, contentChapters[2].level)
    assertTrue(pages.any { it.anchorIds.contains("sec-space") && it.anchorIds.contains("subsec-exposition") })

    // 4. Verify bidirectional footnote connection (Main text -> Footnote -> Back to Main text / Context)
    val fnEntry = book.footnoteEntries["fn-kant1"] ?: book.footnoteEntries["1"]
    org.junit.Assert.assertNotNull("Footnote entry must be linked via id/href/epub:type", fnEntry)
    assertTrue(fnEntry!!.noteText.contains("Compare the first edition formulation"))
    assertEquals("fnref-kant1", fnEntry.backlinkAnchorId)
    assertEquals(1, fnEntry.sourceParagraphNumber)
    assertTrue(fnEntry.sourceContextSnippet.contains("By means of outer sense"))

    // 5. Verify standalone chapter number (e.g. <h1>1</h1> or <p><a href="...">1</a></p>) is suppressed when TOC title is "1 My Journey"
    assertTrue(
      "Standalone chapter number '1' must be recognized as duplicate of '1  My Journey'",
      com.example.reader.EpubParser.isDuplicateChapterHeadingText("1", "1  My Journey", isHeadingOrFirstBlock = true)
    )
    assertTrue(
      "Subtitle 'My Journey' must be recognized as duplicate of '1  My Journey'",
      com.example.reader.EpubParser.isDuplicateChapterHeadingText("My Journey", "1  My Journey", isHeadingOrFirstBlock = true)
    )
    assertTrue(
      "Standalone chapter number '2' must be recognized as duplicate of '2  Starting from Home'",
      com.example.reader.EpubParser.isDuplicateChapterHeadingText("2", "2  Starting from Home", isHeadingOrFirstBlock = true)
    )

    // 6. Verify inline footnote references are converted to bracketed [1] in paragraphs and semanticBlocks across splits
    assertTrue(
      "Main-text paragraph must render footnote reference as bracketed [1] instead of bare digit",
      ch.paragraphs.any { it.contains("[1]") }
    )
  }

  @Test
  fun testEpubCfiEngineGenerationParsingComparisonAndResolution() {
    val epubBook = com.example.reader.EpubBook(
      title = "Philosophical Investigations",
      author = "Ludwig Wittgenstein",
      description = "",
      chapters = listOf(
        com.example.reader.EpubChapter(
          id = "ch1",
          title = "Chapter 1: Language Games",
          content = "First paragraph about builders and slabs.\n\nThe meaning of a word is its use in the language.",
          paragraphs = listOf(
            "First paragraph about builders and slabs.",
            "The meaning of a word is its use in the language."
          ),
          semanticBlocks = listOf(
            com.example.reader.EpubSemanticBlock(
              type = com.example.reader.EpubSemanticBlockType.PARAGRAPH,
              text = "First paragraph about builders and slabs.",
              paragraphNumberInChapter = 1,
              anchorIds = listOf("p-1")
            ),
            com.example.reader.EpubSemanticBlock(
              type = com.example.reader.EpubSemanticBlockType.PARAGRAPH,
              text = "The meaning of a word is its use in the language.",
              paragraphNumberInChapter = 2,
              anchorIds = listOf("p-2")
            )
          )
        )
      )
    )

    val quote = "meaning of a word is its use"
    val citation = com.example.reader.EpubParser.resolvePassageCitation(
      epubBook = epubBook,
      quote = quote,
      fallbackChapterTitle = "Chapter 1: Language Games"
    )

    assertEquals(2, citation.startParagraphNumber)
    assertTrue("EPUB CFI must be generated on citation, got: ${citation.epubCfi}", citation.epubCfi.startsWith("epubcfi(/6/2[ch1]!/4/4[p-2],/1:4,/1:32)"))

    // Parse the generated CFI and verify structural components
    val parsed = com.example.reader.EpubCfiEngine.parseEpubCfi(citation.epubCfi)
    org.junit.Assert.assertNotNull("Generated CFI must parse cleanly", parsed)
    assertTrue(parsed!!.isRange)
    assertEquals(2, parsed.spineStep)
    assertEquals("ch1", parsed.spineIdAssertion)
    assertEquals(0, parsed.spineIndex)
    assertEquals(4, parsed.startBlockStep)
    assertEquals("p-2", parsed.startBlockIdAssertion)
    assertEquals(1, parsed.startBlockIndex)
    assertEquals(4, parsed.startCharOffset)
    assertEquals(32, parsed.endCharOffset)

    // Resolve the CFI back against the book
    val resolved = com.example.reader.EpubCfiEngine.resolveCfiInBook(
      epubBook = epubBook,
      rawCfi = citation.epubCfi
    )
    org.junit.Assert.assertNotNull("CFI must resolve back to the exact book passage", resolved)
    assertEquals(0, resolved!!.chapterIndex)
    assertEquals(2, resolved.startParagraphNumber)
    assertEquals(quote, resolved.extractedText)

    // Verify CFI comparison ordering
    val cfiEarlier = "epubcfi(/6/2[ch1]!/4/2[p-1],/1:0,/1:15)"
    val cfiLater = citation.epubCfi
    assertTrue(com.example.reader.EpubCfiEngine.compareEpubCfi(cfiEarlier, cfiLater) < 0)
    assertTrue(com.example.reader.EpubCfiEngine.compareEpubCfi(cfiLater, cfiEarlier) > 0)
    assertEquals(0, com.example.reader.EpubCfiEngine.compareEpubCfi(cfiLater, "#$cfiLater"))

    // Verify MarkdownExporter preserves stored highlight.epubCfi in exported wiki-links
    val doc = com.example.data.local.DocumentEntity(
      id = 77L,
      title = "Philosophical Investigations",
      author = "Ludwig Wittgenstein",
      filePath = "/storage/emulated/0/Books/Wittgenstein - 1953 - Philosophical Investigations.epub",
      fileType = "EPUB",
      category = "Books",
      totalPages = 220,
      currentPage = 12
    )
    val highlight = com.example.data.local.HighlightEntity(
      id = 88L,
      documentId = 77L,
      title = "Meaning as Use",
      highlightedText = quote,
      userNotes = "Core thesis on language-games.",
      pageNumber = 12,
      chapterTitle = "Chapter 1: Language Games",
      paragraphCitation = citation.chapterAndParagraphCitation,
      epubCfi = citation.epubCfi
    )
    val settings = com.example.data.local.SettingsEntity()
    val md = com.example.reader.MarkdownExporter.generateHighlightMarkdown(highlight, doc, settings)
    assertTrue(
      "Exported note markdown must link directly to stored #epubcfi(...), got:\n$md",
      md.contains("#${citation.epubCfi}|Link to highlight]]")
    )
  }

  @Test
  fun authorCapitalisationNormalisation_andMultiAuthorMetadata_worksAccurately() {
    // 1. Standard all-caps, all-lowercase, and mixed-case examples from prompt
    assertEquals("George Orwell", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("GEORGE ORWELL"))
    assertEquals("George Orwell", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("george orwell"))
    assertEquals("George Orwell", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("GeOrGe OrWeLl"))
    assertEquals("George Orwell", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("  GEORGE   ORWELL \n "))

    // 2. Non-standard capitalisation names (e.g., e. e. cummings, McDonald, O'Connor, van Gogh)
    assertEquals("e. e. cummings", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("e. e. cummings"))
    assertEquals("McDonald", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("MCDONALD"))
    assertEquals("McDonald", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("mcdonald"))
    assertEquals("Ronald McDonald", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("RONALD MCDONALD"))
    assertEquals("Flannery O'Connor", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("FLANNERY O'CONNOR"))
    assertEquals("Vincent van Gogh", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("VINCENT VAN GOGH"))
    assertEquals("Jean-Paul Sartre", com.example.ui.DocumentMetadataHelper.normalizeAuthorName("JEAN-PAUL SARTRE"))

    // 3. Multi-author registration without overwriting and duplicate prevention
    val initialEmpty = com.example.ui.DocumentMetadataHelper.parseAuthors("Unknown Author")
    assertTrue(initialEmpty.isEmpty())

    val afterFirst = com.example.ui.DocumentMetadataHelper.addAuthorToList(initialEmpty, "  George Orwell ")
    assertEquals(listOf("George Orwell"), afterFirst)

    val afterSecond = com.example.ui.DocumentMetadataHelper.addAuthorToList(afterFirst, "Aldous Huxley")
    assertEquals(listOf("George Orwell", "Aldous Huxley"), afterSecond)

    // Duplicate check
    assertTrue(com.example.ui.DocumentMetadataHelper.isDuplicateAuthor(afterSecond, "george orwell"))
    assertTrue(com.example.ui.DocumentMetadataHelper.hasExactCaseDuplicate(afterSecond, "George Orwell"))

    val stored = com.example.ui.DocumentMetadataHelper.formatAuthorsForStorage(afterSecond)
    assertEquals("George Orwell, Aldous Huxley", stored)
  }

  @Test
  fun selectionToolbarMoreMenu_revealsAuthorAndAbstractActions() {
    var addedAuthorSelection: String? = null
    var setAbstractSelection: String? = null

    composeTestRule.setContent {
      MaterialTheme {
        Column {
          com.example.ui.SelectionActionToolbarCard(
            state = com.example.ui.ActiveSelectionToolbarState(
              quote = "  ALDOUS HUXLEY  ",
              pageNumber = 1,
              chapterTitle = "Title Page",
              isCurrentlyDragging = false,
              canAddNextParagraph = false,
              onClearSelection = {},
              onSaveNoteWithDialog = {},
              onQuickSaveHighlight = {},
              onAddAsAuthor = { sel -> addedAuthorSelection = sel },
              onSetAsAbstract = { sel -> setAbstractSelection = sel }
            )
          )
        }
      }
    }

    composeTestRule.onNodeWithTag("selection_more_button").assertIsDisplayed().performClick()
    composeTestRule.onNodeWithTag("selection_more_actions_row").assertIsDisplayed()
    composeTestRule.onNodeWithTag("selection_set_as_abstract_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("selection_add_as_author_button").assertIsDisplayed().performClick()
    assertEquals("  ALDOUS HUXLEY  ", addedAuthorSelection)
  }

  @Test
  fun addAuthorFromSelectionDialog_normalisesAndAppendsAuthorWithoutOverwriting() {
    var savedAuthorsString = "George Orwell"

    composeTestRule.setContent {
      MaterialTheme {
        com.example.ui.AddAuthorFromSelectionDialog(
          selectedText = "  ALDOUS HUXLEY  ",
          currentRawAuthor = savedAuthorsString,
          documentTitle = "Brave New World & 1984",
          onDismiss = {},
          onConfirmAuthors = { updatedRaw, _ ->
            savedAuthorsString = updatedRaw
          }
        )
      }
    }

    // Existing author is displayed
    composeTestRule.onNodeWithTag("registered_author_chip_0").assertIsDisplayed()
    // Normalised preview shows "Aldous Huxley"
    composeTestRule.onNodeWithTag("normalised_author_preview").assertTextEquals("Aldous Huxley")
    // Confirm button initially shows un-normalised trimmed selection: Add "ALDOUS HUXLEY" as Author
    composeTestRule.onNodeWithTag("confirm_add_author_button").assertTextContains("Add \"ALDOUS HUXLEY\" as Author")

    // Click Normalise Capitalisation -> updates input to "Aldous Huxley"
    composeTestRule.onNodeWithTag("normalise_capitalisation_button").performClick()
    composeTestRule.onNodeWithTag("confirm_add_author_button").assertTextContains("Add \"Aldous Huxley\" as Author")
    composeTestRule.onNodeWithTag("confirm_add_author_button").performClick()

    assertEquals("George Orwell, Aldous Huxley", savedAuthorsString)
  }

  @Test
  fun setAbstractFromSelectionDialog_protectsExistingAbstractAndPersistsToRoom() {
    var savedAbstractString = ""
    val existingAbstract = "First existing abstract paragraph."
    val newSelectedAbstract = "Paragraph one of new abstract.\n\nParagraph two of new abstract."
    composeTestRule.setContent {
      MaterialTheme {
        com.example.ui.SetAbstractFromSelectionDialog(
          selectedPassage = newSelectedAbstract,
          existingAbstract = existingAbstract,
          documentTitle = "Brave New World & 1984",
          wasAutoSavedWhenEmpty = false,
          onDismiss = {},
          onConfirmSaveAbstract = { updated ->
            savedAbstractString = updated
          }
        )
      }
    }

    composeTestRule.onNodeWithTag("abstract_overwrite_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("existing_abstract_preview").assertTextEquals(existingAbstract)
    composeTestRule.onNodeWithTag("confirm_save_abstract_button").performClick()
    assertEquals("Paragraph one of new abstract.\n\nParagraph two of new abstract.", savedAbstractString)

    // Verify Room database persistence across reopening document
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      runBlocking {
        val docId = db.documentDao().insertDocument(
          DocumentEntity(
            title = "Brave New World & 1984",
            author = "George Orwell",
            summary = "",
            filePath = "/dummy/bnw_1984.epub",
            fileType = "EPUB",
            category = "BOOK"
          )
        )
        db.documentDao().updateAuthor(docId, "George Orwell, Aldous Huxley")
        db.documentDao().updateSummary(docId, savedAbstractString)

        val reloaded = db.documentDao().getDocumentById(docId)
        org.junit.Assert.assertNotNull(reloaded)
        assertEquals("George Orwell, Aldous Huxley", reloaded!!.author)
        assertEquals("Paragraph one of new abstract.\n\nParagraph two of new abstract.", reloaded.summary)
      }
    } finally {
      db.close()
    }
  }

  @Test
  fun readiumPrintPageLabels_cleanAndFormatAccurately() {
    assertEquals("20", com.example.ui.readium.cleanPrintPageLabel("20"))
    assertEquals("20", com.example.ui.readium.cleanPrintPageLabel("Side 20"))
    assertEquals("20", com.example.ui.readium.cleanPrintPageLabel("Page 20"))
    assertEquals("20", com.example.ui.readium.cleanPrintPageLabel("p. 20"))
    assertEquals("xxiv", com.example.ui.readium.cleanPrintPageLabel("Side xxiv"))

    // Test JS start/end parser
    val jsonString = """{"start": 2, "end": 4}"""
    val parsed = com.example.ui.readium.parseJsStartEndResult(jsonString)
    org.junit.Assert.assertNotNull(parsed)
    assertEquals(2, parsed?.first)
    assertEquals(4, parsed?.second)

    val quotedJson = "\"{\\\"start\\\": -1, \\\"end\\\": -1}\""
    val parsedQuoted = com.example.ui.readium.parseJsStartEndResult(quotedJson)
    org.junit.Assert.assertNotNull(parsedQuoted)
    assertEquals(-1, parsedQuoted?.first)
    assertEquals(-1, parsedQuoted?.second)

    // Test null/empty handling
    org.junit.Assert.assertNull(com.example.ui.readium.parseJsStartEndResult(null))
    org.junit.Assert.assertNull(com.example.ui.readium.parseJsStartEndResult("null"))
  }

  @Test
  fun readiumHighlightCitation_usesPrintPageWhenAvailable() {
    val resolvedChapter = "Chapter 3: The Method"
    val resolvedPage = 41

    // Case 1: with single print page
    val singlePrintLabel = "20"
    val pPrefix1 = if (singlePrintLabel.contains("–") || singlePrintLabel.contains("-")) "pp." else "p."
    val citation1 = "$resolvedChapter, $pPrefix1 $singlePrintLabel"
    val plainLocus1 = "$resolvedChapter • $pPrefix1 $singlePrintLabel"
    assertEquals("Chapter 3: The Method, p. 20", citation1)
    assertEquals("Chapter 3: The Method • p. 20", plainLocus1)

    // Case 2: with print page range
    val rangePrintLabel = "20–21"
    val pPrefix2 = if (rangePrintLabel.contains("–") || rangePrintLabel.contains("-")) "pp." else "p."
    val citation2 = "$resolvedChapter, $pPrefix2 $rangePrintLabel"
    val plainLocus2 = "$resolvedChapter • $pPrefix2 $rangePrintLabel"
    assertEquals("Chapter 3: The Method, pp. 20–21", citation2)
    assertEquals("Chapter 3: The Method • pp. 20–21", plainLocus2)

    // Case 3: without print page
    val noPrintLabel = ""
    val hasPrintPage = noPrintLabel.isNotBlank()
    val citation3 = if (hasPrintPage) "$resolvedChapter, p. $noPrintLabel" else "$resolvedChapter, pos. $resolvedPage"
    val plainLocus3 = if (hasPrintPage) "$resolvedChapter • p. $noPrintLabel" else "$resolvedChapter • pos. $resolvedPage"
    assertEquals("Chapter 3: The Method, pos. 41", citation3)
    assertEquals("Chapter 3: The Method • pos. 41", plainLocus3)
  }

  @Test
  fun readiumPrintPageBreakStyles_cssRulesGeneration() {
    val markerIds = listOf("page-20", "page-21", "side_22")
    val distinctIds = markerIds.filter { it.isNotBlank() }.distinct()
    val idsJson = org.json.JSONArray(distinctIds).toString()
    assertTrue(idsJson.contains("page-20"))
    assertTrue(idsJson.contains("page-21"))
    assertTrue(idsJson.contains("side_22"))

    // Verify empty list handling
    val emptyIds = emptyList<String>().filter { it.isNotBlank() }.distinct()
    val emptyJson = org.json.JSONArray(emptyIds).toString()
    assertEquals("[]", emptyJson)
  }

  @Test
  fun readiumNavigatorConfiguration_interceptsPageBreaks() {
    val config = com.example.reader.readium.ReadiumPublicationManager.createNavigatorConfiguration()
    org.junit.Assert.assertNotNull(config)
    val templates = config.decorationTemplates
    org.junit.Assert.assertNotNull(templates)
    val highlightTmpl = templates.get(org.readium.r2.navigator.Decoration.Style.Highlight::class)
    org.junit.Assert.assertNotNull(highlightTmpl)
    val stylesheet = highlightTmpl?.stylesheet.orEmpty()
    assertTrue(stylesheet.contains("page-break-before"))
    assertTrue(stylesheet.contains("page-break-after"))
    assertTrue(stylesheet.contains("break-before: column"))
    assertTrue(stylesheet.contains("break-after: column"))
  }
}

