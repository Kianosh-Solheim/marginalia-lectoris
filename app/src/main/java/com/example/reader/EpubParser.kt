package com.example.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

const val DEBUG_LOAD_PERF = true
private const val LOAD_PERF_TAG = "LoadPerf"

private inline fun safeLogPerf(msg: () -> String) {
    if (DEBUG_LOAD_PERF) {
        try {
            android.util.Log.d(LOAD_PERF_TAG, msg())
        } catch (_: Throwable) {
            // Ignored in headless JVM tests where android.util.Log is not mocked
        }
    }
}



data class EpubLandmarkItem(
    val title: String,
    val epubType: String,
    val href: String,
    val fileKey: String,
    val anchorId: String,
    val resolvedPage: Int = 1
)

data class EpubPageListEntry(
    val label: String,
    val spineFileKey: String,
    val anchorId: String,
    val spineIndex: Int = 0
)

data class EpubSmilClip(
    val id: String,
    val textFileKey: String,
    val textAnchorId: String,
    val audioFilePath: String,
    val clipBeginMs: Long,
    val clipEndMs: Long
)

data class EpubStandaloneAudioItem(
    val id: String,
    val fileKey: String,
    val anchorId: String,
    val audioFilePath: String,
    val label: String = "Audio"
)

data class EpubBook(
    val title: String,
    val author: String,
    val description: String,
    val coverBitmap: Bitmap? = null,
    val chapters: List<EpubChapter>,
    val hasPageListMetadata: Boolean = false,
    val tocItems: List<EpubTocItem> = emptyList(),
    val footnoteEntries: Map<String, EpubFootnoteEntry> = emptyMap(),
    val embeddedImages: Map<String, Bitmap> = emptyMap(),
    val pageListEntries: List<EpubPageListEntry> = emptyList(),
    val totalStableLocations: Int = 1,
    val landmarks: List<EpubLandmarkItem> = emptyList(),
    val pageProgressionDirection: String = "ltr",
    val language: String = "en",
    val isFixedLayout: Boolean = false,
    val renditionOrientation: String = "auto",
    val renditionSpread: String = "auto",
    val mediaOverlays: List<EpubSmilClip> = emptyList(),
    val mediaOverlayDuration: String = "",
    val embeddedAudioBytes: Map<String, ByteArray> = emptyMap(),
    val standaloneAudioItems: List<EpubStandaloneAudioItem> = emptyList(),
    val sourceArchivePath: String = "",
    val audioEntryNames: Set<String> = emptySet()
) {
    val hasMediaOverlays: Boolean
        get() = mediaOverlays.isNotEmpty()

    val hasPageList: Boolean
        get() = hasPageListMetadata || pageListEntries.isNotEmpty()

    /**
     * Given a reading position (spine index or fileKey and optional anchorId / paragraph index),
     * returns the most recent preceding print page label (supporting roman numerals like "xiv" without converting them).
     */
    fun lookupPageLabel(
        spineFileKey: String = "",
        anchorId: String = "",
        spineIndex: Int = 0,
        paragraphIndexInChapter: Int = 0
    ): String? {
        if (!hasPageList) return null
        val cleanFile = spineFileKey.substringAfterLast("/").trim()
        val cleanAnchor = anchorId.removePrefix("#").trim()

        // 1. Check chapter paragraphPageEquivalents if chapter matches
        val targetChIdx = if (cleanFile.isNotEmpty()) {
            chapters.indexOfFirst {
                it.fileKey.equals(cleanFile, ignoreCase = true) || it.id.equals(cleanFile, ignoreCase = true)
            }.takeIf { it >= 0 } ?: spineIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        } else {
            spineIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        }

        val ch = chapters.getOrNull(targetChIdx)
        if (ch != null) {
            if (cleanAnchor.isNotEmpty()) {
                val blockIdx = ch.anchorToBlockIndex[cleanAnchor]
                    ?: ch.anchorToBlockIndex.entries.firstOrNull { it.key.equals(cleanAnchor, ignoreCase = true) }?.value
                if (blockIdx != null) {
                    val blockEq = ch.semanticBlocks.getOrNull(blockIdx)?.pageEquivalent?.substringBefore("–")?.trim().orEmpty()
                    if (blockEq.isNotEmpty()) return blockEq
                }
            }
            if (ch.paragraphPageEquivalents.isNotEmpty()) {
                val safeParaIdx = paragraphIndexInChapter.coerceIn(0, ch.paragraphPageEquivalents.lastIndex)
                for (i in safeParaIdx downTo 0) {
                    val lbl = ch.paragraphPageEquivalents[i].substringBefore("–").trim()
                    if (lbl.isNotEmpty()) return lbl
                }
            }
            for (prevChIdx in (targetChIdx - 1) downTo 0) {
                val prevLbl = chapters[prevChIdx].paragraphPageEquivalents.lastOrNull { it.isNotBlank() }?.substringAfterLast("–")?.trim()
                if (!prevLbl.isNullOrEmpty()) return prevLbl
            }
        }

        // 2. Fallback to pageListEntries in spine order
        if (pageListEntries.isNotEmpty()) {
            var latest: String? = null
            for (entry in pageListEntries) {
                if (entry.spineIndex < targetChIdx) {
                    latest = entry.label
                } else if (entry.spineIndex == targetChIdx || (cleanFile.isNotEmpty() && entry.spineFileKey.equals(cleanFile, ignoreCase = true))) {
                    latest = entry.label
                    if (cleanAnchor.isNotEmpty() && entry.anchorId.equals(cleanAnchor, ignoreCase = true)) {
                        return entry.label
                    }
                }
            }
            return latest ?: pageListEntries.first().label
        }

        return null
    }
}

data class EpubChapter(
    val id: String,
    val title: String,
    val content: String,
    val paragraphs: List<String>,
    val footnotes: Map<String, String> = emptyMap(),
    val htmlContent: String = "",
    val paragraphPageEquivalents: List<String> = emptyList(),
    val fileKey: String = "",
    val semanticBlocks: List<EpubSemanticBlock> = emptyList(),
    val footnoteEntries: Map<String, EpubFootnoteEntry> = emptyMap(),
    val anchorToBlockIndex: Map<String, Int> = emptyMap(),
    val canonicalText: String = "",
    val startStableLocation: Int = 1,
    val endStableLocation: Int = 1,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val paragraphCanonicalOffsets: IntArray = IntArray(0)
) {
    /**
     * Returns only genuine `<p>` text paragraphs (`EpubSemanticBlockType.PARAGRAPH`),
     * excluding headings, subtitles, blockquotes, lists, figure captions, and footnote definitions.
     */
    val countedTextParagraphs: List<EpubSemanticBlock>
        get() = if (semanticBlocks.isNotEmpty()) {
            semanticBlocks.filter { it.isCountedParagraph }
        } else {
            paragraphs.mapIndexed { idx, p ->
                EpubSemanticBlock(
                    type = EpubSemanticBlockType.PARAGRAPH,
                    text = p,
                    paragraphNumberInChapter = idx + 1,
                    pageEquivalent = paragraphPageEquivalents.getOrNull(idx).orEmpty(),
                    fileKey = fileKey
                )
            }
        }
}

data class EpubPassageCitation(
    val chapterTitle: String,
    val startParagraphNumber: Int,
    val endParagraphNumber: Int,
    val pageEquivalent: String? = null,
    val stableLocationStart: Int = 1,
    val stableLocationEnd: Int = 1,
    val chapterAndParagraphCitation: String = "",
    val epubCfi: String = "",
    val isFixedLayout: Boolean = false,
    val fixedLayoutPageIndex: Int = 1,
    val fixedLayoutTotalPages: Int = 1
) {
    /**
     * Formats the citation as a plain locus string:
     * `"p. 42, Ch. 3 ¶ 4, EPUB loc. 238"`
     * (omits the page part if no page-list; uses `¶¶` and `pp.` for ranges).
     * For fixed-layout (FXL) books, paragraph numbers and stable locations are not meaningful:
     * shows the print page if a page-list exists, otherwise `"page N of M"`, plus chapter title if distinct.
     */
    fun plainLocus(): String {
        val cleanPage = pageEquivalent?.trim()
            ?.removePrefix("pp.")
            ?.removePrefix("p.")
            ?.removePrefix("page equivalent")
            ?.trim()
            ?.removePrefix("pp.")
            ?.removePrefix("p.")
            ?.trim()
            .orEmpty()

        if (isFixedLayout) {
            val fxlParts = mutableListOf<String>()
            if (cleanPage.isNotEmpty()) {
                val isMultiPage = cleanPage.contains("–") || cleanPage.contains("-")
                fxlParts.add(if (isMultiPage) "pp. $cleanPage" else "p. $cleanPage")
            } else {
                val pNum = fixedLayoutPageIndex.coerceAtLeast(1)
                val pTot = fixedLayoutTotalPages.coerceAtLeast(pNum)
                fxlParts.add("page $pNum of $pTot")
            }
            val shortCh = formatCompactChapterPrefix(chapterTitle)
            if (shortCh.isNotEmpty() && !shortCh.equals("Cover", ignoreCase = true)) {
                fxlParts.add(shortCh)
            }
            return fxlParts.joinToString(", ")
        }

        val parts = mutableListOf<String>()

        // 1. Print page equivalent (if available)
        if (cleanPage.isNotEmpty()) {
            val isMultiPage = cleanPage.contains("–") || cleanPage.contains("-")
            parts.add(if (isMultiPage) "pp. $cleanPage" else "p. $cleanPage")
        }

        // 2. Chapter and paragraph number (e.g. "Ch. 3 ¶ 4" or "Ch. 3 ¶¶ 4–6")
        val shortCh = formatCompactChapterPrefix(chapterTitle)
        val startP = startParagraphNumber.coerceAtLeast(1)
        val endP = endParagraphNumber.coerceAtLeast(startP)
        val paraPart = if (endP > startP) "¶¶ $startP–$endP" else "¶ $startP"
        parts.add(if (shortCh.isNotEmpty()) "$shortCh $paraPart" else paraPart)

        // 3. Stable location (e.g. "EPUB loc. 238" or "EPUB loc. 238–240")
        val sLoc = stableLocationStart.coerceAtLeast(1)
        val eLoc = stableLocationEnd.coerceAtLeast(sLoc)
        val locPart = if (eLoc > sLoc) "EPUB loc. $sLoc–$eLoc" else "EPUB loc. $sLoc"
        parts.add(locPart)

        return parts.joinToString(", ")
    }

    /**
     * Formats the same content as a Markdown line suitable for the highlight note's location field,
     * appending the `epubcfi(...)` identifier after the existing `p.` / `¶` / `loc.` content when present.
     */
    fun obsidianLocus(): String {
        val base = plainLocus()
        val cleanCfi = epubCfi.trim().removePrefix("#")
        return if (cleanCfi.isNotEmpty() && cleanCfi.startsWith("epubcfi(", ignoreCase = true)) {
            if (base.isNotEmpty()) "$base, $cleanCfi" else cleanCfi
        } else {
            base
        }
    }

    companion object {
        fun formatCompactChapterPrefix(rawChapterTitle: String): String {
            val clean = rawChapterTitle.trim().removePrefix("#").trim()
            if (clean.isEmpty()) return ""
            val chNumMatch = Regex("^(?:Chapter|Ch\\.?|Kapittel|Part|Book)\\s+(\\d+|[IVXLCDM]+)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (chNumMatch != null) {
                return "Ch. ${chNumMatch.groupValues[1]}"
            }
            val leadingNumMatch = Regex("^(\\d{1,3})(?:[.:\\s]|$)").find(clean)
            if (leadingNumMatch != null) {
                return "Ch. ${leadingNumMatch.groupValues[1]}"
            }
            return clean
        }

        fun fromStoredHighlightFields(
            chapterTitle: String,
            paragraphCitation: String,
            pageEquivalent: String?,
            fallbackPageNumber: Int,
            epubCfi: String = ""
        ): EpubPassageCitation {
            val rawPara = paragraphCitation.trim()
            val locRegex = Regex("EPUB\\s+loc\\.\\s*(\\d+)(?:[–\\-](\\d+))?", RegexOption.IGNORE_CASE)
            val locMatch = locRegex.find(rawPara)
            val sLoc = locMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallbackPageNumber.coerceAtLeast(1)
            val eLoc = locMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: sLoc

            val paraRegex = Regex("¶+\\s*(\\d+)(?:[–\\-](\\d+))?")
            val paraMatch = paraRegex.find(rawPara)
            val sPara = paraMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val ePara = paraMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: sPara

            val pageRegex = Regex("\\bpp?\\.\\s*([0-9ivxlcdm]+(?:[–\\-][0-9ivxlcdm]+)?)", RegexOption.IGNORE_CASE)
            val pageFromCite = pageRegex.find(rawPara)?.groupValues?.getOrNull(1)?.trim()
            val resolvedPageEq = pageEquivalent?.trim()?.ifBlank { null } ?: pageFromCite

            val resolvedChapter = chapterTitle.trim().ifBlank {
                rawPara.substringBefore("¶").trim().trimEnd(',', '•', '-').trim()
            }.ifBlank { "Ch. 1" }

            val resolvedCfi = epubCfi.trim().removePrefix("#").ifBlank {
                val spineStep = 2
                val blockStepS = (sPara.coerceAtLeast(1)) * 2
                val blockStepE = (ePara.coerceAtLeast(sPara)) * 2
                if (blockStepE > blockStepS) {
                    "epubcfi(/6/$spineStep!/4,/$blockStepS/1:0,/$blockStepE/1:0)"
                } else {
                    "epubcfi(/6/$spineStep!/4/$blockStepS/1:0)"
                }
            }

            return EpubPassageCitation(
                chapterTitle = resolvedChapter,
                startParagraphNumber = sPara,
                endParagraphNumber = ePara,
                pageEquivalent = resolvedPageEq,
                stableLocationStart = sLoc,
                stableLocationEnd = eLoc,
                chapterAndParagraphCitation = rawPara,
                epubCfi = resolvedCfi
            )
        }
    }
}

data class BookChapter(
    val title: String,
    val pageNumber: Int, // 1-based page number where chapter begins
    val level: Int = 0,  // Hierarchy level (0 = chapter, 1 = section, 2 = subsection, etc.)
    val href: String = "",
    val fileKey: String = "",
    val anchorId: String = "",
    val pageLabel: String = "" // printed page label (PDF page labels / EPUB page list), display only
)

data class EpubPage(
    val globalIndex: Int,          // 0 to totalPages - 1
    val chapterIndex: Int,         // 0 to totalChapters - 1 (or -1 for Cover)
    val chapterTitle: String,
    val pageInChapter: Int,
    val totalPagesInChapter: Int,
    val paragraphs: List<String>,
    val footnotes: Map<String, String> = emptyMap(),
    val isCoverPage: Boolean = false,
    val coverBitmap: Bitmap? = null,
    val pageEquivalentLabel: String = "",
    val paragraphPageEquivalents: List<String> = emptyList(),
    val semanticBlocks: List<EpubSemanticBlock> = emptyList(),
    val footnoteEntries: Map<String, EpubFootnoteEntry> = emptyMap(),
    val anchorIds: Set<String> = emptySet(),
    val fileKey: String = "",
    val startParagraphNumber: Int = 1,
    val endParagraphNumber: Int = 1,
    val startStableLocation: Int = 1,
    val endStableLocation: Int = 1,
    val isFixedLayoutPage: Boolean = false,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val htmlContent: String = ""
)

data class EpubSpecInfo(
    val rawVersion: String,
    val displayBadge: String,
    val detailedLabel: String,
    val isFixedLayout: Boolean,
    val pageProgressionDirection: String = "ltr",
    val language: String = "en",
    val renditionOrientation: String = "auto",
    val renditionSpread: String = "auto"
)

