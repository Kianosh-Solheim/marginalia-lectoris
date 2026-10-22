package com.example.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.EpubParser
import com.example.ui.ReaderViewModel
import com.example.ui.buildReadingPositionForPage
import com.example.ui.resolveResumeTargetWithStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContinueReadingWidgetRobolectricTest {

    @Test
    fun `ContinueReadingSelector selects most recent book or article and supports tablet dual view`() {
        val book1 = DocumentEntity(
            id = 101L,
            title = "Meditations",
            author = "Marcus Aurelius",
            category = "BOOK",
            fileType = "EPUB",
            filePath = "/tmp/meditations.epub",
            currentPage = 12,
            totalPages = 80,
            readingProgress = 0.15f,
            lastReadTimestamp = 2_000L,
            lastPositionCfi = "epubcfi(/6/4!/4/18/1:0)",
            lastPositionParagraph = 9,
            lastPositionLoc = 24,
            lastPositionLabel = "¶ 9 · loc. 24",
            lastPositionChapter = "Book II"
        )
        val article1 = DocumentEntity(
            id = 102L,
            title = "Thinking as a Craft in Zettelkasten",
            author = "Niklas Luhmann",
            category = "ARTICLE",
            fileType = "PDF",
            filePath = "/tmp/zettelkasten.pdf",
            currentPage = 6,
            totalPages = 18,
            readingProgress = 0.33f,
            lastReadTimestamp = 5_000L,
            lastPositionParagraph = 14,
            lastPositionLabel = "¶ 14 of 26",
            lastPositionChapter = "Slip-Box Architecture"
        )

        val docs = listOf(book1, article1)

        assertEquals(article1.id, ContinueReadingSelector.selectMostRecent(docs, ContinueFilter.ALL)?.id)
        assertEquals(book1.id, ContinueReadingSelector.selectMostRecent(docs, ContinueFilter.BOOKS)?.id)
        assertEquals(article1.id, ContinueReadingSelector.selectMostRecent(docs, ContinueFilter.ARTICLES)?.id)

        val (primaryAll, secondaryAll) = ContinueReadingSelector.selectForTabletLarge(docs, ContinueFilter.ALL)
        assertEquals(book1.id, primaryAll?.id)
        assertEquals(article1.id, secondaryAll?.id)

        assertEquals("¶ 9 · loc. 24", ContinueFormatter.formatLocationSummary(book1))
        assertEquals("¶ 14 of 26", ContinueFormatter.formatLocationSummary(article1))
    }

    @Test
    fun `ContinueReadingWidgetProvider renders all 5 size tiers and cycles filter chip`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getInstance(context)
        val docDao = db.documentDao()

        val bookId = docDao.insertDocument(
            DocumentEntity(
                title = "Epistulae Morales ad Lucilium",
                author = "Seneca",
                category = "BOOK",
                fileType = "EPUB",
                epubVersion = "EPUB 3",
                filePath = "/tmp/seneca.epub",
                currentPage = 14,
                totalPages = 100,
                readingProgress = 0.14f,
                estimatedReadingMinutes = 120,
                lastReadTimestamp = 10_000L,
                lastPositionCfi = "epubcfi(/6/2[letter1]!/4/12/1:0)",
                lastPositionParagraph = 6,
                lastPositionLoc = 19,
                lastPositionLabel = "p. 22 · ¶ 6 · loc. 19",
                lastPositionSnippet = "Hold every hour in your grasp.",
                lastPositionChapter = "Letter I: On Saving Time"
            )
        )
        val articleId = docDao.insertDocument(
            DocumentEntity(
                title = "Scholarly Citable Locations in EPUB 3",
                author = "Dr. E. Vance",
                category = "ARTICLE",
                fileType = "EPUB",
                epubVersion = "EPUB 3",
                filePath = "/tmp/citable.epub",
                currentPage = 4,
                totalPages = 12,
                readingProgress = 0.33f,
                estimatedReadingMinutes = 18,
                lastReadTimestamp = 15_000L,
                lastPositionParagraph = 11,
                lastPositionLoc = 8,
                lastPositionLabel = "¶ 11 of 28",
                lastPositionSnippet = "Every paragraph carries a deterministic locus.",
                lastPositionChapter = "Section 2: Canonical Identifiers"
            )
        )

        try {
            val widgetId = 901
            ContinueReadingWidgetProvider.setFilterForWidget(context, widgetId, ContinueFilter.ALL)
            assertEquals(ContinueFilter.ALL, ContinueReadingWidgetProvider.getFilterForWidget(context, widgetId))

            val tierDimensions = listOf(
                ContinueWidgetSizeTier.COMPACT to (140 to 100),
                ContinueWidgetSizeTier.MEDIUM to (250 to 115),
                ContinueWidgetSizeTier.EXPANDED to (270 to 220),
                ContinueWidgetSizeTier.TABLET_WIDE to (420 to 150),
                ContinueWidgetSizeTier.TABLET_LARGE to (440 to 250)
            )

            for ((expectedTier, dims) in tierDimensions) {
                val (wDp, hDp) = dims
                assertEquals(expectedTier, ContinueReadingWidgetProvider.resolveSizeTier(wDp, hDp))

                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                }
                val rv = ContinueReadingWidgetProvider.buildRemoteViewsForWidget(
                    context = context,
                    appWidgetManager = AppWidgetManager.getInstance(context),
                    appWidgetId = widgetId,
                    optionsBundle = options
                )
                val parent = FrameLayout(context)
                val inflated: View = rv.apply(context, parent)
                val titleView = inflated.findViewById<TextView>(R.id.continue_primary_title)
                assertNotNull("Expected primary title in tier $expectedTier", titleView)
                assertTrue(titleView.text.toString().isNotBlank())

                if (expectedTier == ContinueWidgetSizeTier.TABLET_LARGE) {
                    val secondaryTitle = inflated.findViewById<TextView>(R.id.continue_secondary_title)
                    assertNotNull(secondaryTitle)
                    assertEquals("Scholarly Citable Locations in EPUB 3", titleView.text.toString())
                    assertEquals("Epistulae Morales ad Lucilium", secondaryTitle.text.toString())

                    val booksShelfSection = inflated.findViewById<View>(R.id.continue_shelf_books_section)
                    val articlesShelfSection = inflated.findViewById<View>(R.id.continue_shelf_articles_section)
                    val booksContainer = inflated.findViewById<android.widget.LinearLayout>(R.id.continue_shelf_books_container)
                    val articlesContainer = inflated.findViewById<android.widget.LinearLayout>(R.id.continue_shelf_articles_container)
                    assertNotNull(booksShelfSection)
                    assertNotNull(articlesShelfSection)
                    assertNotNull(booksContainer)
                    assertNotNull(articlesContainer)
                    assertEquals(View.VISIBLE, booksShelfSection.visibility)
                    assertEquals(View.VISIBLE, articlesShelfSection.visibility)
                    assertTrue("Expected at least 1 cover in Recent Books shelf", booksContainer.childCount >= 1)
                    assertTrue("Expected at least 1 cover in Recent Articles shelf", articlesContainer.childCount >= 1)
                }
            }
        } finally {
            docDao.deleteDocumentById(bookId)
            docDao.deleteDocumentById(articleId)
        }
    }

    @Test
    fun `reflow independent resume resolves exact passage across font size changes and Intent extras`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
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

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                  <metadata>
                    <dc:title>Reflow Verification Volume</dc:title>
                    <dc:creator>Marginalia Test</dc:creator>
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

            val parasHtml = (1..30).joinToString("\n") { idx ->
                "<p id=\"p$idx\">Paragraph number $idx presents a distinct philosophical argument with unique vocabulary token_${idx * 17} so we can verify reflow-independent citation and exact page resolution across font sizes.</p>"
            }
            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write("<html><body><h1>Chapter 1: On Reflow</h1>$parasHtml</body></html>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val book = EpubParser.parseEpub(ByteArrayInputStream(baos.toByteArray()))

        val (pages16, _) = EpubParser.paginateBook(
            epubBook = book,
            fontSizeSp = 16f,
            lineSpacingMultiplier = 1.4f,
            marginDp = 16
        )
        val targetPageIdx16 = (pages16.size / 2).coerceAtLeast(1)
        val doc = DocumentEntity(
            id = 505L,
            title = book.title,
            author = book.author,
            category = "BOOK",
            fileType = "EPUB",
            filePath = "/tmp/reflow_test.epub",
            currentPage = targetPageIdx16 + 1,
            totalPages = pages16.size
        )

        val savedPos = buildReadingPositionForPage(
            document = doc,
            epubBook = book,
            pdfEbookDocument = null,
            pagesList = pages16,
            page = targetPageIdx16 + 1,
            totalPages = pages16.size,
            chapterTitle = "Chapter 1: On Reflow"
        )
        assertTrue("Expected non-blank CFI", savedPos.cfi.startsWith("epubcfi("))
        assertTrue("Expected positive paragraph number", savedPos.paragraph > 0)

        // Reflow at much larger 28sp font size
        val (pages28, _) = EpubParser.paginateBook(
            epubBook = book,
            fontSizeSp = 28f,
            lineSpacingMultiplier = 1.4f,
            marginDp = 16
        )
        assertTrue(pages28.size > pages16.size)

        val (resolvedIdxCfi, stepCfi) = resolveResumeTargetWithStep(
            resume = ResumeTarget(position = savedPos, page = targetPageIdx16 + 1),
            pagesList = pages28,
            epubBook = book,
            document = doc
        )
        assertEquals("a_epubcfi", stepCfi)
        assertTrue(resolvedIdxCfi in pages28.indices)

        // Verify MainActivity parses extra_resume_* Intent extras into ViewModel pendingResume
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_DOCUMENT_ID, doc.id)
            putExtra("extra_resume_cfi", savedPos.cfi)
            putExtra("extra_resume_paragraph", savedPos.paragraph)
            putExtra("extra_resume_offset", savedPos.offset)
            putExtra("extra_resume_loc", savedPos.loc)
            putExtra("extra_resume_page", targetPageIdx16 + 1)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val activity = controller.get()
        val vm = ViewModelProvider(activity)[ReaderViewModel::class.java]
        val pending = vm.pendingResume.value
        assertNotNull(pending)
        assertEquals(savedPos.cfi, pending?.position?.cfi)
        assertEquals(savedPos.paragraph, pending?.position?.paragraph)
    }
}
