package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Calculates and formats estimated reading time for books and articles based on actual file content
 * and standard average adult silent reading speed (~225 words per minute).
 */
object ReadingTimeEstimator {

    const val DEFAULT_READING_SPEED_WPM = 225

    private val WORD_SPLIT_REGEX = Regex("\\s+")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val SCRIPT_STYLE_REGEX = Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val HTML_ENTITY_REGEX = Regex("&(#?[a-zA-Z0-9]+);")

    /**
     * Counts the number of words in a plain text string.
     */
    fun countWordsInPlainText(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(WORD_SPLIT_REGEX).count { token ->
            token.any { it.isLetterOrDigit() }
        }
    }

    /**
     * Strips HTML/XML tags and script/style blocks and counts words.
     */
    fun countWordsInMarkup(markup: String): Int {
        if (markup.isBlank()) return 0
        val withoutScripts = SCRIPT_STYLE_REGEX.replace(markup, " ")
        val withoutTags = HTML_TAG_REGEX.replace(withoutScripts, " ")
        val unescaped = HTML_ENTITY_REGEX.replace(withoutTags, " ")
        return countWordsInPlainText(unescaped)
    }

    /**
     * Converts a total word count to estimated reading time in minutes at [wpm] words per minute.
     */
    fun calculateMinutesFromWords(wordCount: Int, wpm: Int = DEFAULT_READING_SPEED_WPM): Int {
        if (wordCount <= 0) return 1
        val safeWpm = wpm.coerceIn(80, 600)
        return ceil(wordCount.toDouble() / safeWpm.toDouble()).toInt().coerceAtLeast(1)
    }

    /**
     * Fallback estimation from total page count when a file is simulated or has no extractable text layer
     * (~250 words per page at 225 WPM -> ~1.11 min per page).
     */
    fun estimateMinutesFromPages(totalPages: Int, category: String = "BOOK", wpm: Int = DEFAULT_READING_SPEED_WPM): Int {
        val safePages = totalPages.coerceAtLeast(1)
        val avgWordsPerPage = if (category.equals("ARTICLE", ignoreCase = true)) 260 else 250
        return calculateMinutesFromWords(safePages * avgWordsPerPage, wpm)
    }

    /**
     * Calculates the total word count from an already-parsed [EpubBook].
     */
    fun countWordsInEpubBook(epubBook: EpubBook): Int {
        var totalWords = 0
        for (chapter in epubBook.chapters) {
            if (chapter.paragraphs.isNotEmpty()) {
                for (p in chapter.paragraphs) {
                    totalWords += countWordsInPlainText(p)
                }
            } else if (chapter.content.isNotBlank()) {
                totalWords += countWordsInPlainText(chapter.content)
            }
        }
        return totalWords
    }

    /**
     * Inspects the actual document file on disk to count words and return the estimated reading time in minutes.
     * Supports EPUB, PDF, DAISY (.zip or directory), HTML/XHTML, MD, and TXT.
     */
    fun calculateReadingTimeMinutes(
        file: File?,
        fileType: String,
        totalPages: Int = 1,
        category: String = "BOOK",
        context: Context? = null,
        wpm: Int = DEFAULT_READING_SPEED_WPM
    ): Int {
        if (file == null || !file.exists()) {
            return estimateMinutesFromPages(totalPages, category, wpm)
        }

        val normalizedType = fileType.uppercase(Locale.ROOT)
        val ext = file.extension.lowercase(Locale.ROOT)

        return try {
            val wordCount = when {
                normalizedType == "TXT" || normalizedType == "MD" || ext == "txt" || ext == "md" || ext == "markdown" -> {
                    countWordsInPlainText(file.readText(Charsets.UTF_8))
                }
                normalizedType == "HTML" || ext == "html" || ext == "htm" || ext == "xhtml" -> {
                    countWordsInMarkup(file.readText(Charsets.UTF_8))
                }
                normalizedType == "EPUB" || ext == "epub" -> {
                    countWordsInEpubArchive(file)
                }
                normalizedType == "DAISY" || ext == "zip" || file.isDirectory -> {
                    countWordsInDaisy(file)
                }
                normalizedType == "PDF" || ext == "pdf" -> {
                    countWordsInPdf(file, totalPages, context)
                }
                else -> 0
            }

            if (wordCount > 0) {
                calculateMinutesFromWords(wordCount, wpm)
            } else {
                estimateMinutesFromPages(totalPages, category, wpm)
            }
        } catch (_: Throwable) {
            estimateMinutesFromPages(totalPages, category, wpm)
        }
    }