object EpubParser {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val chapterParseDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4)

    // Small in-memory LRU cache (max 2 books) keyed by document ID or file path + lastModified
    private val parsedBookLruCache = object : LruCache<String, EpubBook>(2) {}

    fun getCachedEpubBook(cacheKey: String): EpubBook? {
        if (cacheKey.isBlank()) return null
        return synchronized(parsedBookLruCache) {
            parsedBookLruCache.get(cacheKey)
        }
    }

    fun putCachedEpubBook(cacheKey: String, book: EpubBook) {
        if (cacheKey.isBlank()) return
        synchronized(parsedBookLruCache) {
            parsedBookLruCache.put(cacheKey, book)
        }
    }

    /**
     * Reads a single audio/binary entry lazily on demand from [epubBook]'s source archive or returns
     * preloaded bytes if already present in [EpubBook.embeddedAudioBytes].
     */
    fun readAudioEntryBytesLazily(epubBook: EpubBook, audioPath: String): ByteArray? {
        val clean = audioPath.substringBefore("#").removePrefix("/")
        val fileName = clean.substringAfterLast("/")
        epubBook.embeddedAudioBytes[audioPath]?.takeIf { it.isNotEmpty() }?.let { return it }
        epubBook.embeddedAudioBytes[clean]?.takeIf { it.isNotEmpty() }?.let { return it }
        epubBook.embeddedAudioBytes[fileName]?.takeIf { it.isNotEmpty() }?.let { return it }
        if (epubBook.sourceArchivePath.isNotBlank()) {
            val archiveFile = File(epubBook.sourceArchivePath)
            if (archiveFile.exists() && archiveFile.isFile) {
                try {
                    ZipFile(archiveFile).use { zip ->
                        val directEntry = zip.getEntry(clean) ?: zip.getEntry(audioPath)
                        if (directEntry != null && !directEntry.isDirectory) {
                            return zip.getInputStream(directEntry).use { it.readBytes() }
                        }
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (!e.isDirectory && (
                                    e.name.equals(clean, ignoreCase = true) ||
                                        e.name.substringAfterLast("/").equals(fileName, ignoreCase = true)
                                    )
                            ) {
                                return zip.getInputStream(e).use { it.readBytes() }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    private val SKIPPED_LARGE_BINARY_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "mp4", "m4v", "webm", "mov", "avi", "mkv",
        "ttf", "otf", "woff", "woff2"
    )

    private fun shouldSkipBinaryEntryDuringParse(entryName: String): Boolean {
        val ext = entryName.substringAfterLast('.', "").lowercase()
        return ext in SKIPPED_LARGE_BINARY_EXTENSIONS
    }

    private fun isAudioOrVideoEntry(entryName: String): Boolean {
        val ext = entryName.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "mp4", "m4v", "webm")
    }

    private val RTL_LANGUAGE_PREFIXES = setOf(
        "ar", "he", "iw", "fa", "ur", "yi", "ji", "ps", "sd", "ug", "ku", "ckb", "dv", "syr", "arc"
    )

    internal fun resolvePageProgressionDirection(
        spineProgressionAttr: String,
        dcLanguage: String
    ): String {
        val cleanSpineDir = spineProgressionAttr.trim().lowercase()
        if (cleanSpineDir == "rtl" || cleanSpineDir == "ltr") {
            return cleanSpineDir
        }
        val langPrimary = dcLanguage.trim().lowercase().substringBefore("-").substringBefore("_")
        return if (langPrimary in RTL_LANGUAGE_PREFIXES) "rtl" else "ltr"
    }

    /**
     * Fast specification detector that reads ONLY `META-INF/container.xml` and the OPF package document
     * (`<package version="...">` and `<meta property="rendition:layout">`). Does not parse chapter content.
     */
    fun detectEpubSpecification(file: File): EpubSpecInfo {
        val fallback = EpubSpecInfo(
            rawVersion = "",
            displayBadge = "EPUB",
            detailedLabel = "EPUB",
            isFixedLayout = false,
            pageProgressionDirection = "ltr"
        )
        return try {
            if (!file.exists() || !file.isFile) return fallback
            file.inputStream().use { stream ->
                detectEpubSpecification(stream)
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    fun detectEpubSpecification(inputStream: InputStream): EpubSpecInfo {
        val fallback = EpubSpecInfo(
            rawVersion = "",
            displayBadge = "EPUB",
            detailedLabel = "EPUB",
            isFixedLayout = false,
            pageProgressionDirection = "ltr"
        )
        return try {
            var containerBytes: ByteArray? = null
            val opfCandidates = mutableMapOf<String, ByteArray>()

            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (name.equals("META-INF/container.xml", ignoreCase = true)) {
                            containerBytes = zis.readBytes()
                        } else if (name.endsWith(".opf", ignoreCase = true)) {
                            opfCandidates[name] = zis.readBytes()
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val opfPath = if (containerBytes != null) {
                parseOpfPathFromContainer(containerBytes!!)
            } else {
                opfCandidates.keys.firstOrNull() ?: "content.opf"
            }

            val opfBytes = opfCandidates[opfPath]
                ?: opfCandidates.entries.firstOrNull { it.key.equals(opfPath.removePrefix("/"), ignoreCase = true) }?.value
                ?: opfCandidates.values.firstOrNull()
                ?: return fallback

            parseEpubSpecFromOpfBytes(opfBytes, fallback)
        } catch (_: Throwable) {
            fallback
        }
    }

    internal fun parseViewportDimensionsFromHtml(html: String): Pair<Int, Int> {
        val viewportMeta = Regex(
            "<meta\\b[^>]*\\bname\\s*=\\s*[\"']viewport[\"'][^>]*\\bcontent\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groups?.get(1)?.value
            ?: Regex(
                "<meta\\b[^>]*\\bcontent\\s*=\\s*[\"']([^\"']+)[\"'][^>]*\\bname\\s*=\\s*[\"']viewport[\"']",
                RegexOption.IGNORE_CASE
            ).find(html)?.groups?.get(1)?.value
            ?: ""
        if (viewportMeta.isNotEmpty()) {
            val w = Regex("\\bwidth\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(viewportMeta)?.groups?.get(1)?.value?.toIntOrNull() ?: 0
            val h = Regex("\\bheight\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(viewportMeta)?.groups?.get(1)?.value?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) return w to h
        }
        val svgViewBox = Regex(
            "<svg\\b[^>]*\\bviewBox\\s*=\\s*[\"']\\s*[\\d.\\-]+\\s+[\\d.\\-]+\\s+(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)\\s*[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)
        if (svgViewBox != null) {
            val w = svgViewBox.groups[1]?.value?.toFloatOrNull()?.toInt() ?: 0
            val h = svgViewBox.groups[2]?.value?.toFloatOrNull()?.toInt() ?: 0
            if (w > 0 && h > 0) return w to h
        }
        return 0 to 0
    }

    private fun parseEpubSpecFromOpfBytes(opfBytes: ByteArray, fallback: EpubSpecInfo): EpubSpecInfo {
        var rawVersion = ""
        var renditionLayout = ""
        var renditionOrientation = ""
        var renditionSpread = ""
        var spineProgressionDirection = ""
        var dcLanguage = ""

        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(opfBytes), "UTF-8")

            var eventType = parser.eventType
            var insideMetaProperty = ""
            var insideLanguageTag = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = (parser.name ?: "").substringAfterLast(":").lowercase()
                        if (tag == "package" && rawVersion.isEmpty()) {
                            rawVersion = (parser.getAttributeValue(null, "version") ?: "").trim()
                        } else if (tag == "spine") {
                            val ppd = (parser.getAttributeValue(null, "page-progression-direction") ?: "").trim()
                            if (ppd.isNotEmpty() && spineProgressionDirection.isEmpty()) {
                                spineProgressionDirection = ppd
                            }
                        } else if (tag == "language") {
                            insideLanguageTag = true
                        } else if (tag == "meta") {
                            val prop = (parser.getAttributeValue(null, "property") ?: "").trim().lowercase()
                            val nameAttr = (parser.getAttributeValue(null, "name") ?: "").trim()
                            val contentAttr = (parser.getAttributeValue(null, "content") ?: "").trim()
                            when (prop) {
                                "rendition:layout" -> {
                                    insideMetaProperty = "rendition:layout"
                                    if (contentAttr.isNotEmpty() && renditionLayout.isEmpty()) {
                                        renditionLayout = contentAttr
                                    }
                                }
                                "rendition:orientation" -> {
                                    insideMetaProperty = "rendition:orientation"
                                    if (contentAttr.isNotEmpty() && renditionOrientation.isEmpty()) {
                                        renditionOrientation = contentAttr
                                    }
                                }
                                "rendition:spread" -> {
                                    insideMetaProperty = "rendition:spread"
                                    if (contentAttr.isNotEmpty() && renditionSpread.isEmpty()) {
                                        renditionSpread = contentAttr
                                    }
                                }
                            }
                            if (nameAttr.equals("fixed-layout", ignoreCase = true) && contentAttr.equals("true", ignoreCase = true)) {
                                if (renditionLayout.isEmpty()) renditionLayout = "pre-paginated"
                            } else if (nameAttr.equals("orientation-lock", ignoreCase = true) && contentAttr.isNotEmpty()) {
                                if (renditionOrientation.isEmpty()) renditionOrientation = contentAttr
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (insideMetaProperty) {
                                "rendition:layout" -> renditionLayout = text
                                "rendition:orientation" -> renditionOrientation = text
                                "rendition:spread" -> renditionSpread = text
                            }
                            if (insideLanguageTag && dcLanguage.isEmpty()) {
                                dcLanguage = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = (parser.name ?: "").substringAfterLast(":").lowercase()
                        if (tag == "meta") {
                            insideMetaProperty = ""
                        } else if (tag == "language") {
                            insideLanguageTag = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Throwable) {
        }

        if (rawVersion.isEmpty() || renditionLayout.isEmpty() || renditionOrientation.isEmpty() || renditionSpread.isEmpty() || spineProgressionDirection.isEmpty() || dcLanguage.isEmpty()) {
            val opfString = try { String(opfBytes, Charsets.UTF_8) } catch (_: Throwable) { "" }
            if (rawVersion.isEmpty()) {
                rawVersion = Regex("<(?:[a-zA-Z0-9]+:)?package\\b[^>]*\\bversion\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (renditionLayout.isEmpty()) {
                renditionLayout = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']rendition:layout[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (renditionOrientation.isEmpty()) {
                renditionOrientation = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']rendition:orientation[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (renditionSpread.isEmpty()) {
                renditionSpread = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']rendition:spread[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (spineProgressionDirection.isEmpty()) {
                spineProgressionDirection = Regex(
                    "<(?:[a-zA-Z0-9]+:)?spine\\b[^>]*\\bpage-progression-direction\\s*=\\s*[\"']([^\"']+)[\"']",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (dcLanguage.isEmpty()) {
                dcLanguage = Regex(
                    "<(?:[a-zA-Z0-9]+:)?language\\b[^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?language>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
        }

        val resolvedPageProgression = resolvePageProgressionDirection(spineProgressionDirection, dcLanguage)
        val resolvedLang = dcLanguage.ifBlank { "en" }
        val resolvedOrientation = renditionOrientation.lowercase().ifBlank { "auto" }
        val resolvedSpread = renditionSpread.lowercase().ifBlank { "auto" }
        val isFixedLayout = renditionLayout.equals("pre-paginated", ignoreCase = true)
        return when {
            rawVersion.startsWith("2.") || rawVersion == "2" -> EpubSpecInfo(
                rawVersion = rawVersion,
                displayBadge = "EPUB 2",
                detailedLabel = "EPUB $rawVersion (Reflowable)",
                isFixedLayout = false,
                pageProgressionDirection = resolvedPageProgression,
                language = resolvedLang,
                renditionOrientation = resolvedOrientation,
                renditionSpread = resolvedSpread
            )
            rawVersion.startsWith("3.") || rawVersion == "3" -> {
                if (isFixedLayout) {
                    EpubSpecInfo(
                        rawVersion = rawVersion,
                        displayBadge = "EPUB 3 FXL",
                        detailedLabel = "EPUB $rawVersion (Fixed-Layout)",
                        isFixedLayout = true,
                        pageProgressionDirection = resolvedPageProgression,
                        language = resolvedLang,
                        renditionOrientation = resolvedOrientation,
                        renditionSpread = resolvedSpread
                    )
                } else {
                    EpubSpecInfo(
                        rawVersion = rawVersion,
                        displayBadge = "EPUB 3",
                        detailedLabel = "EPUB $rawVersion (Reflowable)",
                        isFixedLayout = false,
                        pageProgressionDirection = resolvedPageProgression,
                        language = resolvedLang,
                        renditionOrientation = resolvedOrientation,
                        renditionSpread = resolvedSpread
                    )
                }
            }
            else -> fallback.copy(
                isFixedLayout = isFixedLayout,
                pageProgressionDirection = resolvedPageProgression,
                language = resolvedLang,
                renditionOrientation = resolvedOrientation,
                renditionSpread = resolvedSpread
            )
        }
    }

    /**
     * Parses an EPUB file with caching and lazy audio/binary entry loading.
     */
    fun parseEpub(file: File, cacheKey: String = ""): EpubBook {
        val effectiveKey = cacheKey.ifBlank { "${file.absolutePath}:${file.lastModified()}:${file.length()}" }
        getCachedEpubBook(effectiveKey)?.let { cached ->
            if (DEBUG_LOAD_PERF) Log.d(LOAD_PERF_TAG, "EPUB parseEpub cache hit for $effectiveKey (0 ms)")
            return cached
        }
        val parsed = file.inputStream().use { stream ->
            parseEpub(stream, sourceArchivePath = file.absolutePath)
        }
        putCachedEpubBook(effectiveKey, parsed)
        return parsed
    }

    /**
     * Parses an EPUB input stream and extracts book metadata, chapters, and cover image.
     * Large audio, video, and font binary entries are NOT read into memory; only their entry names
     * are recorded for lazy playback loading.
     */
    fun parseEpub(inputStream: InputStream, sourceArchivePath: String = ""): EpubBook {
        val totalStartNs = System.nanoTime()
        val stageMs = LinkedHashMap<String, Long>()

        val tZipStart = System.nanoTime()
        val files = mutableMapOf<String, ByteArray>()
        val binaryEntryNames = LinkedHashSet<String>()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name
                    if (shouldSkipBinaryEntryDuringParse(entryName)) {
                        if (isAudioOrVideoEntry(entryName)) {
                            binaryEntryNames.add(entryName)
                        }
                        zis.closeEntry()
                    } else {
                        files[entryName] = zis.readBytes()
                    }
                }
                entry = zis.nextEntry
            }
        }
        stageMs["zip_read"] = (System.nanoTime() - tZipStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(LOAD_PERF_TAG, "EPUB stage zip_read: ${stageMs["zip_read"]} ms (${files.size} entries read, ${binaryEntryNames.size} audio/video entries deferred)")

        val tOpfNavStart = System.nanoTime()
        // 1. Locate container.xml to find OPF path
        val containerXml = files["META-INF/container.xml"]
        val opfPath = if (containerXml != null) {
            parseOpfPathFromContainer(containerXml)
        } else {
            files.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) } ?: "content.opf"
        }

        val opfBytes = files[opfPath] ?: files.entries.firstOrNull { it.key.endsWith(".opf") }?.value

        var title = "Unknown Title"
        var author = "Unknown Author"
        var description = ""
        var dcLanguage = ""
        var spinePageProgressionAttr = ""
        var renditionLayout = ""
        var renditionOrientation = ""
        var renditionSpread = ""
        var coverId: String? = null
        val manifest = mutableMapOf<String, String>() // id to href
        val itemMediaOverlays = mutableMapOf<String, String>() // item id to media-overlay smil item id
        var mediaOverlayDuration = ""
        val spine = mutableListOf<String>() // list of manifest item ids
        val opfGuideReferences = mutableListOf<EpubLandmarkItem>()

        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

        if (opfBytes != null) {
            val opfString = String(opfBytes, Charsets.UTF_8)
            try {
                val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
                val parser = factory.newPullParser()
                parser.setInput(ByteArrayInputStream(opfBytes), "UTF-8")

                var eventType = parser.eventType
                var currentTag = ""
                var currentMetaProperty = ""
                var currentMetaRefines = ""

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            val rawName = parser.name ?: ""
                            currentTag = rawName.substringAfterLast(":").lowercase()
                            when (currentTag) {
                                "spine" -> {
                                    val ppd = (parser.getAttributeValue(null, "page-progression-direction") ?: "").trim()
                                    if (ppd.isNotEmpty() && spinePageProgressionAttr.isEmpty()) {
                                        spinePageProgressionAttr = ppd
                                    }
                                }
                                "item" -> {
                                    val id = parser.getAttributeValue(null, "id") ?: ""
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    val properties = parser.getAttributeValue(null, "properties") ?: ""
                                    val mediaOverlayId = (parser.getAttributeValue(null, "media-overlay") ?: "").trim()
                                    if (id.isNotEmpty() && href.isNotEmpty()) {
                                        val decodedHref = try { java.net.URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                                        manifest[id] = opfDir + decodedHref.removePrefix("/")
                                    }
                                    if (id.isNotEmpty() && mediaOverlayId.isNotEmpty()) {
                                        itemMediaOverlays[id] = mediaOverlayId
                                    }
                                    if (properties.contains("cover-image", ignoreCase = true) ||
                                        id.equals("cover", ignoreCase = true) ||
                                        id.equals("cover-image", ignoreCase = true) ||
                                        id.contains("cover", ignoreCase = true)
                                    ) {
                                        if (coverId == null || properties.contains("cover-image", ignoreCase = true)) {
                                            coverId = id
                                        }
                                    }
                                }
                                "itemref" -> {
                                    val idref = parser.getAttributeValue(null, "idref") ?: ""
                                    val itemRefProps = parser.getAttributeValue(null, "properties") ?: ""
                                    if (itemRefProps.contains("rendition:layout-pre-paginated", ignoreCase = true) && renditionLayout.isEmpty()) {
                                        renditionLayout = "pre-paginated"
                                    }
                                    if (idref.isNotEmpty()) {
                                        spine.add(idref)
                                    }
                                }
                                "meta" -> {
                                    val prop = (parser.getAttributeValue(null, "property") ?: "").trim().lowercase()
                                    val refines = (parser.getAttributeValue(null, "refines") ?: "").trim()
                                    val name = parser.getAttributeValue(null, "name")
                                    val content = parser.getAttributeValue(null, "content")
                                    currentMetaProperty = prop
                                    currentMetaRefines = refines
                                    if (name?.equals("cover", ignoreCase = true) == true && content != null) {
                                        coverId = content
                                    } else if (name?.equals("fixed-layout", ignoreCase = true) == true && content?.equals("true", ignoreCase = true) == true) {
                                        if (renditionLayout.isEmpty()) renditionLayout = "pre-paginated"
                                    } else if (prop == "rendition:layout" && !content.isNullOrBlank() && renditionLayout.isEmpty()) {
                                        renditionLayout = content.trim()
                                    } else if (prop == "rendition:orientation" && !content.isNullOrBlank() && renditionOrientation.isEmpty()) {
                                        renditionOrientation = content.trim()
                                    } else if (prop == "rendition:spread" && !content.isNullOrBlank() && renditionSpread.isEmpty()) {
                                        renditionSpread = content.trim()
                                    } else if (prop == "media:duration" && !content.isNullOrBlank() && (mediaOverlayDuration.isEmpty() || refines.isEmpty())) {
                                        mediaOverlayDuration = content.trim()
                                    }
                                }
                                "reference" -> {
                                    val refType = (parser.getAttributeValue(null, "type") ?: "").trim()
                                    val refTitle = (parser.getAttributeValue(null, "title") ?: "").trim()
                                    val refHref = (parser.getAttributeValue(null, "href") ?: "").trim()
                                    val mappedLandmark = mapRawLandmarkToItem(refType, refTitle, refHref)
                                    if (mappedLandmark != null) {
                                        opfGuideReferences.add(mappedLandmark)
                                    }
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text?.trim() ?: ""
                            if (text.isNotEmpty()) {
                                when (currentTag) {
                                    "title" -> if (title == "Unknown Title") title = text
                                    "creator" -> if (author == "Unknown Author") author = text
                                    "description" -> if (description.isEmpty()) description = text
                                    "language" -> if (dcLanguage.isEmpty()) dcLanguage = text
                                    "meta" -> {
                                        when (currentMetaProperty) {
                                            "rendition:layout" -> if (renditionLayout.isEmpty()) renditionLayout = text
                                            "rendition:orientation" -> if (renditionOrientation.isEmpty()) renditionOrientation = text
                                            "rendition:spread" -> if (renditionSpread.isEmpty()) renditionSpread = text
                                            "media:duration" -> if (mediaOverlayDuration.isEmpty() || currentMetaRefines.isEmpty()) {
                                                mediaOverlayDuration = text
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            currentTag = ""
                            currentMetaProperty = ""
                            currentMetaRefines = ""
                        }
                    }
                    eventType = parser.next()
                }
            } catch (_: Exception) {
                // Fallback regex parsing if XML has malformed declarations or entities
                if (spinePageProgressionAttr.isEmpty()) {
                    spinePageProgressionAttr = Regex(
                        "<(?:[a-zA-Z0-9]+:)?spine\\b[^>]*\\bpage-progression-direction\\s*=\\s*[\"']([^\"']+)[\"']",
                        RegexOption.IGNORE_CASE
                    ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
                }
                if (dcLanguage.isEmpty()) {
                    dcLanguage = Regex(
                        "<(?:[a-zA-Z0-9]+:)?language\\b[^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?language>",
                        RegexOption.IGNORE_CASE
                    ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
                }
                val itemRegex = Regex("<(?:[a-zA-Z0-9]+:)?item\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
                for (m in itemRegex.findAll(opfString)) {
                    val attrs = m.groups[1]?.value ?: ""
                    val id = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
                    val href = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
                    val moId = Regex("\\bmedia-overlay\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        val decodedHref = try { java.net.URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                        manifest[id] = opfDir + decodedHref.removePrefix("/")
                    }
                    if (id.isNotEmpty() && moId.isNotEmpty()) {
                        itemMediaOverlays[id] = moId
                    }
                }
                val itemRefRegex = Regex("<(?:[a-zA-Z0-9]+:)?itemref\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
                for (m in itemRefRegex.findAll(opfString)) {
                    val attrs = m.groups[1]?.value ?: ""
                    val idref = Regex("\\bidref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
                    if (idref.isNotEmpty()) {
                        spine.add(idref)
                    }
                }
                val refRegex = Regex("<(?:[a-zA-Z0-9]+:)?reference\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
                for (m in refRegex.findAll(opfString)) {
                    val attrs = m.groups[1]?.value ?: ""
                    val refType = Regex("\\btype\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value.orEmpty()
                    val refTitle = Regex("\\btitle\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value.orEmpty()
                    val refHref = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value.orEmpty()
                    val mappedLandmark = mapRawLandmarkToItem(refType, refTitle, refHref)
                    if (mappedLandmark != null) {
                        opfGuideReferences.add(mappedLandmark)
                    }
                }
            }
            if (renditionLayout.isEmpty()) {
                renditionLayout = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']rendition:layout[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (renditionOrientation.isEmpty()) {
                renditionOrientation = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']rendition:orientation[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (renditionSpread.isEmpty()) {
                renditionSpread = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']rendition:spread[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
            if (mediaOverlayDuration.isEmpty()) {
                mediaOverlayDuration = Regex(
                    "<(?:[a-zA-Z0-9]+:)?meta\\b[^>]*\\bproperty\\s*=\\s*[\"']media:duration[\"'][^>]*>([^<]*)</(?:[a-zA-Z0-9]+:)?meta>",
                    RegexOption.IGNORE_CASE
                ).find(opfString)?.groups?.get(1)?.value?.trim().orEmpty()
            }
        }

        val isFixedLayoutBook = renditionLayout.equals("pre-paginated", ignoreCase = true)

        // Check for toc.ncx or EPUB3 nav.xhtml in files to get rich in-book chapter names, hierarchical TOC, and EPUB Page List metadata.
        // Print page equivalents priority order:
        //   a. EPUB 3 <nav epub:type="page-list">
        //   b. Inline elements with epub:type="pagebreak" or role="doc-pagebreak" (label from title attribute, else text content)
        //   c. NCX <pageList>
        val tocNcx = files.entries.firstOrNull { it.key.endsWith(".ncx", ignoreCase = true) }?.value
        val ncxTitles = mutableMapOf<String, String>()
        var epub3NavHierarchy: List<EpubTocItem> = emptyList()
        var ncxNavHierarchy: List<EpubTocItem> = emptyList()
        var epub3Landmarks: List<EpubLandmarkItem> = emptyList()
        val epub3PageListByFile = LinkedHashMap<String, MutableMap<String, String>>()
        val ncxPageListByFile = LinkedHashMap<String, MutableMap<String, String>>()
        val adobePageMapByFile = LinkedHashMap<String, MutableMap<String, String>>()

        if (tocNcx != null) {
            ncxTitles.putAll(parseNcxTitles(tocNcx))
            ncxNavHierarchy = EpubSemanticHelper.parseNcxHierarchy(tocNcx)
            for ((fileKey, anchors) in parseNcxPageList(tocNcx)) {
                val target = ncxPageListByFile.getOrPut(fileKey) { LinkedHashMap() }
                target.putAll(anchors)
            }
        }
        val navHtmlEntries = files.entries.filter {
            (it.key.contains("nav", ignoreCase = true) || it.key.contains("toc", ignoreCase = true) || it.key.contains("page", ignoreCase = true)) &&
                (it.key.endsWith(".xhtml", ignoreCase = true) || it.key.endsWith(".html", ignoreCase = true) || it.key.endsWith(".htm", ignoreCase = true))
        }
        for (navEntry in navHtmlEntries) {
            val navHtmlStr = String(navEntry.value, Charsets.UTF_8)
            if (navEntry.key.contains("nav", ignoreCase = true) || navEntry.key.contains("toc", ignoreCase = true)) {
                val navTitles = parseEpub3NavTitles(navHtmlStr)
                for ((k, v) in navTitles) {
                    if (!ncxTitles.containsKey(k)) {
                        ncxTitles[k] = v
                    }
                }
                if (epub3NavHierarchy.isEmpty()) {
                    val parsedNav = EpubSemanticHelper.parseEpub3NavHierarchy(navHtmlStr, navEntry.key)
                    if (parsedNav.isNotEmpty()) {
                        epub3NavHierarchy = parsedNav
                    }
                }
            }
            if (epub3Landmarks.isEmpty()) {
                val parsedLandmarks = parseEpub3Landmarks(navHtmlStr)
                if (parsedLandmarks.isNotEmpty()) {
                    epub3Landmarks = parsedLandmarks
                }
            }
            for ((fileKey, anchors) in parseEpub3NavPageList(navHtmlStr)) {
                val target = epub3PageListByFile.getOrPut(fileKey) { LinkedHashMap() }
                target.putAll(anchors)
            }
        }
        val pageMapEntry = files.entries.firstOrNull {
            it.key.endsWith("page-map.xml", ignoreCase = true) || it.key.endsWith("pagemap.xml", ignoreCase = true)
        }?.value
        if (pageMapEntry != null) {
            for ((fileKey, anchors) in parseAdobePageMap(pageMapEntry)) {
                val target = adobePageMapByFile.getOrPut(fileKey) { LinkedHashMap() }
                target.putAll(anchors)
            }
        }

        // Priority (a) EPUB 3 <nav epub:type="page-list"> takes precedence over (c) NCX <pageList>,
        // while (b) inline epub:type="pagebreak" / role="doc-pagebreak" elements are extracted directly from spine files
        // and merged above NCX <pageList> when EPUB 3 nav page-list is absent.
        val pageListByFile = LinkedHashMap<String, MutableMap<String, String>>()
        val primaryExternalPageList = when {
            epub3PageListByFile.isNotEmpty() -> epub3PageListByFile
            ncxPageListByFile.isNotEmpty() -> ncxPageListByFile
            else -> adobePageMapByFile
        }
        for ((fileKey, anchors) in primaryExternalPageList) {
            pageListByFile.getOrPut(fileKey) { LinkedHashMap() }.putAll(anchors)
        }

        // Comprehensive cover extraction:
        var coverBitmap: Bitmap? = null

        // Strategy A: Via coverId from OPF manifest
        val coverHref = coverId?.let { manifest[it] }
        if (coverHref != null) {
            val bytes = findFileBytes(files, coverHref)
            if (bytes != null) {
                coverBitmap = decodeSampledBitmap(bytes)
                // If it's an XHTML/HTML wrapper, look for embedded image inside it
                if (coverBitmap == null) {
                    val html = String(bytes, Charsets.UTF_8)
                    val imgMatch = Regex("(src|xlink:href)=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)
                    val imgSrc = imgMatch?.groups?.get(2)?.value
                    if (imgSrc != null) {
                        val baseDir = if (coverHref.contains("/")) coverHref.substringBeforeLast("/") + "/" else ""
                        val resolved = baseDir + imgSrc.removePrefix("/")
                        val imgBytes = findFileBytes(files, resolved) ?: findFileBytes(files, imgSrc)
                        if (imgBytes != null) {
                            coverBitmap = decodeSampledBitmap(imgBytes)
                        }
                    }
                }
            }
        }

        // Strategy B: In manifest values matching cover image
        if (coverBitmap == null) {
            val candidateHref = manifest.values.firstOrNull { href ->
                href.contains("cover", ignoreCase = true) && isImageExtension(href)
            }
            if (candidateHref != null) {
                val bytes = findFileBytes(files, candidateHref)
                if (bytes != null) {
                    coverBitmap = decodeSampledBitmap(bytes)
                }
            }
        }

        // Strategy C: Any file in ZIP containing "cover" and ending with image extension
        if (coverBitmap == null) {
            val coverEntry = files.entries.firstOrNull { entry ->
                entry.key.contains("cover", ignoreCase = true) && isImageExtension(entry.key)
            }
            if (coverEntry != null) {
                coverBitmap = decodeSampledBitmap(coverEntry.value)
            }
        }

        // Strategy D: Inspect first spine HTML document (often cover.xhtml or titlepage.xhtml)
        if (coverBitmap == null && spine.isNotEmpty()) {
            val firstItemId = spine.firstOrNull()
            val firstHref = manifest[firstItemId]
            if (firstHref != null) {
                val htmlBytes = findFileBytes(files, firstHref)
                if (htmlBytes != null) {
                    val html = String(htmlBytes, Charsets.UTF_8)
                    // Match <img src="..." or <image xlink:href="..."
                    val imgMatch = Regex("(src|xlink:href)=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(html)
                    val imgSrc = imgMatch?.groups?.get(2)?.value
                    if (imgSrc != null && isImageExtension(imgSrc)) {
                        val baseDir = if (firstHref.contains("/")) firstHref.substringBeforeLast("/") + "/" else ""
                        val resolved = baseDir + imgSrc.removePrefix("/")
                        val bytes = findFileBytes(files, resolved) ?: findFileBytes(files, imgSrc)
                        if (bytes != null) {
                            coverBitmap = decodeSampledBitmap(bytes)
                        }
                    }
                }
            }
        }

        // Strategy E: Any image file in the EPUB as fallback
        if (coverBitmap == null) {
            val anyImage = files.entries.firstOrNull { isImageExtension(it.key) }
            if (anyImage != null) {
                coverBitmap = decodeSampledBitmap(anyImage.value)
            }
        }

        // Strategy F: Generate elegant book cover art if none found
        if (coverBitmap == null) {
            coverBitmap = generateBookCoverBitmap(title, author)
        }
        stageMs["opf_nav_parse"] = (System.nanoTime() - tOpfNavStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(LOAD_PERF_TAG, "EPUB stage opf_nav_parse: ${stageMs["opf_nav_parse"]} ms")

        // Extract chapters in spine order, running per-chapter HTML & semantic extraction in parallel (max 4 coroutines)
        val chapters = mutableListOf<EpubChapter>()
        val itemIdsToProcess = if (spine.isNotEmpty()) spine else manifest.keys.toList()
        val extractedPageListEntries = mutableListOf<EpubPageListEntry>()

        var anyPageEquivalentDetected = pageListByFile.isNotEmpty()

        data class SpineChapterInput(
            val orderIndex: Int,
            val itemId: String,
            val href: String,
            val fileKey: String,
            val htmlString: String,
            val ncxTitle: String?,
            val filePageListAnchors: LinkedHashMap<String, String>
        )

        data class ParallelChapterPrep(
            val input: SpineChapterInput,
            val inlineAnchors: Map<String, String>,
            val vpWidth: Int,
            val vpHeight: Int
        )

        val spineInputs = itemIdsToProcess.mapIndexedNotNull { idx, itemId ->
            val href = manifest[itemId] ?: return@mapIndexedNotNull null
            val chapterBytes = findFileBytes(files, href) ?: return@mapIndexedNotNull null
            val htmlString = String(chapterBytes, Charsets.UTF_8)
            val fileKey = href.substringAfterLast("/")
            val ncxTitle = ncxTitles[fileKey]
            SpineChapterInput(
                orderIndex = idx,
                itemId = itemId,
                href = href,
                fileKey = fileKey,
                htmlString = htmlString,
                ncxTitle = ncxTitle,
                filePageListAnchors = LinkedHashMap()
            )
        }

        val tChWallStart = System.nanoTime()
        val preppedInputs = if (spineInputs.size > 1) {
            runBlocking(chapterParseDispatcher) {
                spineInputs.map { inp ->
                    async {
                        val inlineAnchors = extractInlinePageBreakAnchors(inp.htmlString)
                        val (vpW, vpH) = parseViewportDimensionsFromHtml(inp.htmlString)
                        ParallelChapterPrep(inp, inlineAnchors, vpW, vpH)
                    }
                }.awaitAll()
            }
        } else {
            spineInputs.map { inp ->
                val inlineAnchors = extractInlinePageBreakAnchors(inp.htmlString)
                val (vpW, vpH) = parseViewportDimensionsFromHtml(inp.htmlString)
                ParallelChapterPrep(inp, inlineAnchors, vpW, vpH)
            }
        }

        // Resolve merged page-list anchors per file in spine order
        val resolvedPageAnchorsPerInput = ArrayList<LinkedHashMap<String, String>>(preppedInputs.size)
        for (prep in preppedInputs) {
            val fileKey = prep.input.fileKey
            val epub3Anchors = epub3PageListByFile[fileKey] ?: epub3PageListByFile.entries.firstOrNull {
                it.key.equals(fileKey, ignoreCase = true)
            }?.value ?: emptyMap()
            val ncxAnchors = ncxPageListByFile[fileKey] ?: ncxPageListByFile.entries.firstOrNull {
                it.key.equals(fileKey, ignoreCase = true)
            }?.value ?: adobePageMapByFile[fileKey] ?: emptyMap()

            val filePageListAnchors = LinkedHashMap<String, String>()
            for ((k, v) in ncxAnchors) filePageListAnchors[k] = v
            for ((k, v) in prep.inlineAnchors) filePageListAnchors[k] = v
            for ((k, v) in epub3Anchors) filePageListAnchors[k] = v
            resolvedPageAnchorsPerInput.add(filePageListAnchors)
        }

        data class ParallelChapterParsed(
            val prep: ParallelChapterPrep,
            val filePageListAnchors: LinkedHashMap<String, String>,
            val explicitFileStartPage: String?,
            val extractedResult: ExtractedChapterContentResult
        )

        // Parse each chapter's readable content in parallel with bounded 4-coroutine dispatcher
        val parallelParsedChapters = if (preppedInputs.size > 1) {
            runBlocking(chapterParseDispatcher) {
                preppedInputs.mapIndexed { idx, prep ->
                    async {
                        val anchors = resolvedPageAnchorsPerInput[idx]
                        val explicitStart = anchors[""]?.takeIf { it.isNotBlank() }
                        val res = extractReadableContentWithPageMarkers(
                            html = prep.input.htmlString,
                            chapterIndex = idx + 1,
                            externalPageListAnchors = anchors,
                            initialPageLabel = explicitStart ?: ""
                        )
                        ParallelChapterParsed(prep, anchors, explicitStart, res)
                    }
                }.awaitAll()
            }
        } else {
            preppedInputs.mapIndexed { idx, prep ->
                val anchors = resolvedPageAnchorsPerInput[idx]
                val explicitStart = anchors[""]?.takeIf { it.isNotBlank() }
                val res = extractReadableContentWithPageMarkers(
                    html = prep.input.htmlString,
                    chapterIndex = idx + 1,
                    externalPageListAnchors = anchors,
                    initialPageLabel = explicitStart ?: ""
                )
                ParallelChapterParsed(prep, anchors, explicitStart, res)
            }
        }
        stageMs["per_chapter_parse"] = (System.nanoTime() - tChWallStart) / 1_000_000L

        // Apply exact sequential spine order for chapterCounter and carryOverPageLabel, then build semantic blocks in parallel
        data class ReadyChapterForSemantics(
            val itemId: String,
            val chapterNumber: Int,
            val finalChapterTitle: String,
            val htmlString: String,
            val fileKey: String,
            val paragraphs: List<String>,
            val footnotes: Map<String, String>,
            val paragraphPageLabels: List<String>,
            val vpWidth: Int,
            val vpHeight: Int
        )

        val readyChapters = ArrayList<ReadyChapterForSemantics>(parallelParsedChapters.size)
        var chapterCounter = 1
        var carryOverPageLabel = ""

        for (parsedItem in parallelParsedChapters) {
            val filePageListAnchors = parsedItem.filePageListAnchors
            val fileKey = parsedItem.prep.input.fileKey
            val currentSpineIdx = readyChapters.size
            for ((anchorKey, lbl) in filePageListAnchors) {
                if (lbl.isNotBlank()) {
                    extractedPageListEntries.add(
                        EpubPageListEntry(
                            label = lbl,
                            spineFileKey = fileKey,
                            anchorId = anchorKey,
                            spineIndex = currentSpineIdx
                        )
                    )
                }
            }

            val initialPageForFile = parsedItem.explicitFileStartPage ?: carryOverPageLabel
            val baseRes = parsedItem.extractedResult
            // If carryOverPageLabel was non-empty and leading paragraphs had blank page labels, backfill them identically to sequential execution
            val rawParagraphs = baseRes.paragraphs
            val rawParagraphPageLabels = if (initialPageForFile.isNotBlank() && parsedItem.explicitFileStartPage == null && baseRes.paragraphPageEquivalents.any { it.isBlank() }) {
                val updatedLabels = baseRes.paragraphPageEquivalents.toMutableList()
                for (i in updatedLabels.indices) {
                    if (updatedLabels[i].isBlank()) {
                        updatedLabels[i] = initialPageForFile
                    } else {
                        break
                    }
                }
                updatedLabels
            } else {
                baseRes.paragraphPageEquivalents
            }
            val endingPageLabel = baseRes.endingPageLabel.ifBlank {
                rawParagraphPageLabels.lastOrNull { it.isNotBlank() }.orEmpty().ifBlank { initialPageForFile }
            }
            if (endingPageLabel.isNotBlank()) {
                carryOverPageLabel = endingPageLabel
            }
            if (rawParagraphPageLabels.any { it.isNotBlank() } || filePageListAnchors.isNotEmpty()) {
                anyPageEquivalentDetected = true
            }
            val extractedTitle = if (baseRes.extractedTitle == "Chapter ${parsedItem.prep.input.orderIndex + 1}" && chapterCounter != parsedItem.prep.input.orderIndex + 1) {
                "Chapter $chapterCounter"
            } else {
                baseRes.extractedTitle
            }
            val finalChapterTitle = parsedItem.prep.input.ncxTitle?.takeIf { it.isNotBlank() } ?: extractedTitle

            val paragraphs = if (rawParagraphs.isEmpty() && isFixedLayoutBook) {
                listOf(finalChapterTitle.ifBlank { "Page $chapterCounter" })
            } else {
                rawParagraphs
            }
            val paragraphPageLabels = if (rawParagraphPageLabels.isEmpty() && isFixedLayoutBook && paragraphs.isNotEmpty()) {
                listOf(filePageListAnchors.values.firstOrNull { it.isNotBlank() }.orEmpty())
            } else {
                rawParagraphPageLabels
            }

            if (paragraphs.isNotEmpty()) {
                readyChapters.add(
                    ReadyChapterForSemantics(
                        itemId = parsedItem.prep.input.itemId,
                        chapterNumber = chapterCounter,
                        finalChapterTitle = finalChapterTitle,
                        htmlString = parsedItem.prep.input.htmlString,
                        fileKey = fileKey,
                        paragraphs = paragraphs,
                        footnotes = baseRes.footnotes,
                        paragraphPageLabels = paragraphPageLabels,
                        vpWidth = parsedItem.prep.vpWidth,
                        vpHeight = parsedItem.prep.vpHeight
                    )
                )
                chapterCounter++
            }
        }

        val tSemWallStart = System.nanoTime()
        val builtChapters = if (readyChapters.size > 1) {
            runBlocking(chapterParseDispatcher) {
                readyChapters.map { rc ->
                    async {
                        val semExtraction = EpubSemanticHelper.extractSemanticStructureForChapter(
                            rawHtml = rc.htmlString,
                            fileKey = rc.fileKey,
                            chapterIndex = rc.chapterNumber,
                            chapterTitle = rc.finalChapterTitle,
                            existingParagraphs = rc.paragraphs,
                            existingPageEquivalents = rc.paragraphPageLabels,
                            existingFootnotes = rc.footnotes
                        )
                        EpubChapter(
                            id = rc.itemId,
                            title = rc.finalChapterTitle,
                            content = rc.paragraphs.joinToString("\n\n"),
                            paragraphs = rc.paragraphs,
                            footnotes = rc.footnotes,
                            htmlContent = if (isFixedLayoutBook) rc.htmlString else "",
                            paragraphPageEquivalents = rc.paragraphPageLabels,
                            fileKey = rc.fileKey,
                            semanticBlocks = semExtraction.semanticBlocks,
                            footnoteEntries = semExtraction.footnoteEntries,
                            anchorToBlockIndex = semExtraction.anchorToBlockIndex,
                            viewportWidth = rc.vpWidth,
                            viewportHeight = rc.vpHeight
                        )
                    }
                }.awaitAll()
            }
        } else {
            readyChapters.map { rc ->
                val semExtraction = EpubSemanticHelper.extractSemanticStructureForChapter(
                    rawHtml = rc.htmlString,
                    fileKey = rc.fileKey,
                    chapterIndex = rc.chapterNumber,
                    chapterTitle = rc.finalChapterTitle,
                    existingParagraphs = rc.paragraphs,
                    existingPageEquivalents = rc.paragraphPageLabels,
                    existingFootnotes = rc.footnotes
                )
                EpubChapter(
                    id = rc.itemId,
                    title = rc.finalChapterTitle,
                    content = rc.paragraphs.joinToString("\n\n"),
                    paragraphs = rc.paragraphs,
                    footnotes = rc.footnotes,
                    htmlContent = if (isFixedLayoutBook) rc.htmlString else "",
                    paragraphPageEquivalents = rc.paragraphPageLabels,
                    fileKey = rc.fileKey,
                    semanticBlocks = semExtraction.semanticBlocks,
                    footnoteEntries = semExtraction.footnoteEntries,
                    anchorToBlockIndex = semExtraction.anchorToBlockIndex,
                    viewportWidth = rc.vpWidth,
                    viewportHeight = rc.vpHeight
                )
            }
        }
        chapters.addAll(builtChapters)
        stageMs["semantic_block_build"] = (System.nanoTime() - tSemWallStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) {
            Log.d(LOAD_PERF_TAG, "EPUB stage per_chapter_parse: ${stageMs["per_chapter_parse"]} ms (${chapters.size} chapters)")
            Log.d(LOAD_PERF_TAG, "EPUB stage semantic_block_build: ${stageMs["semantic_block_build"]} ms")
        }

        // Fallback: if OPF spine/manifest yielded 0 chapters, scan all XHTML/HTML/XML files in the archive directly
        if (chapters.isEmpty()) {
            val htmlEntries = files.entries
                .filter { (name, _) ->
                    val lower = name.lowercase()
                    (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xml")) &&
                        !lower.contains("container.xml") &&
                        !lower.endsWith(".opf") &&
                        !lower.endsWith(".ncx") &&
                        !lower.contains("page-map") &&
                        !lower.contains("pagemap") &&
                        !lower.contains("nav.") &&
                        !lower.endsWith("toc.xhtml") &&
                        !lower.endsWith("toc.html")
                }
                .sortedBy { it.key }

            for ((entryName, chapterBytes) in htmlEntries) {
                val htmlString = String(chapterBytes, Charsets.UTF_8)
                val fileKey = entryName.substringAfterLast("/")
                val ncxTitle = ncxTitles[fileKey]
                val inlineAnchors = extractInlinePageBreakAnchors(htmlString)
                val externalAnchors = pageListByFile[fileKey] ?: pageListByFile.entries.firstOrNull {
                    it.key.equals(fileKey, ignoreCase = true)
                }?.value ?: emptyMap()
                val filePageListAnchors = LinkedHashMap<String, String>()
                filePageListAnchors.putAll(externalAnchors)
                for ((k, v) in inlineAnchors) {
                    if (!filePageListAnchors.containsKey(k)) filePageListAnchors[k] = v
                }
                val currentSpineIdx = chapters.size
                for ((anchorKey, lbl) in filePageListAnchors) {
                    if (lbl.isNotBlank()) {
                        extractedPageListEntries.add(
                            EpubPageListEntry(
                                label = lbl,
                                spineFileKey = fileKey,
                                anchorId = anchorKey,
                                spineIndex = currentSpineIdx
                            )
                        )
                    }
                }
                val initialPageForFile = filePageListAnchors[""]?.takeIf { it.isNotBlank() } ?: carryOverPageLabel
                val (extractedTitle, rawParagraphs, footnotes, rawParagraphPageLabels, endingPageLabel) = extractReadableContentWithPageMarkers(
                    html = htmlString,
                    chapterIndex = chapterCounter,
                    externalPageListAnchors = filePageListAnchors,
                    initialPageLabel = initialPageForFile
                )
                if (endingPageLabel.isNotBlank()) {
                    carryOverPageLabel = endingPageLabel
                }
                if (rawParagraphPageLabels.any { it.isNotBlank() } || filePageListAnchors.isNotEmpty()) {
                    anyPageEquivalentDetected = true
                }
                val finalChapterTitle = ncxTitle?.takeIf { it.isNotBlank() } ?: extractedTitle
                val (vpWidth, vpHeight) = parseViewportDimensionsFromHtml(htmlString)
                val paragraphs = if (rawParagraphs.isEmpty() && isFixedLayoutBook) {
                    listOf(finalChapterTitle.ifBlank { "Page $chapterCounter" })
                } else {
                    rawParagraphs
                }
                val paragraphPageLabels = if (rawParagraphPageLabels.isEmpty() && isFixedLayoutBook && paragraphs.isNotEmpty()) {
                    listOf(filePageListAnchors.values.firstOrNull { it.isNotBlank() }.orEmpty())
                } else {
                    rawParagraphPageLabels
                }
                if (paragraphs.isNotEmpty()) {
                    val semExtraction = EpubSemanticHelper.extractSemanticStructureForChapter(
                        rawHtml = htmlString,
                        fileKey = fileKey,
                        chapterIndex = chapterCounter,
                        chapterTitle = finalChapterTitle,
                        existingParagraphs = paragraphs,
                        existingPageEquivalents = paragraphPageLabels,
                        existingFootnotes = footnotes
                    )
                    chapters.add(
                        EpubChapter(
                            id = "ch_$chapterCounter",
                            title = finalChapterTitle,
                            content = paragraphs.joinToString("\n\n"),
                            paragraphs = paragraphs,
                            footnotes = footnotes,
                            htmlContent = htmlString,
                            paragraphPageEquivalents = paragraphPageLabels,
                            fileKey = fileKey,
                            semanticBlocks = semExtraction.semanticBlocks,
                            footnoteEntries = semExtraction.footnoteEntries,
                            anchorToBlockIndex = semExtraction.anchorToBlockIndex,
                            viewportWidth = vpWidth,
                            viewportHeight = vpHeight
                        )
                    )
                    chapterCounter++
                }
            }
        }

        // Ultimate fallback so an image-only or empty-spine EPUB still opens with at least 1 readable chapter after the cover
        if (chapters.isEmpty()) {
            val fallbackText = description.cleanHtml().ifBlank { "Document content for $title." }
            chapters.add(
                EpubChapter(
                    id = "ch_fallback_1",
                    title = title.ifBlank { "Chapter 1" },
                    content = fallbackText,
                    paragraphs = listOf(fallbackText),
                    fileKey = "chapter1.xhtml"
                )
            )
        }

        val tPageListStart = System.nanoTime()
        // Post-process chapters: if an EPUB has Page List metadata where only the first page of each chapter (or sparse anchors)
        // is marked (so a single print page number like "7" would otherwise stay stuck for 3,000+ characters across many pages),
        // automatically advance numeric print page equivalents every ~1,750 characters (standard printed book page density)
        // across paragraphs and chapters until the next explicit page marker!
        val refinedChapters = if (anyPageEquivalentDetected) {
            var runningCarryPageInt: Int? = null
            chapters.mapIndexed { chIdx, ch ->
                val paras = ch.paragraphs
                val eqs = ch.paragraphPageEquivalents.toMutableList()
                if (paras.isNotEmpty() && eqs.size == paras.size) {
                    // Find the first explicit page number in the next chapter (if any) to bound page advancement
                    val nextChapterExplicitInt = chapters.drop(chIdx + 1)
                        .firstNotNullOfOrNull { nextCh ->
                            nextCh.paragraphPageEquivalents.firstOrNull { it.isNotBlank() }?.substringBefore("–")?.trim()?.toIntOrNull()
                        }

                    var i = 0
                    while (i < eqs.size) {
                        val baseLabel = eqs[i].trim()
                        val rawBaseInt = baseLabel.toIntOrNull()
                        val effectiveBaseInt = if (rawBaseInt != null && runningCarryPageInt != null && rawBaseInt < runningCarryPageInt!!) {
                            runningCarryPageInt!!
                        } else {
                            rawBaseInt
                        }
                        // Find the run of consecutive paragraphs that all had the same original baseLabel
                        var runEnd = i
                        var runChars = 0
                        while (runEnd < eqs.size && eqs[runEnd].trim() == baseLabel) {
                            runChars += paras[runEnd].length
                            runEnd++
                        }
                        // Find the next explicit numeric page label after this run (in this chapter or next chapter)
                        val nextExplicitInChapter = if (runEnd < eqs.size) {
                            eqs[runEnd].trim().substringBefore("–").trim().toIntOrNull()
                        } else null
                        val nextExplicitInt = nextExplicitInChapter ?: nextChapterExplicitInt

                        // Standard printed page holds ~1,650–1,800 characters.
                        // If a single page label stretches for more than 1,850 characters without a new page marker,
                        // advance the page number every ~1,700 characters (capped right below nextExplicitInt if known).
                        if (effectiveBaseInt != null && (runEnd - i) >= 2 && runChars >= 1850) {
                            val maxAllowedInt = if (nextExplicitInt != null && nextExplicitInt > effectiveBaseInt) {
                                nextExplicitInt - 1
                            } else {
                                effectiveBaseInt + (runChars / 1700)
                            }
                            var accChars = 0
                            for (k in i until runEnd) {
                                val offsetPages = (accChars / 1700)
                                val computedPage = (effectiveBaseInt + offsetPages).coerceAtMost(maxAllowedInt)
                                eqs[k] = computedPage.toString()
                                runningCarryPageInt = computedPage
                                accChars += paras[k].length
                            }
                        } else if (effectiveBaseInt != null) {
                            for (k in i until runEnd) {
                                eqs[k] = effectiveBaseInt.toString()
                            }
                            runningCarryPageInt = effectiveBaseInt
                        }
                        i = runEnd.coerceAtLeast(i + 1)
                    }
                    // Also sync refined page equivalents into semanticBlocks so semantic blocks reflect post-processed Page Equivalents!
                    val updatedSemBlocks = if (ch.semanticBlocks.isNotEmpty()) {
                        var eqCursor = 0
                        ch.semanticBlocks.map { sb ->
                            val probe = sb.text.removePrefix("## ").removePrefix("• ").trim().take(32)
                            var matchedIdx = -1
                            if (probe.isNotEmpty()) {
                                for (idx in eqCursor until paras.size) {
                                    if (paras[idx].contains(probe, ignoreCase = true) || probe.contains(paras[idx].take(24), ignoreCase = true)) {
                                        matchedIdx = idx
                                        break
                                    }
                                }
                            }
                            if (matchedIdx >= 0) {
                                eqCursor = matchedIdx
                                sb.copy(pageEquivalent = eqs.getOrNull(matchedIdx).orEmpty())
                            } else {
                                sb.copy(pageEquivalent = eqs.getOrNull(eqCursor.coerceIn(0, eqs.lastIndex)).orEmpty())
                            }
                        }
                    } else emptyList()
                    ch.copy(paragraphPageEquivalents = eqs, semanticBlocks = updatedSemBlocks)
                } else {
                    ch
                }
            }
        } else {
            chapters
        }

        // Resolve hierarchical Table of Contents:
        // 1. Prefer EPUB 3 nav.xhtml (<nav epub:type="toc">) preserving chapter/section/subsection hierarchy.
        // 2. Otherwise use EPUB 2 toc.ncx (<navMap>) preserving nested <navPoint> hierarchy.
        // 3. Otherwise build TOC from the document's h1–h6 heading hierarchy.
        val resolvedTocItems = when {
            epub3NavHierarchy.isNotEmpty() -> epub3NavHierarchy
            ncxNavHierarchy.isNotEmpty() -> ncxNavHierarchy
            else -> EpubSemanticHelper.buildTocFromHeadings(refinedChapters)
        }

        // Cross-chapter bidirectional footnote resolution:
        // Merge all footnote definitions and references across the entire book (supporting endnotes in a separate chapter!)
        val bookFootnotesMap = LinkedHashMap<String, String>()
        for (ch in refinedChapters) {
            for ((k, v) in ch.footnotes) {
                if (v.isNotBlank() && !bookFootnotesMap.containsKey(k)) {
                    bookFootnotesMap[k] = v
                }
            }
        }
        val bookFootnoteEntries = LinkedHashMap<String, EpubFootnoteEntry>()
        for (ch in refinedChapters) {
            for ((k, v) in ch.footnoteEntries) {
                val existing = bookFootnoteEntries[k]
                if (existing == null) {
                    bookFootnoteEntries[k] = v
                } else {
                    // Merge definition and reference metadata if one was in main chapter and one was in endnotes chapter
                    val merged = existing.copy(
                        noteText = existing.noteText.ifBlank { v.noteText }.ifBlank { bookFootnotesMap[k].orEmpty() },
                        footnoteFileKey = existing.footnoteFileKey.ifBlank { v.footnoteFileKey },
                        footnoteAnchorId = existing.footnoteAnchorId.ifBlank { v.footnoteAnchorId },
                        backlinkHref = existing.backlinkHref.ifBlank { v.backlinkHref },
                        backlinkAnchorId = existing.backlinkAnchorId.ifBlank { v.backlinkAnchorId },
                        sourceFileKey = existing.sourceFileKey.ifBlank { v.sourceFileKey },
                        sourceChapterTitle = existing.sourceChapterTitle.ifBlank { v.sourceChapterTitle },
                        sourceParagraphNumber = if (existing.sourceParagraphNumber > 0) existing.sourceParagraphNumber else v.sourceParagraphNumber,
                        sourceAnchorId = existing.sourceAnchorId.ifBlank { v.sourceAnchorId },
                        sourceContextSnippet = existing.sourceContextSnippet.ifBlank { v.sourceContextSnippet }
                    )
                    bookFootnoteEntries[k] = merged
                }
            }
        }
        // Second pass: ensure every entry keyed by noteId or displayMarker shares its resolved noteText and sourceContext across all aliases!
        val entriesByMarker = LinkedHashMap<String, EpubFootnoteEntry>()
        for ((_, entry) in bookFootnoteEntries) {
            val marker = entry.displayMarker.trim('[', ']', '*', ' ')
            if (marker.isNotEmpty()) {
                val prev = entriesByMarker[marker]
                if (prev == null) {
                    entriesByMarker[marker] = entry
                } else {
                    entriesByMarker[marker] = prev.copy(
                        noteText = prev.noteText.ifBlank { entry.noteText }.ifBlank { bookFootnotesMap[marker].orEmpty() },
                        footnoteFileKey = prev.footnoteFileKey.ifBlank { entry.footnoteFileKey },
                        footnoteAnchorId = prev.footnoteAnchorId.ifBlank { entry.footnoteAnchorId },
                        backlinkHref = prev.backlinkHref.ifBlank { entry.backlinkHref },
                        backlinkAnchorId = prev.backlinkAnchorId.ifBlank { entry.backlinkAnchorId },
                        sourceFileKey = prev.sourceFileKey.ifBlank { entry.sourceFileKey },
                        sourceChapterTitle = prev.sourceChapterTitle.ifBlank { entry.sourceChapterTitle },
                        sourceParagraphNumber = if (prev.sourceParagraphNumber > 0) prev.sourceParagraphNumber else entry.sourceParagraphNumber,
                        sourceAnchorId = prev.sourceAnchorId.ifBlank { entry.sourceAnchorId },
                        sourceContextSnippet = prev.sourceContextSnippet.ifBlank { entry.sourceContextSnippet }
                    )
                }
            }
        }
        for ((marker, mergedEntry) in entriesByMarker) {
            val withFallbackText = if (mergedEntry.noteText.isBlank()) {
                val fb = bookFootnotesMap[marker] ?: bookFootnotesMap[mergedEntry.noteId] ?: ""
                if (fb.isNotBlank()) mergedEntry.copy(noteText = fb) else mergedEntry
            } else mergedEntry
            if (withFallbackText.noteText.isNotBlank()) {
                bookFootnoteEntries[marker] = withFallbackText
                if (withFallbackText.noteId.isNotBlank()) {
                    bookFootnoteEntries[withFallbackText.noteId] = withFallbackText
                }
                if (withFallbackText.footnoteAnchorId.isNotBlank()) {
                    bookFootnoteEntries[withFallbackText.footnoteAnchorId] = withFallbackText
                }
            }
        }
        for ((k, text) in bookFootnotesMap) {
            if (text.isNotBlank() && !bookFootnoteEntries.containsKey(k)) {
                bookFootnoteEntries[k] = EpubFootnoteEntry(
                    noteId = k,
                    displayMarker = k.replace(Regex("^(?:fn|footnote|note|endnote|en)[_\\-.:]?", RegexOption.IGNORE_CASE), "").ifBlank { k },
                    noteText = text
                )
            }
        }
        stageMs["cfi_pagelist_work"] = (System.nanoTime() - tPageListStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(LOAD_PERF_TAG, "EPUB stage cfi_pagelist_work: ${stageMs["cfi_pagelist_work"]} ms")

        val tStableLocStart = System.nanoTime()
        // Compute canonical text per spine item (tags stripped, whitespace collapsed to single spaces)
        // and cumulative 1-based stable locations across the spine (1 location per 150 characters of canonical text).
        val (chaptersWithStableLocations, totalBookStableLocations) = assignStableLocationsToChapters(
            refinedChapters.map { ch ->
                ch.copy(
                    footnotes = bookFootnotesMap,
                    footnoteEntries = bookFootnoteEntries
                )
            }
        )
        stageMs["stable_location"] = (System.nanoTime() - tStableLocStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(LOAD_PERF_TAG, "EPUB stage stable_location: ${stageMs["stable_location"]} ms")

        // Decode embedded figures referenced by semantic FIGURE blocks so figures render with their captions
        val embeddedImages = mutableMapOf<String, Bitmap>()
        for (ch in chaptersWithStableLocations) {
            for (sb in ch.semanticBlocks) {
                if (sb.imagePath.isNotBlank() && !embeddedImages.containsKey(sb.imagePath)) {
                    val imgBytes = findFileBytes(files, sb.imagePath)
                    if (imgBytes != null) {
                        decodeSampledBitmap(imgBytes, maxDim = 900)?.let { bmp ->
                            embeddedImages[sb.imagePath] = bmp
                            embeddedImages[sb.imagePath.substringAfterLast("/")] = bmp
                        }
                    }
                }
            }
            if (isFixedLayoutBook && ch.htmlContent.isNotBlank()) {
                val imgMatches = Regex("(?:src|xlink:href)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(ch.htmlContent)
                for (im in imgMatches) {
                    val rawSrc = im.groups[1]?.value?.trim().orEmpty()
                    if (rawSrc.isNotEmpty() && isImageExtension(rawSrc) && !embeddedImages.containsKey(rawSrc)) {
                        val imgBytes = findFileBytes(files, rawSrc)
                        if (imgBytes != null) {
                            decodeSampledBitmap(imgBytes, maxDim = 1000)?.let { bmp ->
                                embeddedImages[rawSrc] = bmp
                                embeddedImages[rawSrc.substringAfterLast("/")] = bmp
                            }
                        }
                    }
                }
            }
        }

        val resolvedLandmarks = if (epub3Landmarks.isNotEmpty()) {
            epub3Landmarks
        } else {
            opfGuideReferences
        }

        val resolvedBookDirection = resolvePageProgressionDirection(spinePageProgressionAttr, dcLanguage)

        // Parse Media Overlays (.smil) for spine items with media-overlay attribute (or .smil files in manifest/archive)
        val parsedMediaOverlays = mutableListOf<EpubSmilClip>()
        val processedSmilPaths = HashSet<String>()
        val orderedSmilHrefs = mutableListOf<Pair<String, String>>() // (smilPath, fallbackTextFileKey)

        for (itemId in itemIdsToProcess) {
            val moId = itemMediaOverlays[itemId] ?: continue
            val smilHref = manifest[moId] ?: continue
            val textHref = manifest[itemId]?.substringAfterLast("/").orEmpty()
            if (processedSmilPaths.add(smilHref.lowercase())) {
                orderedSmilHrefs.add(smilHref to textHref)
            }
        }
        // Also check any remaining itemMediaOverlays or .smil manifest items in case spine didn't list them directly
        for ((textItemId, moId) in itemMediaOverlays) {
            val smilHref = manifest[moId] ?: continue
            val textHref = manifest[textItemId]?.substringAfterLast("/").orEmpty()
            if (processedSmilPaths.add(smilHref.lowercase())) {
                orderedSmilHrefs.add(smilHref to textHref)
            }
        }
        if (orderedSmilHrefs.isEmpty()) {
            for ((_, href) in manifest) {
                if (href.endsWith(".smil", ignoreCase = true) && processedSmilPaths.add(href.lowercase())) {
                    orderedSmilHrefs.add(href to "")
                }
            }
        }

        for ((smilPath, fallbackTextFileKey) in orderedSmilHrefs) {
            val smilBytes = findFileBytes(files, smilPath) ?: continue
            val smilStr = try { String(smilBytes, Charsets.UTF_8) } catch (_: Throwable) { continue }
            val smilClips = parseSmilClips(
                smilXml = smilStr,
                smilFilePath = smilPath,
                fallbackTextFileKey = fallbackTextFileKey,
                chapters = chaptersWithStableLocations,
                archiveFiles = files,
                knownAudioEntryNames = binaryEntryNames
            )
            parsedMediaOverlays.addAll(smilClips)
        }

        // Extract standalone <audio> elements from chapters
        val standaloneAudioItems = mutableListOf<EpubStandaloneAudioItem>()
        for (ch in chaptersWithStableLocations) {
            if (ch.htmlContent.contains("<audio", ignoreCase = true)) {
                standaloneAudioItems.addAll(
                    extractStandaloneAudioItems(
                        html = ch.htmlContent,
                        chapterFileKey = ch.fileKey,
                        archiveFiles = files,
                        knownAudioEntryNames = binaryEntryNames
                    )
                )
            }
        }

        // Do NOT load large audio/video binaries into memory during parseEpub; store only their entry names
        // and read bytes lazily when playback needs them.
        val embeddedAudioBytes = emptyMap<String, ByteArray>()

        return EpubBook(
            title = title,
            author = author,
            description = description,
            coverBitmap = coverBitmap,
            chapters = chaptersWithStableLocations,
            hasPageListMetadata = anyPageEquivalentDetected,
            tocItems = resolvedTocItems,
            footnoteEntries = bookFootnoteEntries,
            embeddedImages = embeddedImages,
            pageListEntries = extractedPageListEntries,
            totalStableLocations = totalBookStableLocations,
            landmarks = resolvedLandmarks,
            pageProgressionDirection = resolvedBookDirection,
            language = dcLanguage.ifBlank { "en" },
            isFixedLayout = isFixedLayoutBook,
            renditionOrientation = renditionOrientation.lowercase().ifBlank { "auto" },
            renditionSpread = renditionSpread.lowercase().ifBlank { "auto" },
            mediaOverlays = parsedMediaOverlays,
            mediaOverlayDuration = mediaOverlayDuration,
            embeddedAudioBytes = embeddedAudioBytes,
            standaloneAudioItems = standaloneAudioItems,
            sourceArchivePath = sourceArchivePath,
            audioEntryNames = binaryEntryNames
        ).also {
            if (DEBUG_LOAD_PERF) {
                val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                val top3 = stageMs.entries.sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { "${it.key}=${it.value}ms" }
                Log.d(LOAD_PERF_TAG, "EPUB parseEpub summary: total=${totalMs}ms | slowest: $top3")
            }
        }
    }

    /**
     * Parses SMIL 3.0 / EPUB 3 Media Overlay clock values into milliseconds.
     * Supports:
     * - Full clock values: "00:01:02.500", "01:02:03"
     * - Partial clock values: "1:02.500", "02:15"
     * - Timecount values: "12.5s", "500ms", "2.5min", "1h", or plain seconds "12.5"
     * Returns null if malformed or negative so callers can safely skip or default.
     */
    fun parseSmilClockValueMs(rawClock: String?): Long? {
        if (rawClock == null) return null
        val clean = rawClock.trim()
            .removePrefix("npt=")
            .trim()
            .lowercase()
        if (clean.isEmpty()) return null
        return try {
            when {
                clean.endsWith("ms") -> {
                    val v = clean.removeSuffix("ms").trim().toDoubleOrNull() ?: return null
                    if (v < 0.0 || v.isNaN() || v.isInfinite()) null else kotlin.math.round(v).toLong()
                }
                clean.endsWith("min") -> {
                    val v = clean.removeSuffix("min").trim().toDoubleOrNull() ?: return null
                    if (v < 0.0 || v.isNaN() || v.isInfinite()) null else kotlin.math.round(v * 60_000.0).toLong()
                }
                clean.endsWith("h") -> {
                    val v = clean.removeSuffix("h").trim().toDoubleOrNull() ?: return null
                    if (v < 0.0 || v.isNaN() || v.isInfinite()) null else kotlin.math.round(v * 3_600_000.0).toLong()
                }
                clean.endsWith("s") -> {
                    val v = clean.removeSuffix("s").trim().toDoubleOrNull() ?: return null
                    if (v < 0.0 || v.isNaN() || v.isInfinite()) null else kotlin.math.round(v * 1000.0).toLong()
                }
                clean.contains(":") -> {
                    val parts = clean.split(":")
                    when (parts.size) {
                        2 -> {
                            val mins = parts[0].trim().toLongOrNull() ?: return null
                            val secs = parts[1].trim().toDoubleOrNull() ?: return null
                            if (mins < 0L || secs < 0.0 || secs.isNaN()) return null
                            (mins * 60_000L) + kotlin.math.round(secs * 1000.0).toLong()
                        }
                        3 -> {
                            val hrs = parts[0].trim().toLongOrNull() ?: return null
                            val mins = parts[1].trim().toLongOrNull() ?: return null
                            val secs = parts[2].trim().toDoubleOrNull() ?: return null
                            if (hrs < 0L || mins < 0L || secs < 0.0 || secs.isNaN()) return null
                            (hrs * 3_600_000L) + (mins * 60_000L) + kotlin.math.round(secs * 1000.0).toLong()
                        }
                        else -> null
                    }
                }
                else -> {
                    val v = clean.toDoubleOrNull() ?: return null
                    if (v < 0.0 || v.isNaN() || v.isInfinite()) null else kotlin.math.round(v * 1000.0).toLong()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses an EPUB 3 Media Overlay `.smil` XML document into ordered [EpubSmilClip] entries.
     * Malformed clock values, missing audio files, or unresolved text anchors never crash the reader;
     * invalid clips are skipped cleanly.
     */
    internal fun parseSmilClips(
        smilXml: String,
        smilFilePath: String = "",
        fallbackTextFileKey: String = "",
        chapters: List<EpubChapter> = emptyList(),
        archiveFiles: Map<String, ByteArray> = emptyMap(),
        knownAudioEntryNames: Set<String> = emptySet()
    ): List<EpubSmilClip> {
        val clips = mutableListOf<EpubSmilClip>()
        val smilDir = if (smilFilePath.contains("/")) smilFilePath.substringBeforeLast("/") + "/" else ""
        val parRegex = Regex(
            "<(?:[a-zA-Z0-9]+:)?par\\b([^>]*)>(.*?)</(?:[a-zA-Z0-9]+:)?par>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        var clipCounter = 1
        for (parMatch in parRegex.findAll(smilXml)) {
            try {
                val parAttrs = parMatch.groups[1]?.value.orEmpty()
                val parBody = parMatch.groups[2]?.value.orEmpty()
                val parId = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(parAttrs)?.groups?.get(1)?.value?.trim()
                    .orEmpty()
                    .ifBlank { "par_${clipCounter}" }

                val textMatch = Regex("<(?:[a-zA-Z0-9]+:)?text\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(parBody) ?: continue
                val textAttrs = textMatch.groups[1]?.value.orEmpty()
                val rawTextSrc = Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(textAttrs)?.groups?.get(1)?.value?.trim().orEmpty()
                if (rawTextSrc.isEmpty()) continue

                val audioMatch = Regex("<(?:[a-zA-Z0-9]+:)?audio\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(parBody) ?: continue
                val audioAttrs = audioMatch.groups[1]?.value.orEmpty()
                val rawAudioSrc = Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(audioAttrs)?.groups?.get(1)?.value?.trim().orEmpty()
                if (rawAudioSrc.isEmpty()) continue

                val rawClipBegin = Regex("\\b(?:clipBegin|clip-begin)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(audioAttrs)?.groups?.get(1)?.value?.trim()
                val rawClipEnd = Regex("\\b(?:clipEnd|clip-end)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(audioAttrs)?.groups?.get(1)?.value?.trim()

                val beginMs = if (rawClipBegin.isNullOrBlank()) 0L else (parseSmilClockValueMs(rawClipBegin) ?: continue)
                val endMs = if (rawClipEnd.isNullOrBlank()) {
                    beginMs + 10_000L
                } else {
                    parseSmilClockValueMs(rawClipEnd) ?: continue
                }
                if (endMs <= beginMs) continue

                val decodedTextSrc = try { java.net.URLDecoder.decode(rawTextSrc, "UTF-8") } catch (_: Exception) { rawTextSrc }
                val textFilePart = decodedTextSrc.substringBefore("#").substringAfterLast("/").trim().ifBlank { fallbackTextFileKey }
                val textAnchorPart = if (decodedTextSrc.contains("#")) decodedTextSrc.substringAfter("#").trim() else ""
                if (textFilePart.isEmpty() && textAnchorPart.isEmpty()) continue

                // If chapters are provided, verify the text file/anchor resolves to a known chapter/anchor; skip unresolved text anchors
                if (chapters.isNotEmpty()) {
                    val matchedChapter = chapters.firstOrNull { ch ->
                        textFilePart.isEmpty() ||
                            ch.fileKey.equals(textFilePart, ignoreCase = true) ||
                            ch.id.equals(textFilePart, ignoreCase = true)
                    } ?: chapters.firstOrNull { ch ->
                        textAnchorPart.isNotEmpty() && (
                            ch.anchorToBlockIndex.keys.any { it.equals(textAnchorPart, ignoreCase = true) } ||
                                ch.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(textAnchorPart, ignoreCase = true) } } ||
                                ch.htmlContent.contains("id=\"$textAnchorPart\"", ignoreCase = true) ||
                                ch.htmlContent.contains("id='$textAnchorPart'", ignoreCase = true)
                            )
                    }
                    if (matchedChapter == null) continue
                    if (textAnchorPart.isNotEmpty()) {
                        val anchorExistsInChapter = matchedChapter.anchorToBlockIndex.keys.any { it.equals(textAnchorPart, ignoreCase = true) } ||
                            matchedChapter.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(textAnchorPart, ignoreCase = true) } } ||
                            matchedChapter.htmlContent.contains("id=\"$textAnchorPart\"", ignoreCase = true) ||
                            matchedChapter.htmlContent.contains("id='$textAnchorPart'", ignoreCase = true) ||
                            matchedChapter.htmlContent.contains("name=\"$textAnchorPart\"", ignoreCase = true)
                        if (!anchorExistsInChapter) continue
                    }
                }

                val decodedAudioSrc = try { java.net.URLDecoder.decode(rawAudioSrc, "UTF-8") } catch (_: Exception) { rawAudioSrc }
                val resolvedAudioPath = resolveSmilRelativePath(smilDir, decodedAudioSrc)

                // If archiveFiles or knownAudioEntryNames is non-empty, verify audio file exists in archive; skip missing audio files cleanly
                if (archiveFiles.isNotEmpty() || knownAudioEntryNames.isNotEmpty()) {
                    val audioFileName = resolvedAudioPath.substringAfterLast("/")
                    val existsInAudioNames = knownAudioEntryNames.any {
                        it.equals(resolvedAudioPath, ignoreCase = true) ||
                            it.equals(decodedAudioSrc, ignoreCase = true) ||
                            (audioFileName.isNotEmpty() && it.substringAfterLast("/").equals(audioFileName, ignoreCase = true))
                    }
                    if (!existsInAudioNames) {
                        val audioBytes = findFileBytes(archiveFiles, resolvedAudioPath) ?: findFileBytes(archiveFiles, decodedAudioSrc)
                        if (audioBytes == null || audioBytes.isEmpty()) continue
                    }
                }

                clips.add(
                    EpubSmilClip(
                        id = parId,
                        textFileKey = textFilePart,
                        textAnchorId = textAnchorPart,
                        audioFilePath = resolvedAudioPath,
                        clipBeginMs = beginMs,
                        clipEndMs = endMs
                    )
                )
                clipCounter++
            } catch (_: Throwable) {
                // Never crash on malformed <par>
            }
        }
        return clips
    }

    private fun extractStandaloneAudioItems(
        html: String,
        chapterFileKey: String,
        archiveFiles: Map<String, ByteArray>,
        knownAudioEntryNames: Set<String> = emptySet()
    ): List<EpubStandaloneAudioItem> {
        val items = mutableListOf<EpubStandaloneAudioItem>()
        val audioTagRegex = Regex(
            "<audio\\b([^>]*)(?:>(.*?)</audio>|/>)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        var idx = 1
        for (m in audioTagRegex.findAll(html)) {
            try {
                val attrs = m.groups[1]?.value.orEmpty()
                val inner = m.groups[2]?.value.orEmpty()
                val idAttr = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groups?.get(1)?.value?.trim().orEmpty()
                    .ifBlank { "audio_${chapterFileKey.substringBeforeLast(".")}_$idx" }
                val directSrc = Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groups?.get(1)?.value?.trim().orEmpty()
                val sourceSrc = if (directSrc.isNotEmpty()) directSrc else {
                    Regex("<source\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(inner)?.groups?.get(1)?.value?.trim().orEmpty()
                }
                if (sourceSrc.isEmpty()) continue
                val decodedSrc = try { java.net.URLDecoder.decode(sourceSrc, "UTF-8") } catch (_: Exception) { sourceSrc }
                val cleanSrc = decodedSrc.removePrefix("../").removePrefix("./").removePrefix("/")
                if (archiveFiles.isNotEmpty() || knownAudioEntryNames.isNotEmpty()) {
                    val fileName = cleanSrc.substringAfterLast("/")
                    val existsInAudioNames = knownAudioEntryNames.any {
                        it.equals(cleanSrc, ignoreCase = true) ||
                            it.equals(decodedSrc, ignoreCase = true) ||
                            (fileName.isNotEmpty() && it.substringAfterLast("/").equals(fileName, ignoreCase = true))
                    }
                    if (!existsInAudioNames) {
                        val bytes = findFileBytes(archiveFiles, cleanSrc) ?: findFileBytes(archiveFiles, decodedSrc)
                        if (bytes == null || bytes.isEmpty()) continue
                    }
                }
                val titleAttr = Regex("\\b(?:title|aria-label)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groups?.get(1)?.value?.trim().orEmpty()
                    .ifBlank { cleanSrc.substringAfterLast("/") }
                items.add(
                    EpubStandaloneAudioItem(
                        id = idAttr,
                        fileKey = chapterFileKey,
                        anchorId = idAttr,
                        audioFilePath = cleanSrc,
                        label = titleAttr.ifBlank { "Audio" }
                    )
                )
                idx++
            } catch (_: Throwable) {}
        }
        return items
    }

    private fun resolveSmilRelativePath(baseDir: String, relativeHref: String): String {
        if (baseDir.isEmpty() || relativeHref.startsWith("/")) return relativeHref.removePrefix("/")
        val combined = (baseDir + relativeHref).split("/")
        val stack = ArrayList<String>()
        for (seg in combined) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }

    private val RECOGNIZED_LANDMARK_TYPES = setOf(
        "cover",
        "toc",
        "bodymatter",
        "glossary",
        "bibliography",
        "index"
    )

    private fun normalizeLandmarkType(rawType: String): String? {
        val tokens = rawType.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (token in tokens) {
            val clean = token.substringAfterLast(":")
            when (clean) {
                "cover" -> return "cover"
                "toc" -> return "toc"
                "bodymatter", "text", "start" -> return "bodymatter"
                "glossary" -> return "glossary"
                "bibliography", "biblio" -> return "bibliography"
                "index" -> return "index"
            }
        }
        return null
    }

    private fun defaultTitleForLandmarkType(normalizedType: String): String = when (normalizedType) {
        "cover" -> "Cover"
        "toc" -> "Table of Contents"
        "bodymatter" -> "Start of Content"
        "glossary" -> "Glossary"
        "bibliography" -> "Bibliography"
        "index" -> "Index"
        else -> normalizedType.replaceFirstChar { it.uppercase() }
    }

    internal fun mapRawLandmarkToItem(rawType: String, rawTitle: String, rawHref: String): EpubLandmarkItem? {
        val normalizedType = normalizeLandmarkType(rawType) ?: return null
        if (normalizedType !in RECOGNIZED_LANDMARK_TYPES) return null
        val cleanHref = rawHref.trim()
        if (cleanHref.isEmpty() && normalizedType != "cover") return null
        val filePart = cleanHref.substringBefore("#").substringAfterLast("/").trim()
        val anchorPart = if (cleanHref.contains("#")) cleanHref.substringAfter("#").trim() else ""
        val decodedFile = try { java.net.URLDecoder.decode(filePart, "UTF-8") } catch (_: Exception) { filePart }
        val decodedAnchor = try { java.net.URLDecoder.decode(anchorPart, "UTF-8") } catch (_: Exception) { anchorPart }
        val finalTitle = rawTitle.cleanHtml().ifBlank { defaultTitleForLandmarkType(normalizedType) }
        return EpubLandmarkItem(
            title = finalTitle,
            epubType = normalizedType,
            href = cleanHref,
            fileKey = decodedFile,
            anchorId = decodedAnchor,
            resolvedPage = 1
        )
    }

    /**
     * Parses `<nav epub:type="landmarks">` (or `role="directory"`) from an EPUB 3 Navigation Document.
     * Recognises cover, toc, bodymatter, glossary, bibliography, index.
     */
    fun parseEpub3Landmarks(navHtml: String): List<EpubLandmarkItem> {
        val results = mutableListOf<EpubLandmarkItem>()
        val seenTypesAndHrefs = HashSet<String>()
        val navRegex = Regex(
            "<nav\\b([^>]*)>(.*?)</nav>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (navMatch in navRegex.findAll(navHtml)) {
            val attrs = navMatch.groups[1]?.value.orEmpty()
            val body = navMatch.groups[2]?.value.orEmpty()
            val isLandmarksNav = Regex("epub:type\\s*=\\s*[\"'][^\"']*\\blandmarks\\b[^\"']*[\"']", RegexOption.IGNORE_CASE).containsMatchIn(attrs) ||
                Regex("\\btype\\s*=\\s*[\"'][^\"']*\\blandmarks\\b[^\"']*[\"']", RegexOption.IGNORE_CASE).containsMatchIn(attrs) ||
                Regex("\\brole\\s*=\\s*[\"'][^\"']*\\bdirectory\\b[^\"']*[\"']", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
            if (!isLandmarksNav) continue

            val linkRegex = Regex(
                "<a\\b([^>]*)>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (aMatch in linkRegex.findAll(body)) {
                val aAttrs = aMatch.groups[1]?.value.orEmpty()
                val aInner = aMatch.groups[2]?.value.orEmpty()
                val epubTypeAttr = Regex("(?:epub:type|type)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(aAttrs)?.groups?.get(1)?.value.orEmpty()
                val hrefAttr = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(aAttrs)?.groups?.get(1)?.value.orEmpty()
                val item = mapRawLandmarkToItem(epubTypeAttr, aInner, hrefAttr)
                if (item != null) {
                    val dedupKey = "${item.epubType}|${item.fileKey.lowercase()}|${item.anchorId.lowercase()}"
                    if (seenTypesAndHrefs.add(dedupKey)) {
                        results.add(item)
                    }
                }
            }
        }
        return results
    }

    /**
     * Resolves each [EpubLandmarkItem] to its 1-based [EpubPage] number after pagination.
     */
    fun resolveLandmarksToPages(
        landmarks: List<EpubLandmarkItem>,
        pages: List<EpubPage>,
        chapters: List<BookChapter> = emptyList()
    ): List<EpubLandmarkItem> {
        if (landmarks.isEmpty()) return emptyList()
        if (pages.isEmpty()) return landmarks
        return landmarks.map { item ->
            val resolvedPageNum = when {
                item.epubType.equals("cover", ignoreCase = true) && pages.firstOrNull()?.isCoverPage == true -> 1
                item.anchorId.isNotBlank() -> {
                    val byAnchorAndFile = pages.firstOrNull { p ->
                        !p.isCoverPage &&
                            (item.fileKey.isBlank() || p.fileKey.equals(item.fileKey, ignoreCase = true)) &&
                            (p.anchorIds.any { it.equals(item.anchorId, ignoreCase = true) } ||
                                p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(item.anchorId, ignoreCase = true) } })
                    } ?: pages.firstOrNull { p ->
                        !p.isCoverPage &&
                            (p.anchorIds.any { it.equals(item.anchorId, ignoreCase = true) } ||
                                p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(item.anchorId, ignoreCase = true) } })
                    }
                    if (byAnchorAndFile != null) {
                        byAnchorAndFile.globalIndex + 1
                    } else {
                        val byFile = pages.firstOrNull { p ->
                            !p.isCoverPage && item.fileKey.isNotBlank() && p.fileKey.equals(item.fileKey, ignoreCase = true)
                        }
                        byFile?.let { it.globalIndex + 1 }
                            ?: chapters.firstOrNull { ch ->
                                (item.fileKey.isNotBlank() && ch.fileKey.equals(item.fileKey, ignoreCase = true)) ||
                                    ch.title.equals(item.title, ignoreCase = true)
                            }?.pageNumber
                            ?: 1
                    }
                }
                item.fileKey.isNotBlank() -> {
                    val byFile = pages.firstOrNull { p ->
                        !p.isCoverPage && p.fileKey.equals(item.fileKey, ignoreCase = true)
                    }
                    byFile?.let { it.globalIndex + 1 }
                        ?: chapters.firstOrNull { ch ->
                            ch.fileKey.equals(item.fileKey, ignoreCase = true) || ch.title.equals(item.title, ignoreCase = true)
                        }?.pageNumber
                        ?: if (item.epubType == "bodymatter") {
                            (pages.indexOfFirst { !it.isCoverPage }.takeIf { it >= 0 } ?: 0) + 1
                        } else 1
                }
                else -> 1
            }
            item.copy(resolvedPage = resolvedPageNum.coerceIn(1, pages.size.coerceAtLeast(1)))
        }
    }

    private fun isImageExtension(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun findFileBytes(files: Map<String, ByteArray>, path: String): ByteArray? {
        val clean = path.substringBefore("#").removePrefix("/")
        val decoded = try { java.net.URLDecoder.decode(clean, "UTF-8") } catch (_: Exception) { clean }
        return files[clean]
            ?: files[decoded]
            ?: files[path]
            ?: files.entries.firstOrNull { it.key.equals(clean, ignoreCase = true) || it.key.equals(decoded, ignoreCase = true) }?.value
            ?: files.entries.firstOrNull {
                val fileName = decoded.substringAfterLast("/")
                fileName.isNotEmpty() && it.key.substringAfterLast("/").equals(fileName, ignoreCase = true)
            }?.value
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = 1200): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var sample = 1
            while (options.outWidth / (sample * 2) >= maxDim || options.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts cover image from EPUB file or generates one without parsing all chapter HTML/footnotes.
     */
    fun extractCoverBitmap(file: File, fallbackTitle: String = "", fallbackAuthor: String = ""): Bitmap {
        return try {
            if (file.exists()) {
                file.inputStream().use { stream ->
                    extractCoverBitmapFast(stream, fallbackTitle, fallbackAuthor)
                }
            } else {
                generateBookCoverBitmap(fallbackTitle, fallbackAuthor)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            generateBookCoverBitmap(fallbackTitle, fallbackAuthor)
        }
    }

    private fun extractCoverBitmapFast(
        inputStream: InputStream,
        fallbackTitle: String,
        fallbackAuthor: String
    ): Bitmap {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val lower = entry.name.lowercase()
                    if (lower.endsWith(".opf") ||
                        lower == "meta-inf/container.xml" ||
                        isImageExtension(lower) ||
                        lower.contains("cover") ||
                        lower.contains("title")
                    ) {
                        files[entry.name] = zis.readBytes()
                    }
                }
                entry = zis.nextEntry
            }
        }
        val coverEntry = files.entries.firstOrNull { (k, _) ->
            k.contains("cover", ignoreCase = true) && isImageExtension(k)
        } ?: files.entries.firstOrNull { (k, _) -> isImageExtension(k) }

        if (coverEntry != null) {
            decodeSampledBitmap(coverEntry.value)?.let { return it }
        }
        return generateBookCoverBitmap(
            fallbackTitle.ifBlank { "Book" },
            fallbackAuthor.ifBlank { "Unknown Author" }
        )
    }

    /**
     * Generates a clean editorial e-paper book cover matching the Maktkamp / Min historie style:
     * pure white paper, sharp thin black rectangular frame inset, centered serif title,
     * centered horizontal rule, and centered monospace author name.
     */
    fun generateBookCoverBitmap(title: String, author: String): Bitmap {
        val width = 600
        val height = 840
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Pure white background
        canvas.drawColor(Color.WHITE)

        // Crisp 1-pixel equivalent inner frame (sharp 90-degree corners)
        val borderPaint = Paint().apply {
            color = 0xFF27272A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = false
        }
        canvas.drawRect(36f, 36f, (width - 36).toFloat(), (height - 36).toFloat(), borderPaint)

        // Title text (multi-line centered Serif Bold)
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 40f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val displayTitle = title.ifBlank { "Untitled Book" }
        val words = displayTitle.split(" ")
        val titleLines = mutableListOf<String>()
        var currentLine = StringBuilder()
        for (w in words) {
            if (currentLine.length + w.length > 18) {
                if (currentLine.isNotEmpty()) titleLines.add(currentLine.toString())
                currentLine = StringBuilder(w)
            } else {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(w)
            }
        }
        if (currentLine.isNotEmpty()) titleLines.add(currentLine.toString())

        val cappedLines = titleLines.take(3)
        var y = 370f - ((cappedLines.size - 1) * 26f)
        for (line in cappedLines) {
            canvas.drawText(line, (width / 2).toFloat(), y, titlePaint)
            y += 52f
        }

        // Thin centered horizontal rule
        val dividerPaint = Paint().apply {
            color = 0xFF27272A.toInt()
            strokeWidth = 2.5f
            isAntiAlias = false
        }
        val ruleY = y + 8f
        canvas.drawLine(175f, ruleY, 425f, ruleY, dividerPaint)

        // Author text in clean Monospace
        val authorPaint = Paint().apply {
            color = 0xFF27272A.toInt()
            textSize = 27f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val displayAuthor = author.ifBlank { "Unknown Author" }
        canvas.drawText(displayAuthor, (width / 2).toFloat(), ruleY + 56f, authorPaint)

        return bitmap
    }

    /**
     * Checks whether a candidate block text is a duplicate of the chapter title heading
     * (either exact match after normalizing punctuation/whitespace, or contained in the chapter title when the title combines e.g. "1  My Journey" or "Kapittel 1: Innledning" and the HTML has `<h1>1</h1>` + `<h2>My Journey</h2>`).
     */
    fun isDuplicateChapterHeadingText(candidateText: String, chapterTitle: String, isHeadingOrFirstBlock: Boolean): Boolean {
        val cleanCand = candidateText.replace(Regex("^#+\\s*"), "").trim()
        val cleanCh = chapterTitle.replace(Regex("^#+\\s*"), "").trim()
        if (cleanCand.isEmpty() || cleanCh.isEmpty()) return false
        if (cleanCand.equals(cleanCh, ignoreCase = true)) return true
        val normCand = cleanCand.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val normCh = cleanCh.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (normCand.isNotEmpty() && normCand == normCh) return true
        if (isHeadingOrFirstBlock && cleanCand.length in 1..90) {
            if (normCand.isNotEmpty() && (
                    normCh.startsWith("$normCand ") ||
                        normCh.endsWith(" $normCand") ||
                        normCand.startsWith("$normCh ") ||
                        normCand.endsWith(" $normCh")
                    )
            ) {
                return true
            }
            // Also catch standalone numeric/roman chapter numbers (e.g. "1", "2", "I", "II") when the chapter title starts with or contains that number (e.g. "1 My Journey", "Chapter 1")
            val candDigits = normCand.removePrefix("chapter ").removePrefix("kapittel ").removePrefix("ch ").trim()
            if (candDigits.matches(Regex("^\\d{1,3}$")) && Regex("\\b${Regex.escape(candDigits)}\\b").containsMatchIn(normCh)) {
                return true
            }
        }
        return false
    }

    /**
     * Re-computes inlineLink character ranges when a paragraph is split into a substring (either by
     * `pagebreak` markers or by pagination), so footnote and internal links remain clickable in every split segment!
     */
    fun sliceInlineLinksForSegment(
        originalFullText: String,
        segmentText: String,
        originalLinks: List<EpubInlineLink>
    ): List<EpubInlineLink> {
        if (originalLinks.isEmpty() || segmentText.isBlank()) return emptyList()
        if (originalFullText.equals(segmentText, ignoreCase = true)) return originalLinks
        val cleanSeg = segmentText.replace(Regex("^#+\\s*"), "").trim()
        val segStartInFull = originalFullText.indexOf(cleanSeg, ignoreCase = true)
        if (segStartInFull >= 0) {
            val segEndInFull = segStartInFull + cleanSeg.length
            val prefixLen = segmentText.length - cleanSeg.length
            return originalLinks.mapNotNull { link ->
                if (link.endChar <= segStartInFull || link.startChar >= segEndInFull) {
                    null
                } else {
                    val newStart = (link.startChar - segStartInFull + prefixLen).coerceIn(0, segmentText.length)
                    val newEnd = (link.endChar - segStartInFull + prefixLen).coerceIn(newStart, segmentText.length)
                    if (newEnd > newStart) link.copy(startChar = newStart, endChar = newEnd) else null
                }
            }
        }
        // Fallback: locate each link's label inside segmentText
        return originalLinks.mapNotNull { link ->
            val lbl = link.label.trim()
            if (lbl.isEmpty()) null
            else {
                val idx = segmentText.indexOf(lbl)
                if (idx >= 0) link.copy(startChar = idx, endChar = idx + lbl.length) else null
            }
        }
    }

    /**
     * Estimates how many visual lines a paragraph will occupy on screen, including inter-paragraph spacing.
     */
    private fun estimateVisualLines(
        paragraph: String,
        charsPerLine: Int,
        firstLineIndentChars: Int = 0
    ): Int {
        if (paragraph.isBlank()) return 0
        if (paragraph.startsWith("## ") || paragraph.startsWith("#")) {
            val headerText = paragraph.replace(Regex("^#+\\s*"), "").trim()
            val headerCharsPerLine = (charsPerLine * 0.75f).toInt().coerceAtLeast(16)
            val lines = ((headerText.length + headerCharsPerLine - 1) / headerCharsPerLine).coerceAtLeast(1)
            return lines + 1 // Heading spacing
        }
        var totalLines = 0
        val subLines = paragraph.split("\n")
        for ((idx, line) in subLines.withIndex()) {
            val len = line.trim().length + if (idx == 0) firstLineIndentChars.coerceAtLeast(0) else 0
            totalLines += if (len == 0) 1 else ((len + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
        }
        return totalLines
    }

    /**
     * Splits a paragraph into a first part that fits within [availableVisualLines] and a remainder part
     * that continues onto the next page, breaking cleanly at sentence or word boundaries.
     */
    private fun splitParagraphToFitLines(
        paragraph: String,
        availableVisualLines: Int,
        charsPerLine: Int,
        firstLineIndentChars: Int = 0
    ): Pair<String, String>? {
        if (paragraph.startsWith("## ") || paragraph.startsWith("#") || availableVisualLines < 1) return null
        val targetChars = ((availableVisualLines.coerceAtLeast(1) * charsPerLine) - firstLineIndentChars.coerceAtLeast(0))
            .coerceAtLeast((charsPerLine - firstLineIndentChars.coerceAtLeast(0)).coerceAtLeast(10))
        if (paragraph.length <= targetChars) return Pair(paragraph, "")

        // Prefer breaking at a sentence boundary (. ! ? followed by space) near targetChars (75%..100% of available lines)
        val searchWindowStart = (targetChars * 0.72f).toInt().coerceAtLeast(20)
        val searchWindowEnd = targetChars.coerceAtMost(paragraph.length - 1)

        var splitPos = -1
        if (searchWindowEnd > searchWindowStart) {
            val window = paragraph.substring(searchWindowStart, searchWindowEnd)
            val sentenceEndMatch = Regex("[.!?][\"'”’)]?\\s+").findAll(window).lastOrNull()
            if (sentenceEndMatch != null) {
                splitPos = searchWindowStart + sentenceEndMatch.range.last + 1
            }
        }

        // Fallback: break at the last space/word boundary right before targetChars so the page fills completely
        if (splitPos == -1) {
            val lastSpace = paragraph.lastIndexOf(' ', searchWindowEnd)
            if (lastSpace >= (charsPerLine * 0.5f).toInt()) {
                splitPos = lastSpace + 1
            }
        }

        if (splitPos <= 0 || splitPos >= paragraph.length) return null
        val firstPart = paragraph.substring(0, splitPos).trim()
        val remainder = paragraph.substring(splitPos).trim()
        if (firstPart.isEmpty() || remainder.isEmpty()) return null
        return Pair(firstPart, remainder)
    }

    /**
     * Reflows and paginates an EPUB book into screen-sized pages that fill the viewport from top to bottom.
     * - Automatically adjusts character and line capacity based on [fontSizeSp], [lineSpacingMultiplier],
     *   [marginDp], and actual container dimensions ([viewportWidthDp], [viewportHeightDp]).
     * - Flows text continuously across sections/chapters and splits long paragraphs across page boundaries
     *   so small font sizes automatically pull in subsequent paragraphs/pages to fill the entire screen.
     */
    /**
     * Progressive pagination helper: paginates only the first [maxChapters] chapters (centered or starting from 0
     * up through the chapter needed for the user's saved position) so the reader can open immediately,
     * followed by full pagination in the background.
     */
    fun paginateBookPrefix(
        epubBook: EpubBook,
        maxChapters: Int,
        fontSizeSp: Float = 18f,
        lineSpacingMultiplier: Float = 1.4f,
        marginDp: Int = 20,
        viewportWidthDp: Int = 400,
        viewportHeightDp: Int = 760,
        paginateByPageEquivalent: Boolean = false,
        paragraphStyle: String = "BOTH"
    ): Pair<List<EpubPage>, List<BookChapter>> {
        val safeCount = maxChapters.coerceIn(1, epubBook.chapters.size.coerceAtLeast(1))
        if (safeCount >= epubBook.chapters.size || epubBook.isFixedLayout) {
            return paginateBook(
                epubBook = epubBook,
                fontSizeSp = fontSizeSp,
                lineSpacingMultiplier = lineSpacingMultiplier,
                marginDp = marginDp,
                viewportWidthDp = viewportWidthDp,
                viewportHeightDp = viewportHeightDp,
                paginateByPageEquivalent = paginateByPageEquivalent,
                paragraphStyle = paragraphStyle
            )
        }
        val prefixBook = epubBook.copy(chapters = epubBook.chapters.take(safeCount))
        return paginateBook(
            epubBook = prefixBook,
            fontSizeSp = fontSizeSp,
            lineSpacingMultiplier = lineSpacingMultiplier,
            marginDp = marginDp,
            viewportWidthDp = viewportWidthDp,
            viewportHeightDp = viewportHeightDp,
            paginateByPageEquivalent = paginateByPageEquivalent,
            paragraphStyle = paragraphStyle
        )
    }

    fun paginateBook(
        epubBook: EpubBook,
        fontSizeSp: Float = 18f,
        lineSpacingMultiplier: Float = 1.4f,
        marginDp: Int = 20,
        viewportWidthDp: Int = 400,
        viewportHeightDp: Int = 760,
        paginateByPageEquivalent: Boolean = false,
        paragraphStyle: String = "BOTH"
    ): Pair<List<EpubPage>, List<BookChapter>> {
        val paginateStartNs = System.nanoTime()
        val pages = mutableListOf<EpubPage>()
        val bookChapters = mutableListOf<BookChapter>()

        var globalIndex = 0

        // Page 1: Cover Page if present
        if (epubBook.coverBitmap != null) {
            pages.add(
                EpubPage(
                    globalIndex = globalIndex++,
                    chapterIndex = -1,
                    chapterTitle = "Cover",
                    pageInChapter = 1,
                    totalPagesInChapter = 1,
                    paragraphs = emptyList(),
                    isCoverPage = true,
                    coverBitmap = epubBook.coverBitmap
                )
            )
            bookChapters.add(BookChapter(title = "Cover", pageNumber = 1))
        }

        val chapters = epubBook.chapters
        if (chapters.isEmpty()) {
            if (pages.isEmpty()) {
                pages.add(
                    EpubPage(
                        globalIndex = globalIndex++,
                        chapterIndex = 0,
                        chapterTitle = epubBook.title,
                        pageInChapter = 1,
                        totalPagesInChapter = 1,
                        paragraphs = listOf("Empty book content.")
                    )
                )
                bookChapters.add(BookChapter(title = epubBook.title, pageNumber = 1))
            }
            return Pair(pages, bookChapters)
        }

        // Fixed-layout (FXL) books: do NOT run reflow pagination.
        // Render each spine item as one page, preserving its viewport aspect ratio and HTML content.
        if (epubBook.isFixedLayout) {
            val combinedFootnotes = mutableMapOf<String, String>()
            val combinedFootnoteEntries = LinkedHashMap<String, EpubFootnoteEntry>()
            combinedFootnoteEntries.putAll(epubBook.footnoteEntries)
            for (ch in chapters) {
                combinedFootnotes.putAll(ch.footnotes)
                combinedFootnoteEntries.putAll(ch.footnoteEntries)
            }
            for ((chIdx, chapter) in chapters.withIndex()) {
                val cleanTitle = chapter.title.trim().ifEmpty { "Page ${chIdx + 1}" }
                val currentPageNum = globalIndex + 1
                val nonBlankEqs = chapter.paragraphPageEquivalents.filter { it.isNotBlank() }
                val startPageEq = nonBlankEqs.firstOrNull()?.substringBefore("–")?.trim()
                    ?: epubBook.lookupPageLabel(spineFileKey = chapter.fileKey, spineIndex = chIdx).orEmpty()
                val endPageEq = nonBlankEqs.lastOrNull()?.substringAfterLast("–")?.trim().orEmpty().ifBlank { startPageEq }
                val pageEqLabel = if (startPageEq.isNotEmpty() && endPageEq.isNotEmpty() && !startPageEq.equals(endPageEq, ignoreCase = true)) {
                    "$startPageEq–$endPageEq"
                } else {
                    startPageEq
                }
                val pageAnchors = LinkedHashSet<String>()
                for (sb in chapter.semanticBlocks) {
                    pageAnchors.addAll(sb.anchorIds)
                }
                pages.add(
                    EpubPage(
                        globalIndex = globalIndex++,
                        chapterIndex = chIdx,
                        chapterTitle = cleanTitle,
                        pageInChapter = 1,
                        totalPagesInChapter = 1,
                        paragraphs = chapter.paragraphs,
                        footnotes = combinedFootnotes,
                        pageEquivalentLabel = pageEqLabel,
                        paragraphPageEquivalents = chapter.paragraphPageEquivalents,
                        semanticBlocks = chapter.semanticBlocks,
                        footnoteEntries = combinedFootnoteEntries,
                        anchorIds = pageAnchors,
                        fileKey = chapter.fileKey,
                        startParagraphNumber = 1,
                        endParagraphNumber = 1,
                        startStableLocation = chapter.startStableLocation,
                        endStableLocation = chapter.endStableLocation,
                        isFixedLayoutPage = true,
                        viewportWidth = chapter.viewportWidth.takeIf { it > 0 } ?: 600,
                        viewportHeight = chapter.viewportHeight.takeIf { it > 0 } ?: 800,
                        htmlContent = chapter.htmlContent
                    )
                )
                bookChapters.add(
                    BookChapter(
                        title = cleanTitle,
                        pageNumber = currentPageNum,
                        fileKey = chapter.fileKey
                    )
                )
            }
            if (epubBook.tocItems.isNotEmpty()) {
                val coverEntries = bookChapters.filter { it.title == "Cover" }
                val resolvedTocChapters = epubBook.tocItems.map { tocItem ->
                    val resolvedPage = resolvePageNumberForLink(
                        pages = pages,
                        chapters = chapters,
                        fileKey = tocItem.fileKey,
                        anchorId = tocItem.anchorId,
                        fallbackTitle = tocItem.title
                    )
                    BookChapter(
                        title = tocItem.title,
                        pageNumber = resolvedPage,
                        level = tocItem.level,
                        href = tocItem.href,
                        fileKey = tocItem.fileKey,
                        anchorId = tocItem.anchorId
                    )
                }
                bookChapters.clear()
                bookChapters.addAll(coverEntries)
                bookChapters.addAll(resolvedTocChapters)
            }
            return Pair(pages, bookChapters)
        }

        // Compute dynamic visual line capacity and characters per line based on actual viewport & typography settings
        val usableWidthDp = (viewportWidthDp - (marginDp * 2)).coerceAtLeast(160).toFloat()
        val charsPerLine = (usableWidthDp / (fontSizeSp * 0.45f)).toInt().coerceIn(14, 240)
        val lineHeightDp = (fontSizeSp * lineSpacingMultiplier).coerceAtLeast(10f)
        val usableHeightDp = (viewportHeightDp - 96).coerceAtLeast(280).toFloat()
        val maxVisualLinesPerPage = (usableHeightDp / lineHeightDp).toInt().coerceIn(4, 130)
        val normParagraphStyle = paragraphStyle.uppercase()
        val hasParagraphIndent = normParagraphStyle == "INDENT" || normParagraphStyle == "BOTH"
        val hasParagraphSpacing = normParagraphStyle == "SPACED" || normParagraphStyle == "BOTH"
        // 1.5em first-line indent is ~3.3 average characters (1.5 / 0.45)
        val firstLineIndentCharCount = if (hasParagraphIndent) 3 else 0
        // 0.6em extra paragraph spacing is (0.6 * fontSizeSp) / lineHeightDp visual lines
        val paragraphSpacingVisualLines = if (hasParagraphSpacing) (0.6f / lineSpacingMultiplier.coerceAtLeast(1f)) else 0f

        // Build a continuous stream of blocks across all chapters so pages never stop half-empty
        data class StreamBlock(
            val text: String,
            val chapterIndex: Int,
            val chapterTitle: String,
            val footnotes: Map<String, String>,
            val isChapterStart: Boolean = false,
            val pageEquivalent: String = "",
            val semanticBlock: EpubSemanticBlock? = null,
            val footnoteEntries: Map<String, EpubFootnoteEntry> = emptyMap(),
            val fileKey: String = "",
            val isFirstAfterHeading: Boolean = false
        )

        val stream = ArrayDeque<StreamBlock>()
        val combinedFootnotes = mutableMapOf<String, String>()
        val combinedFootnoteEntries = LinkedHashMap<String, EpubFootnoteEntry>()
        combinedFootnoteEntries.putAll(epubBook.footnoteEntries)

        for ((chIdx, chapter) in chapters.withIndex()) {
            combinedFootnotes.putAll(chapter.footnotes)
            combinedFootnoteEntries.putAll(chapter.footnoteEntries)
            val cleanTitle = chapter.title.trim()
            val firstParaPageEq = chapter.paragraphPageEquivalents.firstOrNull { it.isNotBlank() }?.substringBefore("–")?.trim() ?: ""
            var markedStart = false
            var prevWasHeading = false

            if (cleanTitle.isNotEmpty()) {
                val matchedHeadingBlock = chapter.semanticBlocks.firstOrNull {
                    it.type == EpubSemanticBlockType.HEADING &&
                        isDuplicateChapterHeadingText(it.text, cleanTitle, isHeadingOrFirstBlock = true)
                }
                // Synthesize a clean chapter heading without copying inlineLinks from a clickable HTML <a href> inside <h1>/<h2>
                // so the chapter heading never renders as a blue underlined link or repeats below!
                val headingSemBlock = EpubSemanticBlock(
                    type = EpubSemanticBlockType.HEADING,
                    text = cleanTitle,
                    headingLevel = matchedHeadingBlock?.headingLevel?.coerceIn(1, 2) ?: 1,
                    anchorIds = matchedHeadingBlock?.anchorIds ?: emptyList(),
                    inlineLinks = emptyList(),
                    paragraphNumberInChapter = null,
                    pageEquivalent = firstParaPageEq,
                    fileKey = chapter.fileKey
                )
                stream.addLast(
                    StreamBlock(
                        text = "## $cleanTitle",
                        chapterIndex = chIdx,
                        chapterTitle = cleanTitle,
                        footnotes = chapter.footnotes,
                        isChapterStart = true,
                        pageEquivalent = firstParaPageEq,
                        semanticBlock = headingSemBlock,
                        footnoteEntries = chapter.footnoteEntries,
                        fileKey = chapter.fileKey,
                        isFirstAfterHeading = false
                    )
                )
                markedStart = true
                prevWasHeading = true
            }

            var semSearchIdx = 0
            for ((pIdx, p) in chapter.paragraphs.withIndex()) {
                val trimmed = p.trim()
                if (trimmed.isEmpty()) continue
                val paraPageEq = chapter.paragraphPageEquivalents.getOrNull(pIdx)?.trim() ?: ""
                var matchedSemBlock: EpubSemanticBlock? = null
                if (chapter.semanticBlocks.isNotEmpty()) {
                    val probe = trimmed.replace(Regex("^#+\\s*"), "").removePrefix("• ").trim().take(32)
                    for (sIdx in semSearchIdx until chapter.semanticBlocks.size) {
                        val cand = chapter.semanticBlocks[sIdx]
                        if (cand.text.equals(trimmed, ignoreCase = true) ||
                            (probe.length >= 6 && cand.text.contains(probe, ignoreCase = true)) ||
                            (cand.text.length >= 6 && trimmed.contains(cand.text.take(28), ignoreCase = true))
                        ) {
                            // CRITICAL: Always keep `text = trimmed` from `chapter.paragraphs` so mid-paragraph
                            // page-equivalent splits (e.g. page 7 -> 8 -> 9) are never overwritten by the unsplit semanticBlock text,
                            // while slicing `inlineLinks` so footnote and internal links inside split paragraphs stay clickable!
                            val validLinks = if (cand.type != EpubSemanticBlockType.HEADING) {
                                sliceInlineLinksForSegment(cand.text, trimmed, cand.inlineLinks)
                            } else {
                                emptyList()
                            }
                            matchedSemBlock = cand.copy(
                                text = trimmed,
                                inlineLinks = validLinks,
                                pageEquivalent = paraPageEq.ifBlank { cand.pageEquivalent }
                            )
                            if (cand.text.equals(trimmed, ignoreCase = true)) {
                                semSearchIdx = sIdx + 1
                            }
                            break
                        }
                    }
                }
                val isHeadingOrFirst = pIdx <= 1 || matchedSemBlock?.type == EpubSemanticBlockType.HEADING
                // Avoid duplicating the chapter title if the first paragraph/heading is identical or part of the synthesized chapter title!
                if (markedStart && isDuplicateChapterHeadingText(trimmed, cleanTitle, isHeadingOrFirstBlock = isHeadingOrFirst)) {
                    continue
                }
                val effectiveSemBlock = matchedSemBlock ?: EpubSemanticBlock(
                    type = EpubSemanticBlockType.PARAGRAPH,
                    text = trimmed,
                    paragraphNumberInChapter = pIdx + 1,
                    pageEquivalent = paraPageEq,
                    fileKey = chapter.fileKey
                )
                val displayBlockText = when {
                    effectiveSemBlock.type == EpubSemanticBlockType.HEADING && !trimmed.startsWith("## ") -> {
                        "## ${trimmed.replace(Regex("^#+\\s*"), "").trim()}"
                    }
                    else -> trimmed
                }
                val isCurrentHeading = effectiveSemBlock.type == EpubSemanticBlockType.HEADING || displayBlockText.startsWith("## ")
                stream.addLast(
                    StreamBlock(
                        text = displayBlockText,
                        chapterIndex = chIdx,
                        chapterTitle = cleanTitle.ifEmpty { "Chapter ${chIdx + 1}" },
                        footnotes = chapter.footnotes,
                        isChapterStart = !markedStart,
                        pageEquivalent = paraPageEq,
                        semanticBlock = effectiveSemBlock,
                        footnoteEntries = chapter.footnoteEntries,
                        fileKey = chapter.fileKey,
                        isFirstAfterHeading = prevWasHeading && !isCurrentHeading
                    )
                )
                markedStart = true
                prevWasHeading = isCurrentHeading
            }
        }

        val recordedChapters = mutableSetOf<Int>()
        val usePrintedPageBoundaries = paginateByPageEquivalent && epubBook.hasPageListMetadata && stream.any { it.pageEquivalent.isNotBlank() }

        while (stream.isNotEmpty()) {
            val pageParagraphs = mutableListOf<String>()
            val pageParaEquivalents = mutableListOf<String>()
            val pageSemanticBlocks = mutableListOf<EpubSemanticBlock>()
            val pageAnchors = LinkedHashSet<String>()
            var usedVisualLines = 0
            var pagePrimaryChapterIdx = stream.first().chapterIndex
            var pagePrimaryChapterTitle = stream.first().chapterTitle
            var pageFileKey = stream.first().fileKey
            val currentPageNumber = globalIndex + 1

            if (usePrintedPageBoundaries) {
                // Paginate 1:1 by printed Page Equivalent so each ebook page corresponds directly to a printed page!
                val firstBlockEq = stream.first().pageEquivalent.substringBefore("–").trim()
                var activePrintPageKey = firstBlockEq
                while (stream.isNotEmpty()) {
                    val block = stream.first()
                    val blockStartEq = block.pageEquivalent.substringBefore("–").trim()
                    val blockEndEq = block.pageEquivalent.substringAfterLast("–").trim().ifBlank { blockStartEq }
                    if (pageParagraphs.isNotEmpty() &&
                        activePrintPageKey.isNotBlank() &&
                        blockStartEq.isNotBlank() &&
                        !blockStartEq.equals(activePrintPageKey, ignoreCase = true)
                    ) {
                        break
                    }
                    stream.removeFirst()
                    if (block.isChapterStart && recordedChapters.add(block.chapterIndex)) {
                        bookChapters.add(
                            BookChapter(
                                title = block.chapterTitle,
                                pageNumber = currentPageNumber,
                                fileKey = block.fileKey
                            )
                        )
                        if (pageParagraphs.isEmpty()) {
                            pagePrimaryChapterIdx = block.chapterIndex
                            pagePrimaryChapterTitle = block.chapterTitle
                            pageFileKey = block.fileKey
                        }
                    }
                    if (activePrintPageKey.isBlank() && blockStartEq.isNotBlank()) {
                        activePrintPageKey = blockStartEq
                    }
                    pageParagraphs.add(block.text)
                    pageParaEquivalents.add(block.pageEquivalent)
                    val sb = block.semanticBlock ?: EpubSemanticBlock(
                        type = EpubSemanticBlockType.PARAGRAPH,
                        text = block.text,
                        pageEquivalent = block.pageEquivalent,
                        fileKey = block.fileKey
                    )
                    pageSemanticBlocks.add(sb)
                    pageAnchors.addAll(sb.anchorIds)
                    if (blockEndEq.isNotBlank() && activePrintPageKey.isNotBlank() && !blockEndEq.equals(activePrintPageKey, ignoreCase = true)) {
                        val nextPeekEq = stream.firstOrNull()?.pageEquivalent?.substringBefore("–")?.trim() ?: ""
                        if (nextPeekEq.isNotBlank() && !nextPeekEq.equals(activePrintPageKey, ignoreCase = true)) {
                            break
                        }
                    }
                }
            } else {
                var usedVisualLinesFloat = 0f
                while (stream.isNotEmpty()) {
                    val block = stream.first()
                    val isBlockHeading = block.text.startsWith("## ") || block.semanticBlock?.type == EpubSemanticBlockType.HEADING
                    val isBlockBodyParagraph = !isBlockHeading &&
                        (block.semanticBlock == null || block.semanticBlock.type == EpubSemanticBlockType.PARAGRAPH)
                    val blockIndentChars = if (isBlockBodyParagraph && !block.isFirstAfterHeading) firstLineIndentCharCount else 0

                    // If a new chapter heading is about to start at the very bottom of a nearly-full page,
                    // push the heading to the top of the next page so it isn't orphaned without body text.
                    if (isBlockHeading &&
                        pageParagraphs.isNotEmpty() && (maxVisualLinesPerPage - usedVisualLines) < 3
                    ) {
                        break
                    }

                    val blockLines = estimateVisualLines(block.text, charsPerLine, blockIndentChars)
                    val spacingLinesFloat = when {
                        isBlockBodyParagraph -> paragraphSpacingVisualLines
                        pageParagraphs.isNotEmpty() -> 0.5f
                        else -> 0f
                    }
                    val effectiveLinesFloat = blockLines + spacingLinesFloat
                    val remainingLines = (maxVisualLinesPerPage - kotlin.math.ceil(usedVisualLinesFloat).toInt()).coerceAtLeast(0)

                    if (pageParagraphs.isEmpty()) {
                        stream.removeFirst()
                        if (block.isChapterStart && recordedChapters.add(block.chapterIndex)) {
                            bookChapters.add(
                                BookChapter(
                                    title = block.chapterTitle,
                                    pageNumber = currentPageNumber,
                                    fileKey = block.fileKey
                                )
                            )
                            pagePrimaryChapterIdx = block.chapterIndex
                            pagePrimaryChapterTitle = block.chapterTitle
                            pageFileKey = block.fileKey
                        }

                        if (blockLines > maxVisualLinesPerPage) {
                            val split = splitParagraphToFitLines(block.text, maxVisualLinesPerPage, charsPerLine, blockIndentChars)
                            if (split != null) {
                                val startEq = block.pageEquivalent.substringBefore("–").trim()
                                val endEq = block.pageEquivalent.substringAfterLast("–").trim().ifBlank { startEq }
                                val startInt = startEq.toIntOrNull()
                                val endInt = endEq.toIntOrNull()
                                // If the block itself spans over 1,650 chars, advance the print page number for the remainder!
                                val remainderEq = if (startInt != null && endInt != null && endInt > startInt) {
                                    endEq
                                } else if (startInt != null && split.first.length >= 1450) {
                                    (startInt + 1).toString()
                                } else {
                                    endEq.ifBlank { block.pageEquivalent }
                                }
                                pageParagraphs.add(split.first)
                                pageParaEquivalents.add(startEq.ifBlank { block.pageEquivalent })
                                val baseSb = block.semanticBlock ?: EpubSemanticBlock(
                                    type = EpubSemanticBlockType.PARAGRAPH,
                                    text = block.text,
                                    pageEquivalent = block.pageEquivalent,
                                    fileKey = block.fileKey
                                )
                                val firstPartLinks = sliceInlineLinksForSegment(baseSb.text, split.first, baseSb.inlineLinks)
                                val secondPartLinks = sliceInlineLinksForSegment(baseSb.text, split.second, baseSb.inlineLinks)
                                pageSemanticBlocks.add(
                                    baseSb.copy(
                                        text = split.first,
                                        inlineLinks = firstPartLinks,
                                        pageEquivalent = startEq.ifBlank { block.pageEquivalent }
                                    )
                                )
                                pageAnchors.addAll(baseSb.anchorIds)
                                usedVisualLines = maxVisualLinesPerPage
                                usedVisualLinesFloat = maxVisualLinesPerPage.toFloat()
                                val remSb = baseSb.copy(
                                    text = split.second,
                                    anchorIds = baseSb.anchorIds.filter { it.startsWith("__epub_dir_", ignoreCase = true) },
                                    inlineLinks = secondPartLinks,
                                    pageEquivalent = remainderEq
                                )
                                stream.addFirst(
                                    block.copy(
                                        text = split.second,
                                        isChapterStart = false,
                                        pageEquivalent = remainderEq,
                                        semanticBlock = remSb,
                                        isFirstAfterHeading = false
                                    )
                                )
                                break
                            }
                        }
                        pageParagraphs.add(block.text)
                        pageParaEquivalents.add(block.pageEquivalent)
                        val sb = block.semanticBlock ?: EpubSemanticBlock(
                            type = EpubSemanticBlockType.PARAGRAPH,
                            text = block.text,
                            pageEquivalent = block.pageEquivalent,
                            fileKey = block.fileKey
                        )
                        pageSemanticBlocks.add(sb)
                        pageAnchors.addAll(sb.anchorIds)
                        usedVisualLinesFloat += effectiveLinesFloat
                        usedVisualLines = kotlin.math.ceil(usedVisualLinesFloat).toInt()
                    } else if (usedVisualLinesFloat + effectiveLinesFloat <= maxVisualLinesPerPage.toFloat()) {
                        stream.removeFirst()
                        if (block.isChapterStart && recordedChapters.add(block.chapterIndex)) {
                            bookChapters.add(
                                BookChapter(
                                    title = block.chapterTitle,
                                    pageNumber = currentPageNumber,
                                    fileKey = block.fileKey
                                )
                            )
                        }
                        pageParagraphs.add(block.text)
                        pageParaEquivalents.add(block.pageEquivalent)
                        val sb = block.semanticBlock ?: EpubSemanticBlock(
                            type = EpubSemanticBlockType.PARAGRAPH,
                            text = block.text,
                            pageEquivalent = block.pageEquivalent,
                            fileKey = block.fileKey
                        )
                        pageSemanticBlocks.add(sb)
                        pageAnchors.addAll(sb.anchorIds)
                        usedVisualLinesFloat += effectiveLinesFloat
                        usedVisualLines = kotlin.math.ceil(usedVisualLinesFloat).toInt()
                    } else {
                        // Paragraph is larger than remainingLines on the current page:
                        // Split the paragraph so the first portion fills the rest of this page,
                        // and the remainder continues at the top of the next page!
                        if (remainingLines >= 2 && !isBlockHeading) {
                            val split = splitParagraphToFitLines(block.text, remainingLines, charsPerLine, blockIndentChars)
                            if (split != null) {
                                stream.removeFirst()
                                if (block.isChapterStart && recordedChapters.add(block.chapterIndex)) {
                                    bookChapters.add(
                                        BookChapter(
                                            title = block.chapterTitle,
                                            pageNumber = currentPageNumber,
                                            fileKey = block.fileKey
                                        )
                                    )
                                }
                                val startEq = block.pageEquivalent.substringBefore("–").trim()
                                val endEq = block.pageEquivalent.substringAfterLast("–").trim().ifBlank { startEq }
                                pageParagraphs.add(split.first)
                                pageParaEquivalents.add(startEq.ifBlank { block.pageEquivalent })
                                val baseSb = block.semanticBlock ?: EpubSemanticBlock(
                                    type = EpubSemanticBlockType.PARAGRAPH,
                                    text = block.text,
                                    pageEquivalent = block.pageEquivalent,
                                    fileKey = block.fileKey
                                )
                                val firstPartLinks = sliceInlineLinksForSegment(baseSb.text, split.first, baseSb.inlineLinks)
                                val secondPartLinks = sliceInlineLinksForSegment(baseSb.text, split.second, baseSb.inlineLinks)
                                pageSemanticBlocks.add(
                                    baseSb.copy(
                                        text = split.first,
                                        inlineLinks = firstPartLinks,
                                        pageEquivalent = startEq.ifBlank { block.pageEquivalent }
                                    )
                                )
                                pageAnchors.addAll(baseSb.anchorIds)
                                usedVisualLines = maxVisualLinesPerPage
                                usedVisualLinesFloat = maxVisualLinesPerPage.toFloat()
                                val remSb = baseSb.copy(
                                    text = split.second,
                                    anchorIds = baseSb.anchorIds.filter { it.startsWith("__epub_dir_", ignoreCase = true) },
                                    inlineLinks = secondPartLinks,
                                    pageEquivalent = endEq.ifBlank { block.pageEquivalent }
                                )
                                stream.addFirst(
                                    block.copy(
                                        text = split.second,
                                        isChapterStart = false,
                                        pageEquivalent = endEq.ifBlank { block.pageEquivalent },
                                        semanticBlock = remSb,
                                        isFirstAfterHeading = false
                                    )
                                )
                            }
                        }
                        break
                    }
                }
            }

            val nonBlankPageEqs = pageParaEquivalents.filter { it.isNotBlank() }
            val startPageEq = nonBlankPageEqs.firstOrNull()?.substringBefore("–")?.trim() ?: ""
            val endPageEq = nonBlankPageEqs.lastOrNull()?.substringAfterLast("–")?.trim() ?: startPageEq
            val computedPageEqLabel = when {
                usePrintedPageBoundaries && startPageEq.isNotEmpty() -> startPageEq
                startPageEq.isNotEmpty() && endPageEq.isNotEmpty() && !startPageEq.equals(endPageEq, ignoreCase = true) ->
                    "$startPageEq–$endPageEq"
                startPageEq.isNotEmpty() -> startPageEq
                else -> ""
            }

            // Compute 1-based paragraph number range on this page (counting genuine <p> paragraphs)
            val countedParaNumbersOnPage = pageSemanticBlocks.mapNotNull { it.paragraphNumberInChapter }.filter { it > 0 }
            val pageStartParaNum = countedParaNumbersOnPage.firstOrNull() ?: 1
            val pageEndParaNum = countedParaNumbersOnPage.lastOrNull()?.coerceAtLeast(pageStartParaNum) ?: pageStartParaNum

            // Compute 150-char canonical stable location range on this page (independent of font size / screen size)
            val primaryChapter = chapters.getOrNull(pagePrimaryChapterIdx)
            val (pageStartStableLoc, pageEndStableLoc) = computePageStableLocationRange(
                chapter = primaryChapter,
                pageParagraphs = pageParagraphs
            )

            pages.add(
                EpubPage(
                    globalIndex = globalIndex++,
                    chapterIndex = pagePrimaryChapterIdx,
                    chapterTitle = pagePrimaryChapterTitle,
                    pageInChapter = 1,
                    totalPagesInChapter = 1,
                    paragraphs = pageParagraphs,
                    footnotes = combinedFootnotes,
                    pageEquivalentLabel = computedPageEqLabel,
                    paragraphPageEquivalents = pageParaEquivalents,
                    semanticBlocks = pageSemanticBlocks,
                    footnoteEntries = combinedFootnoteEntries,
                    anchorIds = pageAnchors,
                    fileKey = pageFileKey,
                    startParagraphNumber = pageStartParaNum,
                    endParagraphNumber = pageEndParaNum,
                    startStableLocation = pageStartStableLoc,
                    endStableLocation = pageEndStableLoc
                )
            )
        }

        // Update pageInChapter and totalPagesInChapter per chapter for status display in O(N) instead of O(N^2)
        val pagesByChapter = pages.filter { !it.isCoverPage }.groupBy { it.chapterIndex }
        val chapterPageOrdinalMap = HashMap<Int, Int>(pages.size * 2)
        val chapterTotalCountMap = HashMap<Int, Int>(pagesByChapter.size * 2)
        for ((chIdx, chPages) in pagesByChapter) {
            chapterTotalCountMap[chIdx] = chPages.size.coerceAtLeast(1)
            for ((ordIdx, pg) in chPages.withIndex()) {
                chapterPageOrdinalMap[pg.globalIndex] = ordIdx + 1
            }
        }
        val updatedPages = pages.map { p ->
            if (p.isCoverPage) p
            else {
                val idxInCh = chapterPageOrdinalMap[p.globalIndex] ?: 1
                val totInCh = chapterTotalCountMap[p.chapterIndex] ?: 1
                p.copy(pageInChapter = idxInCh, totalPagesInChapter = totInCh)
            }
        }

        // If epubBook has a hierarchical TOC (from nav.xhtml, toc.ncx, or headings), resolve each TOC entry's exact 1-based page number!
        if (epubBook.tocItems.isNotEmpty()) {
            val coverEntries = bookChapters.filter { it.title == "Cover" }
            val resolvedTocChapters = epubBook.tocItems.map { tocItem ->
                val resolvedPage = resolvePageNumberForLink(
                    pages = updatedPages,
                    chapters = chapters,
                    fileKey = tocItem.fileKey,
                    anchorId = tocItem.anchorId,
                    fallbackTitle = tocItem.title
                )
                BookChapter(
                    title = tocItem.title,
                    pageNumber = resolvedPage,
                    level = tocItem.level,
                    href = tocItem.href,
                    fileKey = tocItem.fileKey,
                    anchorId = tocItem.anchorId
                )
            }
            bookChapters.clear()
            bookChapters.addAll(coverEntries)
            bookChapters.addAll(resolvedTocChapters)
        }

        val paginateMs = (System.nanoTime() - paginateStartNs) / 1_000_000L
        safeLogPerf { "EPUB stage pagination: ${paginateMs}ms (${updatedPages.size} pages)" }

        return Pair(updatedPages, bookChapters)
    }

    /**
     * Resolves a 1-based page number in [pages] for an internal EPUB link (`fileKey#anchorId` or `#anchorId`).
     */
    fun resolvePageNumberForLink(
        pages: List<EpubPage>,
        chapters: List<EpubChapter>,
        fileKey: String,
        anchorId: String,
        fallbackTitle: String = ""
    ): Int {
        if (pages.isEmpty()) return 1
        val cleanFile = fileKey.substringAfterLast("/").trim()
        val cleanAnchor = anchorId.removePrefix("#").trim()

        // 1. Exact anchorId match on a page (preferring matching fileKey if specified)
        if (cleanAnchor.isNotEmpty()) {
            val exactAnchorPage = pages.firstOrNull { p ->
                !p.isCoverPage &&
                    (cleanFile.isEmpty() || p.fileKey.equals(cleanFile, ignoreCase = true) ||
                        chapters.getOrNull(p.chapterIndex)?.fileKey?.equals(cleanFile, ignoreCase = true) == true) &&
                    (p.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } ||
                        p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } })
            } ?: pages.firstOrNull { p ->
                !p.isCoverPage &&
                    (p.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } ||
                        p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } })
            }
            if (exactAnchorPage != null) {
                return exactAnchorPage.globalIndex + 1
            }

            // Check if the anchor corresponds to a semantic block in a chapter, and find the page containing that block's text
            for ((chIdx, ch) in chapters.withIndex()) {
                if (cleanFile.isNotEmpty() && !ch.fileKey.equals(cleanFile, ignoreCase = true) && !ch.id.equals(cleanFile, ignoreCase = true)) continue
                val blockIdx = ch.anchorToBlockIndex[cleanAnchor]
                    ?: ch.anchorToBlockIndex.entries.firstOrNull { it.key.equals(cleanAnchor, ignoreCase = true) }?.value
                val targetBlock = if (blockIdx != null) ch.semanticBlocks.getOrNull(blockIdx) else {
                    ch.semanticBlocks.firstOrNull { sb -> sb.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } }
                }
                if (targetBlock != null && targetBlock.text.isNotBlank()) {
                    val probe = targetBlock.text.removePrefix("## ").trim().take(36)
                    val textMatchedPage = pages.firstOrNull { p ->
                        !p.isCoverPage && p.chapterIndex == chIdx && p.paragraphs.any { it.contains(probe, ignoreCase = true) }
                    }
                    if (textMatchedPage != null) {
                        return textMatchedPage.globalIndex + 1
                    }
                }
            }
        }

        // 2. Match by heading/title text if fallbackTitle is provided (e.g. subsection TOC entry)
        if (fallbackTitle.isNotBlank()) {
            val cleanFallback = fallbackTitle.trim()
            val headingPage = pages.firstOrNull { p ->
                !p.isCoverPage &&
                    (cleanFile.isEmpty() || p.fileKey.equals(cleanFile, ignoreCase = true) ||
                        chapters.getOrNull(p.chapterIndex)?.fileKey?.equals(cleanFile, ignoreCase = true) == true) &&
                    (p.chapterTitle.equals(cleanFallback, ignoreCase = true) ||
                        p.semanticBlocks.any { sb ->
                            sb.type == EpubSemanticBlockType.HEADING && sb.text.removePrefix("## ").trim().equals(cleanFallback, ignoreCase = true)
                        } ||
                        p.paragraphs.any { para ->
                            para.removePrefix("## ").trim().equals(cleanFallback, ignoreCase = true)
                        })
            }
            if (headingPage != null) {
                return headingPage.globalIndex + 1
            }
        }

        // 3. Match by fileKey or chapter id
        if (cleanFile.isNotEmpty()) {
            val targetChIdx = chapters.indexOfFirst { ch ->
                ch.fileKey.equals(cleanFile, ignoreCase = true) || ch.id.equals(cleanFile, ignoreCase = true)
            }
            if (targetChIdx >= 0) {
                val chStartPage = pages.firstOrNull { !it.isCoverPage && it.chapterIndex == targetChIdx }
                if (chStartPage != null) {
                    return chStartPage.globalIndex + 1
                }
            }
        }

        return 1
    }

    private fun parseEpub3NavTitles(navHtml: String): Map<String, String> {
        val titles = mutableMapOf<String, String>()
        try {
            // Exclude <nav epub:type="page-list"> blocks so page numbers don't overwrite chapter titles
            val tocOnlyHtml = navHtml.replace(
                Regex("<nav[^>]*(?:epub:type|role)\\s*=\\s*[\"'][^\"']*(?:page-list|doc-pagelist)[^\"']*[\"'][^>]*>.*?</nav>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                ""
            )
            val linkRegex = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (m in linkRegex.findAll(tocOnlyHtml)) {
                val href = m.groups[1]?.value ?: continue
                val fileSrc = href.substringBefore("#").substringAfterLast("/").trim()
                val label = m.groups[2]?.value?.cleanHtml() ?: continue
                if (fileSrc.isNotEmpty() && label.isNotEmpty() && !titles.containsKey(fileSrc)) {
                    titles[fileSrc] = label
                }
            }
        } catch (_: Exception) {}
        return titles
    }

    /**
     * Parses EPUB 3 `<nav epub:type="page-list">` or `<nav role="doc-pagelist">` metadata.
     * Returns map of `fileKey -> Map<anchorId, pageLabel>`.
     */
    private fun parseEpub3NavPageList(navHtml: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        try {
            val navRegex = Regex(
                "<nav[^>]*(?:epub:type|role|class|id)\\s*=\\s*[\"'][^\"']*(?:page-list|pagelist|doc-pagelist)[^\"']*[\"'][^>]*>(.*?)</nav>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val linkRegex = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (navMatch in navRegex.findAll(navHtml)) {
                val body = navMatch.groups[1]?.value ?: continue
                for (m in linkRegex.findAll(body)) {
                    val href = m.groups[1]?.value?.trim() ?: continue
                    val rawLabel = m.groups[2]?.value?.cleanHtml()?.trim() ?: continue
                    val cleanLabel = sanitizePageEquivalentLabel(rawLabel)
                    if (cleanLabel.isEmpty()) continue
                    val filePart = href.substringBefore("#").substringAfterLast("/").trim()
                    val fragment = if (href.contains("#")) href.substringAfter("#").trim() else ""
                    if (filePart.isNotEmpty()) {
                        val fileMap = result.getOrPut(filePart) { LinkedHashMap() }
                        fileMap[fragment] = cleanLabel
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * Extracts inline elements with `epub:type="pagebreak"` or `role="doc-pagebreak"`
     * (label from `title` attribute, else text content) mapped to their anchor ID.
     */
    private fun extractInlinePageBreakAnchors(html: String): Map<String, String> {
        val anchors = LinkedHashMap<String, String>()
        try {
            val pairedRegex = Regex(
                "<([a-zA-Z0-9:]+)\\b([^>]*\\b(?:epub:type|role)\\s*=\\s*[\"'][^\"']*(?:pagebreak|doc-pagebreak)[^\"']*[\"'][^>]*)>(.*?)</\\1>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            var synthIdx = 1
            for (m in pairedRegex.findAll(html)) {
                val attrs = m.groups[2]?.value ?: ""
                val innerText = m.groups[3]?.value?.cleanHtml()?.trim() ?: ""
                val titleAttr = Regex("\\btitle\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: Regex("\\baria-label\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: ""
                val idAttr = Regex("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: "inline_pb_${synthIdx++}"
                val rawCandidate = if (titleAttr.isNotEmpty()) titleAttr else innerText
                val clean = sanitizePageEquivalentLabel(rawCandidate)
                if (clean.isNotEmpty()) {
                    anchors[idAttr] = clean
                }
            }
            val selfClosingRegex = Regex(
                "<([a-zA-Z0-9:]+)\\b([^>]*\\b(?:epub:type|role)\\s*=\\s*[\"'][^\"']*(?:pagebreak|doc-pagebreak)[^\"']*[\"'][^>]*)/?>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (m in selfClosingRegex.findAll(html)) {
                val attrs = m.groups[2]?.value ?: ""
                val titleAttr = Regex("\\btitle\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: Regex("\\baria-label\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: ""
                val idAttr = Regex("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: "inline_pb_${synthIdx++}"
                val clean = sanitizePageEquivalentLabel(titleAttr)
                if (clean.isNotEmpty() && !anchors.containsKey(idAttr)) {
                    anchors[idAttr] = clean
                }
            }
        } catch (_: Exception) {}
        return anchors
    }

    private const val CHARS_PER_STABLE_LOCATION = 150

    /**
     * Computes canonical text per spine item (tags stripped, whitespace collapsed to single spaces)
     * and assigns cumulative 1-based stable locations across the spine (1 location per 150 characters of canonical text).
     * Also precomputes paragraph canonical start offsets at parse time so pagination does not have to re-scan full strings when not needed.
     */
    private fun assignStableLocationsToChapters(chapters: List<EpubChapter>): Pair<List<EpubChapter>, Int> {
        if (chapters.isEmpty()) return Pair(emptyList(), 1)
        var cumulativeChars = 0
        val updated = chapters.map { ch ->
            val rawSource = if (ch.htmlContent.isNotBlank()) {
                ch.htmlContent
                    .replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .cleanHtml()
            } else {
                ch.paragraphs.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            }
            val canonical = rawSource.replace(Regex("\\s+"), " ").trim()
            val startLoc = (cumulativeChars / CHARS_PER_STABLE_LOCATION) + 1
            val len = canonical.length.coerceAtLeast(1)
            cumulativeChars += len
            val endLoc = ((cumulativeChars - 1).coerceAtLeast(0) / CHARS_PER_STABLE_LOCATION) + 1

            // Precompute canonical offset for each paragraph once at parse time
            val paraOffsets = IntArray(ch.paragraphs.size)
            var searchCursor = 0
            for (i in ch.paragraphs.indices) {
                val pClean = ch.paragraphs[i]
                    .replace(Regex("^#+\\s*"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val probe = pClean.take(36)
                val found = if (probe.length >= 4 && canonical.isNotEmpty()) {
                    canonical.indexOf(probe, startIndex = searchCursor, ignoreCase = true).takeIf { it >= 0 }
                        ?: canonical.indexOf(probe, ignoreCase = true).takeIf { it >= 0 }
                        ?: searchCursor
                } else searchCursor
                paraOffsets[i] = found
                searchCursor = found
            }

            ch.copy(
                canonicalText = canonical,
                startStableLocation = startLoc,
                endStableLocation = endLoc.coerceAtLeast(startLoc),
                paragraphCanonicalOffsets = paraOffsets
            )
        }
        val totalLocations = ((cumulativeChars - 1).coerceAtLeast(0) / CHARS_PER_STABLE_LOCATION) + 1
        return Pair(updated, totalLocations.coerceAtLeast(1))
    }

    /**
     * Computes the 1-based startStableLocation and endStableLocation for an [EpubPage]
     * from its chapter's canonical text and cumulative spine offset.
     */
    private fun computePageStableLocationRange(
        chapter: EpubChapter?,
        pageParagraphs: List<String>
    ): Pair<Int, Int> {
        if (chapter == null || pageParagraphs.isEmpty()) return Pair(1, 1)
        val chapterStartCharOffset = (chapter.startStableLocation - 1).coerceAtLeast(0) * CHARS_PER_STABLE_LOCATION
        val canonical = chapter.canonicalText.ifBlank {
            chapter.paragraphs.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        }
        if (canonical.isEmpty()) {
            return Pair(chapter.startStableLocation, chapter.endStableLocation)
        }
        val firstParaClean = pageParagraphs.firstOrNull { !it.startsWith("## ") }
            ?.replace(Regex("^#+\\s*"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val lastParaClean = pageParagraphs.lastOrNull { !it.startsWith("## ") }
            ?.replace(Regex("^#+\\s*"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        val startProbe = firstParaClean.take(36)
        val startOffsetInCh = if (startProbe.length >= 4) {
            canonical.indexOf(startProbe, ignoreCase = true).takeIf { it >= 0 } ?: 0
        } else 0

        val endProbe = lastParaClean.take(36)
        val endMatchStart = if (endProbe.length >= 4) {
            canonical.indexOf(endProbe, startIndex = startOffsetInCh, ignoreCase = true).takeIf { it >= 0 }
                ?: canonical.indexOf(endProbe, ignoreCase = true).takeIf { it >= 0 }
                ?: startOffsetInCh
        } else startOffsetInCh
        val endOffsetInCh = (endMatchStart + lastParaClean.length).coerceIn(startOffsetInCh + 1, canonical.length.coerceAtLeast(1))

        val absStartChar = chapterStartCharOffset + startOffsetInCh
        val absEndChar = (chapterStartCharOffset + endOffsetInCh - 1).coerceAtLeast(absStartChar)
        val startLoc = (absStartChar / CHARS_PER_STABLE_LOCATION) + 1
        val endLoc = (absEndChar / CHARS_PER_STABLE_LOCATION) + 1
        return Pair(startLoc, endLoc.coerceAtLeast(startLoc))
    }

    /**
     * Parses EPUB 2 NCX `<pageList><pageTarget>...</pageTarget></pageList>` metadata.
     * Returns map of `fileKey -> Map<anchorId, pageLabel>`.
     */
    private fun parseNcxPageList(ncxBytes: ByteArray): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        try {
            val ncxString = String(ncxBytes, Charsets.UTF_8)
            val pageListMatch = Regex(
                "<(?:[a-zA-Z0-9]+:)?pageList\\b[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?pageList>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(ncxString) ?: return result

            val pageListBody = pageListMatch.groups[1]?.value ?: return result
            val targetRegex = Regex(
                "<(?:[a-zA-Z0-9]+:)?pageTarget\\b([^>]*)>(.*?)</(?:[a-zA-Z0-9]+:)?pageTarget>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (targetMatch in targetRegex.findAll(pageListBody)) {
                val attrs = targetMatch.groups[1]?.value ?: ""
                val inner = targetMatch.groups[2]?.value ?: ""
                val textLabel = Regex(
                    "<(?:[a-zA-Z0-9]+:)?text\\b[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?text>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(inner)?.groups?.get(1)?.value?.cleanHtml()?.trim()
                    ?: Regex("\\bvalue\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim()
                    ?: continue
                val src = Regex("<(?:[a-zA-Z0-9]+:)?content\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(inner)?.groups?.get(1)?.value?.trim() ?: continue
                val cleanLabel = sanitizePageEquivalentLabel(textLabel)
                if (cleanLabel.isEmpty()) continue
                val filePart = src.substringBefore("#").substringAfterLast("/").trim()
                val fragment = if (src.contains("#")) src.substringAfter("#").trim() else ""
                if (filePart.isNotEmpty()) {
                    val fileMap = result.getOrPut(filePart) { LinkedHashMap() }
                    fileMap[fragment] = cleanLabel
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * Parses Adobe `<page-map>` XML metadata if present in the EPUB.
     */
    private fun parseAdobePageMap(pageMapBytes: ByteArray): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        try {
            val xml = String(pageMapBytes, Charsets.UTF_8)
            val pageRegex = Regex("<page\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
            for (m in pageRegex.findAll(xml)) {
                val attrs = m.groups[1]?.value ?: continue
                val name = Regex("\\bname\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim() ?: continue
                val href = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim() ?: continue
                val cleanLabel = sanitizePageEquivalentLabel(name)
                if (cleanLabel.isEmpty()) continue
                val filePart = href.substringBefore("#").substringAfterLast("/").trim()
                val fragment = if (href.contains("#")) href.substringAfter("#").trim() else ""
                if (filePart.isNotEmpty()) {
                    val fileMap = result.getOrPut(filePart) { LinkedHashMap() }
                    fileMap[fragment] = cleanLabel
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun sanitizePageEquivalentLabel(raw: String): String {
        val trimmed = raw.trim()
            .replace(Regex("^(?:page|pg\\.?|p\\.?)\\s*", RegexOption.IGNORE_CASE), "")
            .trim('[', ']', '(', ')', ' ')
        return trimmed
    }

    private fun parseNcxTitles(ncxBytes: ByteArray): Map<String, String> {
        val titles = mutableMapOf<String, String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(ncxBytes), "UTF-8")
            var eventType = parser.eventType
            var currentTag = ""
            var currentLabel = ""
            var insidePageList = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()
                        if (currentTag == "pagelist") {
                            insidePageList = true
                        } else if (!insidePageList && currentTag == "content") {
                            val src = parser.getAttributeValue(null, "src") ?: ""
                            val fileSrc = src.substringBefore("#").substringAfterLast("/")
                            if (fileSrc.isNotEmpty() && currentLabel.isNotEmpty() && !titles.containsKey(fileSrc)) {
                                titles[fileSrc] = currentLabel
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (!insidePageList && currentTag == "text" && text.isNotEmpty() && currentLabel.isEmpty()) {
                            currentLabel = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("pageList", ignoreCase = true)) {
                            insidePageList = false
                        } else if (parser.name.equals("navPoint", ignoreCase = true)) {
                            currentLabel = ""
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return titles
    }

    private fun parseOpfPathFromContainer(containerBytes: ByteArray): String {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(containerBytes), "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrBlank()) return fullPath
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return "content.opf"
    }

    private data class ExtractedChapterContentResult(
        val extractedTitle: String,
        val paragraphs: List<String>,
        val footnotes: Map<String, String>,
        val paragraphPageEquivalents: List<String>,
        val endingPageLabel: String
    )

    private const val PAGE_BREAK_SENTINEL_PREFIX = "⟦EPUB_PAGE_EQ:"
    private const val PAGE_BREAK_SENTINEL_SUFFIX = "⟧"

    /**
     * Injects invisible sentinel tokens (`⟦EPUB_PAGE_EQ:42⟧`) at print-equivalent page break anchors
     * inside the chapter XHTML before stripping HTML tags, so each extracted paragraph knows its exact
     * embedded print-equivalent page number (or page span if a page break occurs mid-paragraph),
     * while stripping the sentinel from the visible paragraph text!
     */
    private fun injectPageBreakSentinels(
        html: String,
        externalPageListAnchors: Map<String, String>
    ): String {
        var result = html

        // 1. Replace explicit EPUB 3 / WAI-ARIA DPUB pagebreak tags:
        //    e.g. <span epub:type="pagebreak" title="42" id="page42"/> or <a role="doc-pagebreak" aria-label="42">42</a>
        val explicitPageBreakPairedRegex = Regex(
            "<(span|a|div)([^>]*(?:epub:type|role|class)\\s*=\\s*[\"'][^\"']*(?:pagebreak|doc-pagebreak|page-break|pagenum)[^\"']*[\"'][^>]*)>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        result = explicitPageBreakPairedRegex.replace(result) { m ->
            val attrs = m.groups[2]?.value ?: ""
            val innerText = m.groups[3]?.value?.cleanHtml()?.trim() ?: ""
            val idVal = Regex("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
            val titleVal = Regex("\\b(?:title|aria-label|data-page|value)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
            val fromExternal = if (idVal.isNotEmpty()) {
                externalPageListAnchors[idVal] ?: externalPageListAnchors.entries.firstOrNull { it.key.equals(idVal, ignoreCase = true) }?.value
            } else null
            val candidate = fromExternal ?: titleVal.ifBlank { innerText }.ifBlank {
                Regex("^(?:page|pg|p)[_\\-.]?([0-9ivxlcdm]+)$", RegexOption.IGNORE_CASE).find(idVal)?.groups?.get(1)?.value ?: ""
            }
            val clean = sanitizePageEquivalentLabel(candidate)
            if (clean.isNotEmpty()) " $PAGE_BREAK_SENTINEL_PREFIX$clean$PAGE_BREAK_SENTINEL_SUFFIX " else m.value
        }

        val explicitPageBreakSelfClosingRegex = Regex(
            "<(span|a|div|hr)([^>]*(?:epub:type|role|class)\\s*=\\s*[\"'][^\"']*(?:pagebreak|doc-pagebreak|page-break|pagenum)[^\"']*[\"'][^>]*)/>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        result = explicitPageBreakSelfClosingRegex.replace(result) { m ->
            val attrs = m.groups[2]?.value ?: ""
            val idVal = Regex("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
            val titleVal = Regex("\\b(?:title|aria-label|data-page|value)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value ?: ""
            val fromExternal = if (idVal.isNotEmpty()) {
                externalPageListAnchors[idVal] ?: externalPageListAnchors.entries.firstOrNull { it.key.equals(idVal, ignoreCase = true) }?.value
            } else null
            val candidate = fromExternal ?: titleVal.ifBlank {
                Regex("^(?:page|pg|p)[_\\-.]?([0-9ivxlcdm]+)$", RegexOption.IGNORE_CASE).find(idVal)?.groups?.get(1)?.value ?: ""
            }
            val clean = sanitizePageEquivalentLabel(candidate)
            if (clean.isNotEmpty()) " $PAGE_BREAK_SENTINEL_PREFIX$clean$PAGE_BREAK_SENTINEL_SUFFIX " else m.value
        }

        // 2. Inject sentinels for any remaining anchor IDs or name attributes referenced by the NCX <pageList>, EPUB 3 <nav epub:type="page-list">, or Adobe page-map
        for ((rawAnchorId, pageLabel) in externalPageListAnchors) {
            if (rawAnchorId.isBlank() || pageLabel.isBlank()) continue
            val decodedAnchorId = try { java.net.URLDecoder.decode(rawAnchorId, "UTF-8") } catch (_: Exception) { rawAnchorId }
            val candidateIds = setOf(rawAnchorId, decodedAnchorId).filter { it.isNotBlank() }
            for (anchorId in candidateIds) {
                val escapedId = Regex.escape(anchorId)
                val idTagRegex = Regex("(<([a-zA-Z0-9:]+)\\b[^>]*\\b(?:id|name)\\s*=\\s*[\"']$escapedId[\"'][^>]*>)", RegexOption.IGNORE_CASE)
                result = idTagRegex.replace(result) { m ->
                    val fullTag = m.groups[1]?.value ?: m.value
                    val tagName = m.groups[2]?.value?.lowercase() ?: ""
                    val sentinel = " $PAGE_BREAK_SENTINEL_PREFIX$pageLabel$PAGE_BREAK_SENTINEL_SUFFIX "
                    if (fullTag.endsWith("/>")) {
                        "$sentinel$fullTag"
                    } else if (tagName in setOf("p", "blockquote", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6")) {
                        // Place sentinel immediately AFTER the opening <p id="..."> tag so it is inside the paragraph body!
                        "$fullTag$sentinel"
                    } else {
                        "$sentinel$fullTag"
                    }
                }
            }
        }

        // 3. Also detect common publisher inline page anchor tags (<a id="page_8"/>, <span id="page8"></span>, <a name="p8"></a>)
        //    even if the external NCX/nav pageList used slightly different hrefs or wasn't linked to those IDs!
        val inlinePageIdAnchorRegex = Regex(
            "<(a|span)\\b([^>]*\\b(?:id|name)\\s*=\\s*[\"'](?:page|pg)[_\\-.]?([0-9]+|[ivxlcdm]+)[\"'][^>]*)(?:/>|>\\s*</\\1>)",
            setOf(RegexOption.IGNORE_CASE)
        )
        result = inlinePageIdAnchorRegex.replace(result) { m ->
            val pageNumCandidate = m.groups[3]?.value?.trim() ?: ""
            val clean = sanitizePageEquivalentLabel(pageNumCandidate)
            if (clean.isNotEmpty()) " $PAGE_BREAK_SENTINEL_PREFIX$clean$PAGE_BREAK_SENTINEL_SUFFIX " else m.value
        }

        return result
    }

    private fun extractReadableContentWithPageMarkers(
        html: String,
        chapterIndex: Int,
        externalPageListAnchors: Map<String, String> = emptyMap(),
        initialPageLabel: String = ""
    ): ExtractedChapterContentResult {
        val htmlWithSentinels = injectPageBreakSentinels(html, externalPageListAnchors)
        val (extractedTitleRaw, rawParasWithSentinels, footnotes) = extractReadableContent(htmlWithSentinels, chapterIndex)
        val sentinelRegex = Regex("${Regex.escape(PAGE_BREAK_SENTINEL_PREFIX)}(.*?)${Regex.escape(PAGE_BREAK_SENTINEL_SUFFIX)}")
        val cleanExtractedTitle = extractedTitleRaw.replace(sentinelRegex, "").replace(Regex("\\s+"), " ").trim()
            .ifBlank { "Chapter $chapterIndex" }

        val cleanParagraphs = mutableListOf<String>()
        val paragraphPageEquivalents = mutableListOf<String>()
        var currentActivePageLabel = initialPageLabel.trim()
        var matchedSentinelCount = 0

        for (rawPara in rawParasWithSentinels) {
            val matches = sentinelRegex.findAll(rawPara).toList()

            if (matches.isEmpty()) {
                val strippedPara = rawPara.replace(Regex("[ \\t]{2,}"), " ").trim()
                if (strippedPara.isNotBlank()) {
                    cleanParagraphs.add(strippedPara)
                    paragraphPageEquivalents.add(currentActivePageLabel)
                }
            } else {
                matchedSentinelCount += matches.size
                // Split the paragraph at each sentinel token so we know the exact text on each print page!
                var cursor = 0
                val segments = mutableListOf<Pair<String, String>>() // (textSegment, pageLabel)
                for (match in matches) {
                    val beforeText = rawPara.substring(cursor, match.range.first)
                        .replace(sentinelRegex, " ")
                        .replace(Regex("[ \\t]{2,}"), " ")
                        .trim()
                    if (beforeText.isNotBlank()) {
                        segments.add(Pair(beforeText, currentActivePageLabel))
                    }
                    val newLabel = match.groups[1]?.value?.trim() ?: ""
                    if (newLabel.isNotBlank()) {
                        currentActivePageLabel = newLabel
                    }
                    cursor = match.range.last + 1
                }
                val trailingText = rawPara.substring(cursor)
                    .replace(sentinelRegex, " ")
                    .replace(Regex("[ \\t]{2,}"), " ")
                    .trim()
                if (trailingText.isNotBlank()) {
                    segments.add(Pair(trailingText, currentActivePageLabel))
                }

                // Merge consecutive segments that share the same pageLabel
                val mergedSegments = mutableListOf<Pair<String, String>>()
                for ((segText, segPage) in segments) {
                    val last = mergedSegments.lastOrNull()
                    if (last != null && last.second.equals(segPage, ignoreCase = true)) {
                        mergedSegments[mergedSegments.lastIndex] = Pair("${last.first} $segText", last.second)
                    } else {
                        mergedSegments.add(Pair(segText, segPage))
                    }
                }

                for ((segText, segPage) in mergedSegments) {
                    if (segText.isNotBlank()) {
                        cleanParagraphs.add(segText)
                        paragraphPageEquivalents.add(segPage)
                    }
                }
            }
        }

        // Fallback: If the EPUB's external Page List (NCX <pageList>, EPUB3 <nav epub:type="page-list">, or Adobe page-map)
        // declared multiple print pages for this XHTML file (e.g. pages 7, 8, 9, 10...) using CFI/synthetic/unmatched anchors,
        // distribute those ordered print page numbers proportionally by character offset across the chapter's paragraphs
        // so the printed page number advances naturally through the chapter instead of staying stuck on the first page!
        val orderedExternalLabels = externalPageListAnchors.values.filter { it.isNotBlank() }.distinct()
        if (orderedExternalLabels.size > 1 && matchedSentinelCount <= 1 && cleanParagraphs.isNotEmpty()) {
            val totalChars = cleanParagraphs.sumOf { it.length.coerceAtLeast(1) }.coerceAtLeast(1)
            var cumulativeChars = 0
            for (i in cleanParagraphs.indices) {
                val ratio = cumulativeChars.toFloat() / totalChars.toFloat()
                val labelIdx = (ratio * orderedExternalLabels.size).toInt().coerceIn(0, orderedExternalLabels.size - 1)
                paragraphPageEquivalents[i] = orderedExternalLabels[labelIdx]
                cumulativeChars += cleanParagraphs[i].length.coerceAtLeast(1)
            }
            currentActivePageLabel = orderedExternalLabels.last()
        } else if (paragraphPageEquivalents.isNotEmpty() && paragraphPageEquivalents.first().isBlank()) {
            // Backfill leading paragraphs before the first anchor in the chapter with the first anchor's page if initialPageLabel was blank
            val firstNonBlank = paragraphPageEquivalents.firstOrNull { it.isNotBlank() } ?: orderedExternalLabels.firstOrNull() ?: ""
            if (firstNonBlank.isNotBlank()) {
                for (i in paragraphPageEquivalents.indices) {
                    if (paragraphPageEquivalents[i].isBlank()) {
                        paragraphPageEquivalents[i] = firstNonBlank
                    } else {
                        break
                    }
                }
                if (currentActivePageLabel.isBlank()) {
                    currentActivePageLabel = paragraphPageEquivalents.lastOrNull { it.isNotBlank() } ?: firstNonBlank
                }
            }
        }

        val cleanFootnotes = footnotes.mapValues { (_, v) ->
            v.replace(sentinelRegex, "").replace(Regex("[ \\t]{2,}"), " ").trim()
        }

        return ExtractedChapterContentResult(
            extractedTitle = cleanExtractedTitle,
            paragraphs = cleanParagraphs,
            footnotes = cleanFootnotes,
            paragraphPageEquivalents = paragraphPageEquivalents,
            endingPageLabel = currentActivePageLabel
        )
    }

    /**
     * Resolves the exact academic citation for a highlighted quote inside an [EpubBook]:
     * - Always resolves `Chapter + Paragraph` (e.g. `"Chapter 1: Foundations, ¶ 3"` or `"Chapter 1: Foundations, ¶¶ 3–4"`).
     * - If the EPUB contains Page List / print-equivalent page markers, also resolves `pageEquivalent` (e.g. `"42"` or `"42–43"`).
     * - Computes 1-based `stableLocationStart` and `stableLocationEnd` (1 location per 150 canonical characters across spine).
     */
    fun resolvePassageCitation(
        epubBook: EpubBook?,
        quote: String,
        fallbackChapterTitle: String
    ): EpubPassageCitation {
        val cleanFallbackChapter = fallbackChapterTitle.trim().ifBlank { "Chapter 1" }
        val trimmedQuote = quote.trim()
        if (epubBook == null || epubBook.chapters.isEmpty() || trimmedQuote.isEmpty()) {
            val fallbackCfi = EpubCfiEngine.generateCfiForBookSelection(
                epubBook = null,
                quote = trimmedQuote,
                fallbackChapterTitle = cleanFallbackChapter,
                fallbackParagraphCitation = "$cleanFallbackChapter, ¶ 1"
            )
            return EpubPassageCitation(
                chapterTitle = cleanFallbackChapter,
                startParagraphNumber = 1,
                endParagraphNumber = 1,
                pageEquivalent = null,
                stableLocationStart = 1,
                stableLocationEnd = 1,
                chapterAndParagraphCitation = "$cleanFallbackChapter, ¶ 1",
                epubCfi = fallbackCfi
            )
        }

        fun normalize(s: String): String = s.lowercase()
            .replace(Regex("[“”\"'’‘«»]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val quoteBlocks = trimmedQuote.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotBlank() }
        val firstBlockNorm = normalize(quoteBlocks.firstOrNull() ?: trimmedQuote)
        val lastBlockNorm = normalize(quoteBlocks.lastOrNull() ?: trimmedQuote)
        val prefixProbe = firstBlockNorm.split(" ").filter { it.isNotBlank() }.take(5).joinToString(" ")

        // Prefer searching the matching chapter first, then all chapters
        val orderedChapters = epubBook.chapters.sortedByDescending {
            it.title.trim().equals(cleanFallbackChapter, ignoreCase = true)
        }

        for (chapter in orderedChapters) {
            if (chapter.paragraphs.isEmpty() && chapter.semanticBlocks.isEmpty()) continue

            // If semanticBlocks are present, count ONLY genuine <p> paragraphs (isCountedParagraph == true),
            // so titles, subtitles, blockquotes, lists, figure captions, and footnotes are NEVER miscounted as paragraphs!
            if (chapter.semanticBlocks.isNotEmpty()) {
                var startSemIdx = -1
                var endSemIdx = -1
                for ((idx, sb) in chapter.semanticBlocks.withIndex()) {
                    val normText = normalize(sb.text)
                    if (normText.isEmpty()) continue
                    if (startSemIdx < 0) {
                        if ((firstBlockNorm.isNotEmpty() && normText.contains(firstBlockNorm)) ||
                            (firstBlockNorm.length >= 12 && firstBlockNorm.contains(normText)) ||
                            (prefixProbe.length >= 8 && normText.contains(prefixProbe))
                        ) {
                            startSemIdx = idx
                            endSemIdx = idx
                        }
                    } else {
                        if (quoteBlocks.size > 1 && lastBlockNorm.isNotEmpty() &&
                            (normText.contains(lastBlockNorm) || (normText.length >= 12 && lastBlockNorm.contains(normText)))
                        ) {
                            endSemIdx = idx
                            break
                        }
                    }
                }
                if (startSemIdx >= 0) {
                    val resolvedChapterTitle = chapter.title.trim().ifBlank { cleanFallbackChapter }
                    val rangeBlocks = chapter.semanticBlocks.subList(startSemIdx, (endSemIdx + 1).coerceAtMost(chapter.semanticBlocks.size))
                    val countedInSpan = rangeBlocks.mapNotNull { it.paragraphNumberInChapter }
                    val fallbackNearestPara = chapter.semanticBlocks.take(startSemIdx + 1)
                        .lastOrNull { it.paragraphNumberInChapter != null }?.paragraphNumberInChapter
                        ?: chapter.semanticBlocks.drop(startSemIdx)
                            .firstOrNull { it.paragraphNumberInChapter != null }?.paragraphNumberInChapter
                        ?: 1
                    val startNum = countedInSpan.firstOrNull() ?: fallbackNearestPara
                    val endNum = maxOf(startNum, countedInSpan.lastOrNull() ?: startNum)

                    val pageLabels = rangeBlocks.mapNotNull { it.pageEquivalent.takeIf { eq -> eq.isNotBlank() } }
                    val startPageEq = pageLabels.firstOrNull()?.substringBefore("–")?.trim() ?: ""
                    val endPageEq = pageLabels.lastOrNull()?.substringAfterLast("–")?.trim() ?: startPageEq
                    val resolvedPageEq = when {
                        startPageEq.isNotEmpty() && endPageEq.isNotEmpty() && !startPageEq.equals(endPageEq, ignoreCase = true) ->
                            "$startPageEq–$endPageEq"
                        startPageEq.isNotEmpty() -> startPageEq
                        else -> null
                    }

                    val (stableLocStart, stableLocEnd) = computeSelectionStableLocationRange(
                        chapter = chapter,
                        selectionQuote = trimmedQuote,
                        fallbackTexts = rangeBlocks.map { it.text }
                    )

                    val spineIndex = epubBook.chapters.indexOf(chapter).coerceAtLeast(0)
                    val startBlock = chapter.semanticBlocks[startSemIdx]
                    val endBlock = chapter.semanticBlocks[endSemIdx]
                    val firstQuoteSeg = quoteBlocks.firstOrNull() ?: trimmedQuote
                    val lastQuoteSeg = quoteBlocks.lastOrNull() ?: trimmedQuote
                    val rawStartChar = startBlock.text.indexOf(firstQuoteSeg, ignoreCase = true).takeIf { it >= 0 } ?: 0
                    val rawEndChar = if (startSemIdx == endSemIdx) {
                        (rawStartChar + firstQuoteSeg.length).coerceAtLeast(rawStartChar + 1)
                    } else {
                        val endMatch = endBlock.text.indexOf(lastQuoteSeg, ignoreCase = true)
                        if (endMatch >= 0) (endMatch + lastQuoteSeg.length).coerceAtLeast(1) else lastQuoteSeg.length.coerceAtLeast(1)
                    }
                    val computedCfi = EpubCfiEngine.buildSelectionRangeCfi(
                        spineIndex = spineIndex,
                        spineId = chapter.id,
                        startBlockIndex = startSemIdx,
                        startBlockId = startBlock.anchorIds.firstOrNull().orEmpty(),
                        startCharOffset = rawStartChar,
                        endBlockIndex = endSemIdx,
                        endBlockId = endBlock.anchorIds.firstOrNull().orEmpty(),
                        endCharOffset = rawEndChar
                    )

                    val tempCitation = EpubPassageCitation(
                        chapterTitle = resolvedChapterTitle,
                        startParagraphNumber = startNum,
                        endParagraphNumber = endNum,
                        pageEquivalent = resolvedPageEq ?: epubBook.lookupPageLabel(spineFileKey = chapter.fileKey, spineIndex = spineIndex),
                        stableLocationStart = stableLocStart,
                        stableLocationEnd = stableLocEnd,
                        epubCfi = computedCfi,
                        isFixedLayout = epubBook.isFixedLayout,
                        fixedLayoutPageIndex = spineIndex + 1,
                        fixedLayoutTotalPages = epubBook.chapters.size.coerceAtLeast(1)
                    )
                    val paraCitation = if (startNum == endNum) "$resolvedChapterTitle, ¶ $startNum" else "$resolvedChapterTitle, ¶¶ $startNum–$endNum"
                    return tempCitation.copy(
                        chapterAndParagraphCitation = paraCitation
                    )
                }
            }

            var startParaIdx = -1
            var endParaIdx = -1

            for ((idx, para) in chapter.paragraphs.withIndex()) {
                val normPara = normalize(para)
                if (normPara.isEmpty()) continue
                if (startParaIdx < 0) {
                    if ((firstBlockNorm.isNotEmpty() && normPara.contains(firstBlockNorm)) ||
                        (firstBlockNorm.length >= 12 && firstBlockNorm.contains(normPara)) ||
                        (prefixProbe.length >= 8 && normPara.contains(prefixProbe))
                    ) {
                        startParaIdx = idx
                        endParaIdx = idx
                    }
                } else {
                    if (quoteBlocks.size > 1 && lastBlockNorm.isNotEmpty() &&
                        (normPara.contains(lastBlockNorm) || (normPara.length >= 12 && lastBlockNorm.contains(normPara)))
                    ) {
                        endParaIdx = idx
                        break
                    }
                }
            }

            if (startParaIdx >= 0) {
                val resolvedChapterTitle = chapter.title.trim().ifBlank { cleanFallbackChapter }
                val startNum = startParaIdx + 1
                val endNum = maxOf(startNum, endParaIdx + 1)

                // Resolve Page List equivalent across startParaIdx..endParaIdx if present
                val pageLabels = (startParaIdx..(endParaIdx.coerceAtLeast(startParaIdx)))
                    .mapNotNull { i -> chapter.paragraphPageEquivalents.getOrNull(i)?.takeIf { it.isNotBlank() } }
                val startPageEq = pageLabels.firstOrNull()?.substringBefore("–")?.trim() ?: ""
                val endPageEq = pageLabels.lastOrNull()?.substringAfterLast("–")?.trim() ?: startPageEq
                val resolvedPageEq = when {
                    startPageEq.isNotEmpty() && endPageEq.isNotEmpty() && !startPageEq.equals(endPageEq, ignoreCase = true) ->
                        "$startPageEq–$endPageEq"
                    startPageEq.isNotEmpty() -> startPageEq
                    else -> null
                }

                val spanParas = chapter.paragraphs.subList(startParaIdx, (endParaIdx + 1).coerceAtMost(chapter.paragraphs.size))
                val (stableLocStart, stableLocEnd) = computeSelectionStableLocationRange(
                    chapter = chapter,
                    selectionQuote = trimmedQuote,
                    fallbackTexts = spanParas
                )

                val spineIndex = epubBook.chapters.indexOf(chapter).coerceAtLeast(0)
                val startParaText = chapter.paragraphs[startParaIdx]
                val endParaText = chapter.paragraphs[endParaIdx.coerceAtLeast(startParaIdx)]
                val firstQuoteSeg = quoteBlocks.firstOrNull() ?: trimmedQuote
                val lastQuoteSeg = quoteBlocks.lastOrNull() ?: trimmedQuote
                val rawStartChar = startParaText.indexOf(firstQuoteSeg, ignoreCase = true).takeIf { it >= 0 } ?: 0
                val rawEndChar = if (startParaIdx == endParaIdx) {
                    (rawStartChar + firstQuoteSeg.length).coerceAtLeast(rawStartChar + 1)
                } else {
                    val endMatch = endParaText.indexOf(lastQuoteSeg, ignoreCase = true)
                    if (endMatch >= 0) (endMatch + lastQuoteSeg.length).coerceAtLeast(1) else lastQuoteSeg.length.coerceAtLeast(1)
                }
                val computedCfi = EpubCfiEngine.buildSelectionRangeCfi(
                    spineIndex = spineIndex,
                    spineId = chapter.id,
                    startBlockIndex = startParaIdx,
                    startCharOffset = rawStartChar,
                    endBlockIndex = endParaIdx.coerceAtLeast(startParaIdx),
                    endCharOffset = rawEndChar
                )

                val tempCitation = EpubPassageCitation(
                    chapterTitle = resolvedChapterTitle,
                    startParagraphNumber = startNum,
                    endParagraphNumber = endNum,
                    pageEquivalent = resolvedPageEq ?: epubBook.lookupPageLabel(spineFileKey = chapter.fileKey, spineIndex = spineIndex),
                    stableLocationStart = stableLocStart,
                    stableLocationEnd = stableLocEnd,
                    epubCfi = computedCfi,
                    isFixedLayout = epubBook.isFixedLayout,
                    fixedLayoutPageIndex = spineIndex + 1,
                    fixedLayoutTotalPages = epubBook.chapters.size.coerceAtLeast(1)
                )
                val paraCitation = if (startNum == endNum) "$resolvedChapterTitle, ¶ $startNum" else "$resolvedChapterTitle, ¶¶ $startNum–$endNum"
                return tempCitation.copy(
                    chapterAndParagraphCitation = paraCitation
                )
            }
        }

        val fallbackSpineIdx = epubBook.chapters.indexOfFirst {
            it.title.trim().equals(cleanFallbackChapter, ignoreCase = true)
        }.coerceAtLeast(0)
        val fallbackPageEq = epubBook.lookupPageLabel(
            spineFileKey = epubBook.chapters.getOrNull(fallbackSpineIdx)?.fileKey.orEmpty(),
            spineIndex = fallbackSpineIdx
        )
        val fallbackCfi = EpubCfiEngine.generateCfiForBookSelection(
            epubBook = epubBook,
            quote = trimmedQuote,
            fallbackChapterTitle = cleanFallbackChapter,
            fallbackParagraphCitation = "$cleanFallbackChapter, ¶ 1"
        )
        val fallbackCitation = EpubPassageCitation(
            chapterTitle = cleanFallbackChapter,
            startParagraphNumber = 1,
            endParagraphNumber = 1,
            pageEquivalent = fallbackPageEq,
            stableLocationStart = 1,
            stableLocationEnd = 1,
            epubCfi = fallbackCfi,
            isFixedLayout = epubBook.isFixedLayout,
            fixedLayoutPageIndex = fallbackSpineIdx + 1,
            fixedLayoutTotalPages = epubBook.chapters.size.coerceAtLeast(1)
        )
        return fallbackCitation.copy(
            chapterAndParagraphCitation = "$cleanFallbackChapter, ¶ 1"
        )
    }

    private fun computeSelectionStableLocationRange(
        chapter: EpubChapter,
        selectionQuote: String,
        fallbackTexts: List<String>
    ): Pair<Int, Int> {
        val chapterStartCharOffset = (chapter.startStableLocation - 1).coerceAtLeast(0) * CHARS_PER_STABLE_LOCATION
        val canonical = chapter.canonicalText.ifBlank {
            chapter.paragraphs.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        }
        if (canonical.isEmpty()) {
            return Pair(chapter.startStableLocation, chapter.endStableLocation)
        }
        val cleanQuote = selectionQuote.replace(Regex("\\s+"), " ").trim()
        val probe = cleanQuote.take(40).ifBlank { fallbackTexts.firstOrNull()?.take(36).orEmpty() }
        val startOffsetInCh = if (probe.length >= 4) {
            canonical.indexOf(probe, ignoreCase = true).takeIf { it >= 0 } ?: 0
        } else 0
        val spanLen = cleanQuote.length.coerceAtLeast(fallbackTexts.sumOf { it.length }.coerceAtLeast(1))
        val endOffsetInCh = (startOffsetInCh + spanLen).coerceIn(startOffsetInCh + 1, canonical.length.coerceAtLeast(1))
        val absStart = chapterStartCharOffset + startOffsetInCh
        val absEnd = (chapterStartCharOffset + endOffsetInCh - 1).coerceAtLeast(absStart)
        val startLoc = (absStart / CHARS_PER_STABLE_LOCATION) + 1
        val endLoc = (absEnd / CHARS_PER_STABLE_LOCATION) + 1
        return Pair(startLoc, endLoc.coerceAtLeast(startLoc))
    }

    private fun extractReadableContent(html: String, chapterIndex: Int): Triple<String, List<String>, Map<String, String>> {
        val footnotes = mutableMapOf<String, String>()

        // Strip <head>, <style>, <script>, and HTML comments first
        var workingHtml = html
            .replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        // Convert inline superscript footnote/glossref/biblioref references (e.g. <sup><a href="#fn1">1</a></sup> or <a href="notes.xhtml#fn4"><sup>4</sup></a> or <sup>1</sup>)
        // into bracketed references " [1]" BEFORE stripping tags so numbers don't mash onto words or split across words!
        workingHtml = workingHtml.replace(
            Regex("<a\\b[^>]*>\\s*<sup[^>]*>\\s*\\[?(\\d+[a-zA-Z]?)\\]?\\s*</sup>\\s*</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            " [$1]"
        )
        workingHtml = workingHtml.replace(
            Regex("<sup[^>]*>\\s*(?:<a[^>]*>)?\\s*\\[?(\\d+[a-zA-Z]?)\\]?\\s*(?:</a>)?\\s*</sup>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            " [$1]"
        )
        workingHtml = workingHtml.replace(
            Regex("<a\\b[^>]*>\\s*\\[?(\\d{1,3}[a-zA-Z]?)\\]?\\s*</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            " [$1]"
        )

        // 1a. Extract EPUB 3 semantic footnote, endnote, rearnote, dfn, glossary, and bibliography targets BEFORE stripping tags
        val semanticTargetRegex = Regex(
            "<(aside|div|li|p|dd|section|dfn)\\b([^>]*?(?:epub:type|role)\\s*=\\s*[\"'][^\"']*\\b(?:footnote|endnote|rearnote|dfn|doc-footnote|doc-endnote|doc-rearnote|doc-glossary|doc-bibliography|doc-biblioentry|glossdef|biblioentry)\\b[^\"']*[\"'][^>]*)>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in semanticTargetRegex.findAll(workingHtml)) {
            val attrs = m.groups[2]?.value.orEmpty()
            val inner = m.groups[3]?.value.orEmpty()
            val id = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)?.groups?.get(1)?.value?.trim().orEmpty()
            val noteBody = inner.cleanHtml()
                .replace(Regex("^(?:\\[\\d+[a-zA-Z]?\\]|\\d+[a-zA-Z]?[.):]?)\\s+"), "")
                .replace(Regex("^[↑↩↵^]+\\s*"), "")
                .replace(Regex("\\s*[↑↩↵^]+$"), "")
                .trim()
            if (noteBody.isNotBlank()) {
                if (id.isNotEmpty()) footnotes[id] = noteBody
                val trailingNum = Regex("(\\d+[a-zA-Z]?)$").find(id)?.groups?.get(1)?.value
                if (!trailingNum.isNullOrEmpty() && !footnotes.containsKey(trailingNum)) {
                    footnotes[trailingNum] = noteBody
                }
            }
        }
        // Also extract standalone <dfn id="...">
        val dfnTagRegex = Regex(
            "<dfn\\b([^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*)>(.*?)</dfn>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in dfnTagRegex.findAll(workingHtml)) {
            val id = m.groups[2]?.value?.trim().orEmpty()
            val textVal = m.groups[3]?.value?.cleanHtml().orEmpty()
            if (id.isNotEmpty() && textVal.isNotEmpty() && !footnotes.containsKey(id)) {
                footnotes[id] = textVal
            }
        }

        // 1b. Extract explicit HTML footnote/endnote definitions (<aside>, <li id="fn...">, <div id="fn...">, <p id="fn...">, or <p><a id="fn4">4</a> ...</p>)
        val asideRegex = Regex("<(?:aside|div|li|p|dd)\\b[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</(?:aside|div|li|p|dd)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in asideRegex.findAll(workingHtml)) {
            val id = m.groups[1]?.value?.trim() ?: continue
            val rawInner = m.groups[2]?.value ?: continue
            val noteBody = rawInner.cleanHtml().replace(Regex("^(?:\\[\\d+[a-zA-Z]?\\]|\\d+[a-zA-Z]?[.):]?)\\s+"), "").trim()
            if (noteBody.isNotBlank()) {
                footnotes[id] = noteBody
                val trailingNum = Regex("(\\d+[a-zA-Z]?)$").find(id)?.groups?.get(1)?.value
                if (!trailingNum.isNullOrEmpty() && !footnotes.containsKey(trailingNum)) {
                    footnotes[trailingNum] = noteBody
                }
            }
        }
        // Also extract paragraph footnotes where the anchor id sits on an inner <a> or <span> at the start of <p>:
        // e.g. <p class="footnote"><a id="fn4" href="#ref4">4</a>. Note text...</p> or <div class="note"><a id="n4">[4]</a> Note text...</div>
        // Two-step non-backtracking match to prevent catastrophic regex backtracking on large Notes chapters!
        val candidateFnBlockRegex = Regex(
            "<(?:p|div|li)\\b[^>]*>(.*?)</(?:p|div|li)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val leadingFnAnchorRegex = Regex(
            "^\\s*(?:<sup[^>]*>\\s*)?<(?:a|span)\\b[^>]*\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>\\s*\\[?(\\d{1,3}[a-zA-Z]?)\\]?[.):]?\\s*</(?:a|span)>\\s*(?:</sup>)?[.):]?\\s*(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (blockMatch in candidateFnBlockRegex.findAll(workingHtml)) {
            val innerHtml = blockMatch.groups[1]?.value ?: continue
            if (!innerHtml.contains("id=", ignoreCase = true) && !innerHtml.contains("name=", ignoreCase = true)) continue
            val m = leadingFnAnchorRegex.find(innerHtml) ?: continue
            val id = m.groups[1]?.value?.trim().orEmpty()
            val num = m.groups[2]?.value?.trim().orEmpty()
            val body = m.groups[3]?.value?.cleanHtml()?.trim().orEmpty()
            if (body.length > 2) {
                if (id.isNotEmpty()) footnotes[id] = body
                if (num.isNotEmpty()) footnotes[num] = body
            }
        }

        // Remove footnote/endnote/rearnote <aside> blocks from body text so they do not duplicate in body paragraphs or alter ¶ counts
        workingHtml = workingHtml.replace(
            Regex(
                "<aside\\b[^>]*?(?:epub:type|role)\\s*=\\s*[\"'][^\"']*\\b(?:footnote|endnote|rearnote|doc-footnote|doc-endnote|doc-rearnote)\\b[^\"']*[\"'][^>]*>.*?</aside>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ),
            ""
        )

        // Find chapter title in <h1> or <h2> or <title>
        val titleRegex = Regex("<h[1-2][^>]*>(.*?)</h[1-2]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val titleMatch = titleRegex.find(workingHtml)
        val extractedTitle = titleMatch?.groups?.get(1)?.value?.cleanHtml()?.takeIf { it.isNotBlank() } ?: "Chapter $chapterIndex"

        // Pre-transform structural EPUB 3 blocks (<table>, <pre>, <dl>, callout <aside>, <figure>) into <p> containers
        // so they stay in exact reading order and pair 1:1 with semanticBlocks in EpubSemanticHelper!
        // Tables -> single summary <p>
        workingHtml = Regex("<table\\b[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(workingHtml) { m ->
                val inner = m.groups[1]?.value.orEmpty()
                val rows = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(inner)
                    .mapNotNull { tr ->
                        val cells = Regex("<(th|td)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .findAll(tr.groups[1]?.value.orEmpty())
                            .map { it.groups[2]?.value?.cleanHtml().orEmpty() }
                            .toList()
                        if (cells.any { it.isNotBlank() }) cells.joinToString(" | ") else null
                    }
                    .toList()
                if (rows.isEmpty()) "" else "<p>${rows.joinToString(" / ")}</p>\n"
            }

        // Code blocks (<pre> and <pre><code>) -> preserve newlines via [[PRE_NL]] sentinel
        workingHtml = Regex("<pre\\b[^>]*>(.*?)</pre>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(workingHtml) { m ->
                val rawPre = m.groups[1]?.value.orEmpty()
                    .replace(Regex("</?code\\b[^>]*>", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&#160;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .trimEnd()
                if (rawPre.isBlank()) "" else {
                    val encoded = rawPre.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "[[PRE_NL]]")
                    "<p>[[EPUB_CODE_BLOCK]]$encoded</p>\n"
                }
            }

        // Definition lists (<dl>...</dl>) -> single <p> summary
        workingHtml = Regex("<dl\\b[^>]*>(.*?)</dl>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(workingHtml) { m ->
                val dlInner = m.groups[1]?.value.orEmpty()
                val items = Regex("<(dt|dd)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(dlInner)
                    .mapNotNull { itemMatch ->
                        val tag = itemMatch.groups[1]?.value?.lowercase().orEmpty()
                        val text = itemMatch.groups[2]?.value?.cleanHtml().orEmpty()
                        if (text.isBlank()) null else if (tag == "dt") text else "— $text"
                    }
                    .toList()
                if (items.isEmpty()) "" else "<p>${items.joinToString(" • ")}</p>\n"
            }

        // Callouts / sidebars (<aside> not already stripped as a footnote)
        workingHtml = Regex("<aside\\b([^>]*)>(.*?)</aside>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(workingHtml) { m ->
                val attrs = m.groups[1]?.value.orEmpty()
                val inner = m.groups[2]?.value?.cleanHtml().orEmpty()
                if (inner.isBlank()) "" else {
                    val lowerAttrs = attrs.lowercase()
                    val label = when {
                        lowerAttrs.contains("warning") || lowerAttrs.contains("caution") -> "WARNING"
                        lowerAttrs.contains("tip") -> "TIP"
                        lowerAttrs.contains("sidebar") -> "SIDEBAR"
                        else -> "NOTE"
                    }
                    "<p>[$label] $inner</p>\n"
                }
            }

        // Figures (<figure>...<img/><figcaption>...</figcaption></figure>) -> single <p> summary
        workingHtml = Regex("<figure\\b[^>]*>(.*?)</figure>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(workingHtml) { m ->
                val figInner = m.groups[1]?.value.orEmpty()
                val caption = Regex("<figcaption\\b[^>]*>(.*?)</figcaption>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(figInner)?.groups?.get(1)?.value?.cleanHtml().orEmpty()
                val alt = Regex("<img\\b[^>]*\\balt\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(figInner)?.groups?.get(1)?.value?.cleanHtml().orEmpty()
                val summary = when {
                    caption.isNotBlank() -> caption
                    alt.isNotBlank() -> alt
                    else -> "[Figure]"
                }
                "<p>$summary</p>\n"
            }

        // Standalone <audio> elements -> single <p> summary so they pair 1:1 with semanticBlocks
        workingHtml = Regex("<audio\\b([^>]*)(?:>(.*?)</audio>|/>)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(workingHtml) { m ->
                val attrs = m.groups[1]?.value.orEmpty()
                val inner = m.groups[2]?.value.orEmpty()
                val directSrc = Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groups?.get(1)?.value?.trim().orEmpty()
                val sourceSrc = if (directSrc.isNotEmpty()) directSrc else {
                    Regex("<source\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(inner)?.groups?.get(1)?.value?.trim().orEmpty()
                }
                val cleanSrc = sourceSrc.removePrefix("../").removePrefix("./").removePrefix("/")
                if (cleanSrc.isEmpty()) "" else {
                    val titleLbl = Regex("\\b(?:title|aria-label)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(attrs)?.groups?.get(1)?.value?.trim().orEmpty()
                        .ifBlank { cleanSrc.substringAfterLast("/") }
                        .ifBlank { "Audio" }
                    "<p>$titleLbl</p>\n"
                }
            }

        // Transform ordered lists <ol>...</ol> into numbered <p> paragraphs (1. ..., 2. ...)
        val olRegex = Regex("<ol([^>]*)>(.*?)</ol>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        workingHtml = olRegex.replace(workingHtml) { olMatch ->
            val olAttrs = olMatch.groups[1]?.value ?: ""
            val olBody = olMatch.groups[2]?.value ?: ""
            val startNum = Regex("start=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(olAttrs)?.groups?.get(1)?.value?.toIntOrNull() ?: 1
            val liRegex = Regex("<li([^>]*)>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            var counter = startNum
            val sb = StringBuilder()
            for (liMatch in liRegex.findAll(olBody)) {
                val liAttrs = liMatch.groups[1]?.value ?: ""
                val liInner = liMatch.groups[2]?.value ?: ""
                val explicitVal = Regex("value=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(liAttrs)?.groups?.get(1)?.value?.toIntOrNull()
                val fnIdNum = Regex("id=\"(?:fn|note|endnote)[^0-9]*(\\d+)\"", RegexOption.IGNORE_CASE).find(liAttrs)?.groups?.get(1)?.value?.toIntOrNull()
                val itemNum = explicitVal ?: fnIdNum ?: counter
                val cleanedItem = liInner
                    .replace(Regex("</p>\\s*<p[^>]*>", setOf(RegexOption.IGNORE_CASE)), " ")
                    .cleanHtml()
                if (cleanedItem.isNotBlank()) {
                    // Strip leading duplicate number if the item text already begins with e.g. "1. " or "[1] "
                    val strippedBody = cleanedItem.replace(Regex("^\\[?\\d+\\]?[.):]\\s+"), "").trim()
                    val formatted = "$itemNum. ${strippedBody.ifEmpty { cleanedItem }}"
                    footnotes[itemNum.toString()] = strippedBody.ifEmpty { cleanedItem }
                    sb.append("<p>").append(formatted).append("</p>\n")
                }
                counter = itemNum + 1
            }
            sb.toString()
        }

        // Transform unordered lists <ul>...</ul> into bulleted <p> paragraphs (• ...)
        val ulRegex = Regex("<ul[^>]*>(.*?)</ul>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        workingHtml = ulRegex.replace(workingHtml) { ulMatch ->
            val ulBody = ulMatch.groups[1]?.value ?: ""
            val liRegex = Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val sb = StringBuilder()
            for (liMatch in liRegex.findAll(ulBody)) {
                val liInner = liMatch.groups[1]?.value ?: ""
                val cleanedItem = liInner
                    .replace(Regex("</p>\\s*<p[^>]*>", setOf(RegexOption.IGNORE_CASE)), " ")
                    .cleanHtml()
                if (cleanedItem.isNotBlank()) {
                    val strippedBody = cleanedItem.replace(Regex("^[•\\-*]\\s+"), "").trim()
                    sb.append("<p>• ").append(strippedBody.ifEmpty { cleanedItem }).append("</p>\n")
                }
            }
            sb.toString()
        }

        // Transform any remaining standalone <li> items and definition lists <dt>/<dd>
        workingHtml = workingHtml
            .replace(Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                val text = m.groups[1]?.value?.cleanHtml() ?: ""
                if (text.isNotBlank()) "<p>• $text</p>" else ""
            }
            .replace(Regex("<dt[^>]*>(.*?)</dt>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                val text = m.groups[1]?.value?.cleanHtml() ?: ""
                if (text.isNotBlank()) "<p>• $text</p>" else ""
            }
            .replace(Regex("<dd[^>]*>(.*?)</dd>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                val text = m.groups[1]?.value?.cleanHtml() ?: ""
                if (text.isNotBlank()) "<p>  — $text</p>" else ""
            }
            .replace(Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                val text = m.groups[1]?.value?.cleanHtml() ?: ""
                if (text.isNotBlank() && !isDuplicateChapterHeadingText(text, extractedTitle, isHeadingOrFirstBlock = true)) "<p>## $text</p>" else ""
            }
            .replace(Regex("<h[2-6][^>]*>(.*?)</h[2-6]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                val text = m.groups[1]?.value?.cleanHtml() ?: ""
                if (text.isNotBlank() && !isDuplicateChapterHeadingText(text, extractedTitle, isHeadingOrFirstBlock = true)) "<p>## $text</p>" else ""
            }

        // Extract all <p> and <blockquote> blocks as well as any standalone pagebreak sentinels placed between <p> tags!
        val sentinelPattern = "${Regex.escape(PAGE_BREAK_SENTINEL_PREFIX)}.*?${Regex.escape(PAGE_BREAK_SENTINEL_SUFFIX)}"
        val pOrSentinelRegex = Regex("<(?:p|blockquote)[^>]*>(.*?)</(?:p|blockquote)>|($sentinelPattern)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val pMatches = pOrSentinelRegex.findAll(workingHtml)
        val paragraphs = mutableListOf<String>()

        for (m in pMatches) {
            val standaloneSentinel = m.groups[2]?.value
            if (!standaloneSentinel.isNullOrBlank()) {
                paragraphs.add(standaloneSentinel)
                continue
            }
            val innerRaw = m.groups[1]?.value ?: ""
            if (innerRaw.startsWith("[[EPUB_CODE_BLOCK]]")) {
                val decodedCode = innerRaw.removePrefix("[[EPUB_CODE_BLOCK]]").replace("[[PRE_NL]]", "\n").trimEnd()
                if (decodedCode.isNotBlank()) {
                    paragraphs.add(decodedCode)
                }
                continue
            }
            // Split if <br><br> was used inside a single <p> tag for paragraph breaks
            val subBlocks = innerRaw.split(Regex("(?:<br\\s*/?>\\s*){2,}", RegexOption.IGNORE_CASE))
            for (sub in subBlocks) {
                val text = sub.cleanHtml()
                if (text.isNotBlank()) {
                    // If the very first paragraph in the chapter is just the chapter title (e.g. <p><a href="toc.xhtml">Kapittel 1</a></p>), skip it so the heading isn't repeated!
                    if (paragraphs.none { !it.startsWith(PAGE_BREAK_SENTINEL_PREFIX) } &&
                        isDuplicateChapterHeadingText(text, extractedTitle, isHeadingOrFirstBlock = true)
                    ) {
                        continue
                    }
                    val fnMatch = Regex("^(?:\\[(\\d{1,3}[a-zA-Z]?)\\]|(\\d{1,3}[a-zA-Z]?)[.):]|(\\d{1,3})\\s+(?=[A-ZÆØÅ“\"']))\\s*(.*)$").find(text)
                    if (fnMatch != null) {
                        val num = fnMatch.groups[1]?.value ?: fnMatch.groups[2]?.value ?: fnMatch.groups[3]?.value ?: ""
                        val content = fnMatch.groups[4]?.value?.trim() ?: ""
                        if (num.isNotEmpty() && content.length > 3) {
                            footnotes[num] = content
                        }
                    }
                    paragraphs.add(text)
                }
            }
        }

        // Also check if the HTML used <div>, <section>, or <br> blocks without standard <p> tags
        val blockDelimited = workingHtml
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(?:div|p|section|article|blockquote|h[1-6]|li|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
        val rawBlocks = blockDelimited
            .split(Regex("\n\\s*\n+"))
            .map { it.cleanHtml() }
            .filter { it.isNotBlank() }

        val totalExtractedChars = paragraphs.sumOf { it.length }
        val totalRawChars = rawBlocks.sumOf { it.length }

        val finalParas = if (paragraphs.isNotEmpty() && totalExtractedChars >= (totalRawChars * 0.60).toInt()) {
            paragraphs
        } else if (rawBlocks.isNotEmpty()) {
            rawBlocks
        } else {
            paragraphs
        }

        return Triple(extractedTitle, finalParas, footnotes)
    }

    fun paginateTextArticle(
        title: String,
        author: String,
        text: String,
        fontSizeSp: Float = 18f,
        lineSpacingMultiplier: Float = 1.4f,
        marginDp: Int = 20,
        viewportWidthDp: Int = 400,
        viewportHeightDp: Int = 760
    ): Pair<List<EpubPage>, List<BookChapter>> {
        val rawParas = text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val footnotes = mutableMapOf<String, String>()
        for (p in rawParas) {
            val fnMatch = Regex("^(?:\\[(\\d+)\\]|(\\d+)[.):])\\s+(.*)$").find(p)
            if (fnMatch != null) {
                val num = fnMatch.groups[1]?.value ?: fnMatch.groups[2]?.value ?: ""
                val content = fnMatch.groups[3]?.value ?: ""
                if (num.isNotEmpty() && content.isNotBlank()) {
                    footnotes[num] = content
                }
            }
        }

        val syntheticBook = EpubBook(
            title = title,
            author = author,
            description = "",
            coverBitmap = generateBookCoverBitmap(title, author),
            chapters = listOf(
                EpubChapter(
                    id = "article-main",
                    title = title,
                    content = text,
                    paragraphs = if (rawParas.isEmpty()) listOf(text.trim().ifBlank { "Article content." }) else rawParas,
                    footnotes = footnotes
                )
            )
        )
        return paginateBook(syntheticBook, fontSizeSp, lineSpacingMultiplier, marginDp, viewportWidthDp, viewportHeightDp)
    }

    /**
     * Parses a standalone HTML / HTM / XHTML document into an [EpubBook], automatically extracting:
     * - Document `<title>` or primary `<h1>`
     * - `<meta name="author" content="...">` or `dc.creator`
     * - Chapter/section structure split across `<h1>` / `<h2>` headings (or a single readable chapter)
     * - Clean paragraphs, blockquotes, ordered/unordered lists, and interactive footnotes.
     */
    fun parseHtmlDocument(
        rawHtml: String,
        fallbackTitle: String = "HTML Document",
        fallbackAuthor: String = "Unknown Author"
    ): EpubBook {
        val htmlTitleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(rawHtml)
        val h1Match = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(rawHtml)
        val extractedTitle = htmlTitleMatch?.groups?.get(1)?.value?.cleanHtml()?.takeIf { it.isNotBlank() }
            ?: h1Match?.groups?.get(1)?.value?.cleanHtml()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle.ifBlank { "HTML Document" }

        val metaAuthorMatch = Regex(
            "<meta[^>]+(?:name|property)=[\"'](?:author|dc\\.creator|article:author|og:site_name)[\"'][^>]+content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).find(rawHtml) ?: Regex(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:name|property)=[\"'](?:author|dc\\.creator|article:author)[\"']",
            RegexOption.IGNORE_CASE
        ).find(rawHtml)

        val extractedAuthor = metaAuthorMatch?.groups?.get(1)?.value?.cleanHtml()?.takeIf { it.isNotBlank() }
            ?: fallbackAuthor.ifBlank { "Unknown Author" }

        // Split into logical chapters if multiple <h1> or <h2> sections exist
        val bodyContent = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(rawHtml)?.groups?.get(1)?.value ?: rawHtml

        // Also extract document-wide footnotes so footnotes placed at the end of the HTML file
        // are available in all sections/chapters
        val (_, _, globalFootnotes) = extractReadableContent(bodyContent, 1)
        val htmlPageListAnchors = parseEpub3NavPageList(rawHtml).values.fold(mutableMapOf<String, String>()) { acc, m ->
            acc.putAll(m)
            acc
        }

        val sectionChunks = bodyContent.split(Regex("(?=<h[1-2]\\b[^>]*>)", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chapters = mutableListOf<EpubChapter>()
        var carryOverPageLabel = htmlPageListAnchors[""] ?: ""
        var anyPageEq = htmlPageListAnchors.isNotEmpty()
        if (sectionChunks.size > 1) {
            var chNum = 1
            for (chunk in sectionChunks) {
                val (secTitle, paras, fns, paraPageLabels, endingLabel) = extractReadableContentWithPageMarkers(
                    html = chunk,
                    chapterIndex = chNum,
                    externalPageListAnchors = htmlPageListAnchors,
                    initialPageLabel = carryOverPageLabel
                )
                if (endingLabel.isNotBlank()) carryOverPageLabel = endingLabel
                if (paraPageLabels.any { it.isNotBlank() }) anyPageEq = true
                if (paras.isNotEmpty()) {
                    val resolvedSecTitle = if (secTitle == "Chapter $chNum" && chNum == 1) extractedTitle else secTitle
                    val mergedFootnotes = globalFootnotes + fns
                    val semExtraction = EpubSemanticHelper.extractSemanticStructureForChapter(
                        rawHtml = chunk,
                        fileKey = "html_sec_$chNum",
                        chapterIndex = chNum,
                        chapterTitle = resolvedSecTitle,
                        existingParagraphs = paras,
                        existingPageEquivalents = paraPageLabels,
                        existingFootnotes = mergedFootnotes
                    )
                    chapters.add(
                        EpubChapter(
                            id = "html_sec_$chNum",
                            title = resolvedSecTitle,
                            content = paras.joinToString("\n\n"),
                            paragraphs = paras,
                            footnotes = mergedFootnotes,
                            htmlContent = chunk,
                            paragraphPageEquivalents = paraPageLabels,
                            fileKey = "html_sec_$chNum",
                            semanticBlocks = semExtraction.semanticBlocks,
                            footnoteEntries = semExtraction.footnoteEntries,
                            anchorToBlockIndex = semExtraction.anchorToBlockIndex
                        )
                    )
                    chNum++
                }
            }
        }

        if (chapters.isEmpty()) {
            val (secTitle, paras, fns, paraPageLabels, _) = extractReadableContentWithPageMarkers(
                html = rawHtml,
                chapterIndex = 1,
                externalPageListAnchors = htmlPageListAnchors,
                initialPageLabel = carryOverPageLabel
            )
            if (paraPageLabels.any { it.isNotBlank() }) anyPageEq = true
            val resolvedTitle = if (secTitle == "Chapter 1") extractedTitle else secTitle
            val finalParas = if (paras.isNotEmpty()) {
                paras
            } else {
                val fallbackPlain = rawHtml.cleanHtml()
                if (fallbackPlain.isNotBlank()) listOf(fallbackPlain) else listOf("Empty HTML document.")
            }
            val semExtraction = EpubSemanticHelper.extractSemanticStructureForChapter(
                rawHtml = bodyContent,
                fileKey = "html_main",
                chapterIndex = 1,
                chapterTitle = resolvedTitle,
                existingParagraphs = finalParas,
                existingPageEquivalents = paraPageLabels,
                existingFootnotes = fns
            )
            chapters.add(
                EpubChapter(
                    id = "html_main",
                    title = resolvedTitle,
                    content = finalParas.joinToString("\n\n"),
                    paragraphs = finalParas,
                    footnotes = fns,
                    htmlContent = bodyContent,
                    paragraphPageEquivalents = paraPageLabels,
                    fileKey = "html_main",
                    semanticBlocks = semExtraction.semanticBlocks,
                    footnoteEntries = semExtraction.footnoteEntries,
                    anchorToBlockIndex = semExtraction.anchorToBlockIndex
                )
            )
        }

        val tocFromNav = EpubSemanticHelper.parseEpub3NavHierarchy(rawHtml, "document.xhtml")
        val resolvedToc = if (tocFromNav.isNotEmpty()) tocFromNav else EpubSemanticHelper.buildTocFromHeadings(chapters)
        val htmlLandmarks = parseEpub3Landmarks(rawHtml)
        val mergedFootnoteEntries = LinkedHashMap<String, EpubFootnoteEntry>()
        chapters.forEach { mergedFootnoteEntries.putAll(it.footnoteEntries) }

        val htmlDirAttr = Regex("<(?:html|body)\\b[^>]*\\bdir\\s*=\\s*[\"'](rtl|ltr)[\"']", RegexOption.IGNORE_CASE)
            .find(rawHtml)?.groups?.get(1)?.value?.lowercase().orEmpty()
        val htmlLangAttr = Regex("<html\\b[^>]*\\b(?:xml:)?lang\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(rawHtml)?.groups?.get(1)?.value?.trim().orEmpty()
        val resolvedHtmlDir = resolvePageProgressionDirection(htmlDirAttr, htmlLangAttr)

        return EpubBook(
            title = extractedTitle,
            author = extractedAuthor,
            description = "",
            coverBitmap = try { generateBookCoverBitmap(extractedTitle, extractedAuthor) } catch (_: Throwable) { null },
            chapters = chapters,
            hasPageListMetadata = anyPageEq,
            tocItems = resolvedToc,
            footnoteEntries = mergedFootnoteEntries,
            landmarks = htmlLandmarks,
            pageProgressionDirection = resolvedHtmlDir,
            language = htmlLangAttr.ifBlank { "en" }
        )
    }

    fun paginateHtmlDocument(
        title: String,
        author: String,
        rawHtml: String,
        fontSizeSp: Float = 18f,
        lineSpacingMultiplier: Float = 1.4f,
        marginDp: Int = 20,
        viewportWidthDp: Int = 400,
        viewportHeightDp: Int = 760
    ): Pair<List<EpubPage>, List<BookChapter>> {
        val htmlBook = parseHtmlDocument(
            rawHtml = rawHtml,
            fallbackTitle = title,
            fallbackAuthor = author
        )
        val effectiveBook = htmlBook.copy(
            title = title.ifBlank { htmlBook.title },
            author = author.ifBlank { htmlBook.author },
            coverBitmap = generateBookCoverBitmap(title.ifBlank { htmlBook.title }, author.ifBlank { htmlBook.author })
        )
        return paginateBook(effectiveBook, fontSizeSp, lineSpacingMultiplier, marginDp, viewportWidthDp, viewportHeightDp)
    }

    private fun String.cleanHtml(): String {
        var result = this
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&rdquo;", "”")
            .replace("&ldquo;", "“")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&bull;", "•")

        // Decode decimal numeric entities like &#8217;
        result = Regex("&#(\\d+);").replace(result) { m ->
            val code = m.groups[1]?.value?.toIntOrNull()
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        // Decode hex numeric entities like &#x2019;
        result = Regex("&#x([0-9a-fA-F]+);").replace(result) { m ->
            val code = m.groups[1]?.value?.toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
        }

        // Collapse horizontal whitespace and unwanted single newlines inside paragraphs while preserving explicit line breaks
        return result
            .replace(Regex("[ \\t\\r\\n]+"), " ")
            .trim()
    }
}