    private fun countWordsInEpubArchive(epubFile: File): Int {
        if (!epubFile.exists() || !epubFile.isFile || epubFile.length() == 0L) return 0
        var totalWords = 0
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.lowercase(Locale.ROOT)
                if ((name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml")) &&
                    !name.endsWith("container.xml") &&
                    !name.endsWith(".opf") &&
                    !name.endsWith(".ncx")
                ) {
                    val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    totalWords += countWordsInMarkup(text)
                }
            }
        }
        return totalWords
    }

    private fun countWordsInDaisy(daisySource: File): Int {
        if (!daisySource.exists()) return 0
        var totalWords = 0
        if (daisySource.isDirectory) {
            daisySource.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val ext = f.extension.lowercase(Locale.ROOT)
                    val name = f.name.lowercase(Locale.ROOT)
                    if ((ext == "html" || ext == "htm" || ext == "xhtml" || ext == "xml") &&
                        name != "ncc.html" && name != "ncc.htm" && !name.endsWith(".smil")
                    ) {
                        totalWords += countWordsInMarkup(f.readText(Charsets.UTF_8))
                    }
                }
            }
        } else if (daisySource.isFile && daisySource.length() > 0L) {
            ZipFile(daisySource).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast("/").lowercase(Locale.ROOT)
                    val ext = name.substringAfterLast(".", "")
                    if ((ext == "html" || ext == "htm" || ext == "xhtml" || ext == "xml") &&
                        name != "ncc.html" && name != "ncc.htm" && !name.contains("container.xml")
                    ) {
                        val content = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        totalWords += countWordsInMarkup(content)
                    }
                }
            }
        }
        return totalWords
    }

    private fun countWordsInPdf(pdfFile: File, fallbackPages: Int, context: Context?): Int {
        if (!pdfFile.exists() || !pdfFile.isFile || pdfFile.length() == 0L) return 0
        try {
            if (context != null && !PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(context.applicationContext)
            }
        } catch (_: Throwable) {}

        return try {
            PDDocument.load(
                pdfFile,
                com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(16L * 1024L * 1024L)
            ).use { pdDoc ->
                val totalPages = pdDoc.numberOfPages.coerceAtLeast(1)
                val stripper = PDFTextStripper()
                if (totalPages <= 30) {
                    stripper.startPage = 1
                    stripper.endPage = totalPages
                    val extracted = stripper.getText(pdDoc)
                    val words = countWordsInPlainText(extracted)
                    if (words > 20) words else totalPages * 250
                } else {
                    // Sample up to 12 representative pages across the PDF for fast, accurate word count estimation
                    val sampleIndices = linkedSetOf<Int>()
                    val step = (totalPages.toDouble() / 12.0).coerceAtLeast(1.0)
                    var cursor = 1.0
                    while (sampleIndices.size < 12 && cursor <= totalPages) {
                        sampleIndices.add(cursor.roundToInt().coerceIn(1, totalPages))
                        cursor += step
                    }
                    var sampledWords = 0
                    for (pageNum in sampleIndices) {
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        sampledWords += countWordsInPlainText(stripper.getText(pdDoc))
                    }
                    val avgPerSampledPage = sampledWords.toDouble() / sampleIndices.size.coerceAtLeast(1).toDouble()
                    val estimatedTotalWords = (avgPerSampledPage * totalPages).roundToInt()
                    if (estimatedTotalWords > totalPages * 15) estimatedTotalWords else totalPages * 250
                }
            }
        } catch (_: Throwable) {
            fallbackPages.coerceAtLeast(1) * 250
        }
    }

    /**
     * Formats the reading time label for display on a book or article card.
     * Takes into account the document's [DocumentEntity.readingProgress] if partially read,
     * or returns the total estimated reading time.
     *
     * Examples:
     * - Unstarted or < 5% progress: `"~12 min read"` or `"~2h 15m read"`
     * - In progress (5%..94%): `"~18 min left"` or `"~1h 40m left"`
     * - Completed (>= 95%): `"~45 min read"`
     */
    fun formatReadingTimeForCard(
        document: DocumentEntity,
        resolvedTotalMinutes: Int = document.estimatedReadingMinutes,
        compact: Boolean = false
    ): String {
        val totalMins = if (resolvedTotalMinutes > 0) {
            resolvedTotalMinutes
        } else {
            estimateMinutesFromPages(document.totalPages, document.category)
        }

        val progress = document.readingProgress.coerceIn(0f, 1f)
        val isPartiallyRead = progress in 0.05f..0.94f
        val displayMinutes = if (isPartiallyRead) {
            ceil(totalMins * (1f - progress).toDouble()).toInt().coerceAtLeast(1)
        } else {
            totalMins.coerceAtLeast(1)
        }

        val suffix = when {
            compact -> ""
            isPartiallyRead -> " left"
            else -> " read"
        }

        return formatDurationMinutes(displayMinutes, suffix)
    }

    fun formatDurationMinutes(minutes: Int, suffix: String = " read"): String {
        val safeMins = minutes.coerceAtLeast(1)
        return if (safeMins < 60) {
            "~${safeMins}m$suffix"
        } else {
            val hours = safeMins / 60
            val remMins = safeMins % 60
            if (remMins == 0) {
                "~${hours}h$suffix"
            } else {
                "~${hours}h ${remMins}m$suffix"
            }
        }
    }
}
