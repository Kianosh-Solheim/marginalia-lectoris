package com.example.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipFile

private const val DAISY_PERF_TAG = "LoadPerf"



data class DaisyQuickMetadata(
    val title: String,
    val author: String,
    val estimatedPages: Int,
    val formatVersion: String,
    val coverBitmap: Bitmap? = null
)

data class DaisyNavItem(
    val id: String,
    val title: String,
    val level: Int, // 1..6 for h1..h6 or navPoint depth; 0 for page target
    val smilHref: String,
    val smilAnchor: String,
    val textFileKey: String,
    val textAnchorId: String,
    val audioFile: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
    val pageNumber: Int = 1,
    val isPageNumberTarget: Boolean = false,
    val pageLabel: String = ""
) {
    fun formatTimecode(): String = DaisyParserEngine.formatTimeMs(clipBeginMs)
}

data class DaisySmilClip(
    val id: String,
    val index: Int,
    val smilFile: String,
    val parId: String,
    val textFileKey: String,
    val textAnchorId: String,
    val textSnippet: String,
    val audioFile: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
    val globalStartMs: Long,
    val globalEndMs: Long,
    val chapterTitle: String,
    val pageNumber: Int = 1,
    val pageEquivalent: String = ""
) {
    val durationMs: Long
        get() = (clipEndMs - clipBeginMs).coerceAtLeast(0L)
}

data class DaisyBookIndex(
    val formatVersion: String, // "DAISY 2.02", "DAISY 3", or "DAISY Audio-Only"
    val title: String,
    val author: String,
    val totalDurationMs: Long,
    val extractedDir: String,
    val sourceArchivePath: String = "",
    val nccNavItems: List<DaisyNavItem>,
    val pageTargets: List<DaisyNavItem>,
    val smilClips: List<DaisySmilClip>,
    val hasRecordedAudio: Boolean,
    val epubBook: EpubBook
) {
    /**
     * Fast O(Pages + Clips) page-number resolution using HashMaps instead of nested linear scans.
     */
    fun withResolvedPageNumbers(pages: List<EpubPage>): DaisyBookIndex {
        if (pages.isEmpty()) return this

        val pageByFileAndAnchor = HashMap<String, Int>(pages.size * 4)
        val pageByAnchorOnly = HashMap<String, Int>(pages.size * 4)
        val pageByFileOnly = HashMap<String, Int>(pages.size)
        val pageByPageEq = HashMap<String, Int>(pages.size * 2)

        for ((idx, p) in pages.withIndex()) {
            if (p.isCoverPage) continue
            val pageNum = idx + 1
            val fKey = p.fileKey.substringAfterLast("/").lowercase(Locale.ROOT)
            if (fKey.isNotEmpty()) {
                pageByFileOnly.putIfAbsent(fKey, pageNum)
            }
            if (p.pageEquivalentLabel.isNotBlank()) {
                pageByPageEq.putIfAbsent(p.pageEquivalentLabel.trim().lowercase(Locale.ROOT), pageNum)
            }
            for (eq in p.paragraphPageEquivalents) {
                if (eq.isNotBlank()) {
                    pageByPageEq.putIfAbsent(eq.trim().lowercase(Locale.ROOT), pageNum)
                }
            }
            for (anc in p.anchorIds) {
                val cleanAnc = anc.trim().lowercase(Locale.ROOT)
                if (cleanAnc.isNotEmpty()) {
                    pageByAnchorOnly.putIfAbsent(cleanAnc, pageNum)
                    if (fKey.isNotEmpty()) {
                        pageByFileAndAnchor.putIfAbsent("$fKey#$cleanAnc", pageNum)
                    }
                }
            }
        }

        fun resolvePageFast(fileKey: String, anchorId: String, fallbackPage: Int): Int {
            val cleanFile = fileKey.substringAfterLast("/").lowercase(Locale.ROOT)
            val cleanAnchor = anchorId.trim().lowercase(Locale.ROOT)
            if (cleanAnchor.isNotEmpty()) {
                if (cleanFile.isNotEmpty()) {
                    pageByFileAndAnchor["$cleanFile#$cleanAnchor"]?.let { return it }
                }
                pageByAnchorOnly[cleanAnchor]?.let { return it }
            }
            if (cleanFile.isNotEmpty()) {
                pageByFileOnly[cleanFile]?.let { return it }
            }
            return fallbackPage.coerceIn(1, pages.size.coerceAtLeast(1))
        }

        var lastResolvedClipPage = 1
        val updatedClips = smilClips.map { clip ->
            val resolved = resolvePageFast(
                fileKey = clip.textFileKey,
                anchorId = clip.textAnchorId,
                fallbackPage = lastResolvedClipPage
            )
            lastResolvedClipPage = resolved
            clip.copy(pageNumber = resolved)
        }

        val clipBySmilAndPar = HashMap<String, DaisySmilClip>(updatedClips.size * 2)
        val clipBySmilFirst = HashMap<String, DaisySmilClip>()
        val clipByTextAnchor = HashMap<String, DaisySmilClip>(updatedClips.size)
        for (c in updatedClips) {
            val smilLower = c.smilFile.lowercase(Locale.ROOT)
            clipBySmilFirst.putIfAbsent(smilLower, c)
            if (c.parId.isNotBlank()) {
                clipBySmilAndPar.putIfAbsent("$smilLower#${c.parId.lowercase(Locale.ROOT)}", c)
            }
            if (c.id.isNotBlank()) {
                clipBySmilAndPar.putIfAbsent("$smilLower#${c.id.lowercase(Locale.ROOT)}", c)
            }
            if (c.textAnchorId.isNotBlank()) {
                clipByTextAnchor.putIfAbsent(c.textAnchorId.lowercase(Locale.ROOT), c)
            }
        }

        val updatedNavItems = nccNavItems.map { nav ->
            val smilLower = nav.smilFileKey().lowercase(Locale.ROOT)
            val ancLower = nav.smilAnchor.lowercase(Locale.ROOT)
            val matchedClip = (if (smilLower.isNotEmpty() && ancLower.isNotEmpty()) clipBySmilAndPar["$smilLower#$ancLower"] else null)
                ?: (if (nav.textAnchorId.isNotEmpty()) clipByTextAnchor[nav.textAnchorId.lowercase(Locale.ROOT)] else null)
                ?: (if (smilLower.isNotEmpty()) clipBySmilFirst[smilLower] else null)

            val resolvedPage = matchedClip?.pageNumber ?: resolvePageFast(
                fileKey = nav.textFileKey,
                anchorId = nav.textAnchorId,
                fallbackPage = nav.pageNumber
            )
            nav.copy(pageNumber = resolvedPage)
        }

        val updatedPageTargets = pageTargets.map { pt ->
            val byEq = if (pt.pageLabel.isNotBlank()) {
                pageByPageEq[pt.pageLabel.trim().lowercase(Locale.ROOT)]
            } else null

            val resolvedPage = byEq ?: resolvePageFast(
                fileKey = pt.textFileKey,
                anchorId = pt.textAnchorId,
                fallbackPage = pt.pageNumber
            )
            pt.copy(pageNumber = resolvedPage)
        }

        return copy(
            nccNavItems = updatedNavItems,
            pageTargets = updatedPageTargets,
            smilClips = updatedClips
        )
    }

    private fun DaisyNavItem.smilFileKey(): String = smilHref.substringBefore("#").substringAfterLast("/")
}

object DaisyParserEngine {

    private val audioExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac", "opus", "flac")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private const val IO_BUFFER_SIZE = 128 * 1024

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val smilParseDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4)

    private val parsedDaisyCache = object : LruCache<String, DaisyBookIndex>(2) {}

    fun getCachedDaisyBook(cacheKey: String): DaisyBookIndex? {
        if (cacheKey.isBlank()) return null
        return synchronized(parsedDaisyCache) {
            parsedDaisyCache.get(cacheKey)
        }
    }

    fun putCachedDaisyBook(cacheKey: String, index: DaisyBookIndex) {
        if (cacheKey.isBlank()) return
        synchronized(parsedDaisyCache) {
            parsedDaisyCache.put(cacheKey, index)
        }
    }


    /**
     * Fast inspection of a `.zip` file to determine if it is a DAISY 2.02, DAISY 3, or DAISY Audio archive.
     */
    fun isDaisyZipArchive(zipFile: File): Boolean {
        if (!zipFile.exists() || !zipFile.isFile || zipFile.length() == 0L) return false
        return try {
            ZipFile(zipFile).use { zip ->
                var hasNcc = false
                var hasSmil = false
                var hasNcx = false
                var hasOpf = false
                var hasDtbookXml = false
                var hasAudio = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast("/").lowercase(Locale.ROOT)
                    val ext = name.substringAfterLast(".", "")
                    when {
                        name == "ncc.html" || name == "ncc.htm" -> return true
                        ext == "smil" || ext == "smi" -> hasSmil = true
                        ext == "ncx" -> hasNcx = true
                        ext == "opf" -> hasOpf = true
                        ext == "xml" && !name.contains("container") -> hasDtbookXml = true
                        ext in audioExtensions -> hasAudio = true
                    }
                    if (hasSmil && (hasAudio || hasNcx || hasOpf || hasDtbookXml)) return true
                }
                hasNcc || (hasSmil && (hasAudio || hasNcx || hasOpf || hasDtbookXml))
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Ultra-fast (< 10ms) metadata peek directly from the `.zip` central directory without extracting
     * any MP3s or running full book pagination. Used by `AddDocumentDialog` for instant import!
     */
    fun peekDaisyZipMetadata(
        zipFile: File,
        fallbackTitle: String = "DAISY Book",
        fallbackAuthor: String = ""
    ): DaisyQuickMetadata {
        var resolvedTitle = fallbackTitle.ifBlank { "DAISY Book" }
        var resolvedAuthor = fallbackAuthor
        var estimatedPages = 1
        var formatVersion = "DAISY 2.02"
        var coverBitmap: Bitmap? = null

        if (!zipFile.exists() || !zipFile.isFile) {
            return DaisyQuickMetadata(resolvedTitle, resolvedAuthor, estimatedPages, formatVersion, null)
        }

        try {
            ZipFile(zipFile).use { zip ->
                var nccEntryName: String? = null
                var opfEntryName: String? = null
                var ncxEntryName: String? = null
                var coverEntryName: String? = null
                var firstImageEntryName: String? = null
                var smilCount = 0

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name.substringAfterLast("/").lowercase(Locale.ROOT)
                    val ext = name.substringAfterLast(".", "")
                    when {
                        name == "ncc.html" || name == "ncc.htm" -> nccEntryName = e.name
                        ext == "opf" && opfEntryName == null -> opfEntryName = e.name
                        ext == "ncx" && ncxEntryName == null -> ncxEntryName = e.name
                        ext == "smil" || ext == "smi" -> smilCount++
                        ext in imageExtensions -> {
                            if (firstImageEntryName == null) firstImageEntryName = e.name
                            if (coverEntryName == null && (name.contains("cover") || name.contains("front"))) {
                                coverEntryName = e.name
                            }
                        }
                    }
                }

                if (nccEntryName != null) {
                    formatVersion = "DAISY 2.02"
                    val entry = zip.getEntry(nccEntryName)
                    if (entry != null) {
                        val rawNcc = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val (t, a) = parseMetaFromHtml(rawNcc)
                        if (t.isNotBlank()) resolvedTitle = t
                        if (a.isNotBlank()) resolvedAuthor = a
                        val pageNormalStr = parseMetaValueByName(rawNcc, "ncc:pagenormal")
                            .ifBlank { parseMetaValueByName(rawNcc, "ncc:maxpagenormal") }
                        val pageNormal = pageNormalStr.toIntOrNull() ?: 0
                        estimatedPages = if (pageNormal > 0) pageNormal else (smilCount * 3).coerceAtLeast(10)
                    }
                } else if (opfEntryName != null) {
                    formatVersion = "DAISY 3"
                    val entry = zip.getEntry(opfEntryName)
                    if (entry != null) {
                        val rawOpf = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val (t, a) = parseMetaFromOpf(rawOpf)
                        if (t.isNotBlank()) resolvedTitle = t
                        if (a.isNotBlank()) resolvedAuthor = a
                        estimatedPages = (smilCount * 3).coerceAtLeast(10)
                    }
                } else if (ncxEntryName != null) {
                    formatVersion = "DAISY 3"
                    estimatedPages = (smilCount * 3).coerceAtLeast(10)
                }

                val chosenImgName = coverEntryName ?: firstImageEntryName
                if (chosenImgName != null) {
                    val imgEntry = zip.getEntry(chosenImgName)
                    if (imgEntry != null && imgEntry.size in 1..(8 * 1024 * 1024)) {
                        coverBitmap = try {
                            zip.getInputStream(imgEntry).use { BitmapFactory.decodeStream(it) }
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (coverBitmap == null) {
            coverBitmap = try {
                EpubParser.generateBookCoverBitmap(resolvedTitle, resolvedAuthor.ifBlank { formatVersion })
            } catch (_: Throwable) {
                null
            }
        }

        return DaisyQuickMetadata(
            title = resolvedTitle,
            author = resolvedAuthor,
            estimatedPages = estimatedPages.coerceAtLeast(1),
            formatVersion = formatVersion,
            coverBitmap = coverBitmap
        )
    }

    /**
     * Phase 1 Fast Extraction: Extracts all non-audio files (`ncc.html`, `.smil`, `.html`, `.xml`, `.opf`, `.ncx`, images)
     * in < 50ms so the book opens immediately, and returns the list of virtual audio files inside `targetExtractDir`.
     */
    fun extractDaisyTextAndStructureFiles(zipFile: File, targetExtractDir: File): List<File> {
        if (!targetExtractDir.exists()) {
            targetExtractDir.mkdirs()
        }
        val textMarkerFile = File(targetExtractDir, ".daisy_text_extracted_v2")
        val audioTargetFiles = mutableListOf<File>()

        ZipFile(zipFile).use { zip ->
            val canonicalRoot = targetExtractDir.canonicalPath
            val textAlreadyExtracted = textMarkerFile.exists()
            val entries = zip.entries()
            val buffer = ByteArray(IO_BUFFER_SIZE)

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val cleanEntryName = entry.name.replace('\\', '/').trimStart('/')
                if (cleanEntryName.isBlank()) continue
                val outFile = File(targetExtractDir, cleanEntryName)
                if (!outFile.canonicalPath.startsWith(canonicalRoot)) continue

                if (entry.isDirectory) {
                    if (!textAlreadyExtracted) outFile.mkdirs()
                    continue
                }

                val ext = outFile.extension.lowercase(Locale.ROOT)
                if (ext in audioExtensions) {
                    audioTargetFiles.add(outFile)
                    continue
                }

                if (!textAlreadyExtracted || !outFile.exists() || outFile.length() == 0L) {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } >= 0) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }
            }
        }
        try {
            if (!textMarkerFile.exists()) {
                textMarkerFile.writeText(System.currentTimeMillis().toString())
            }
        } catch (_: Throwable) {}
        return audioTargetFiles
    }

    /**
     * Extracts a single audio file on-demand from `sourceZipPath` in ~20ms if the user hits Play
     * before background audio extraction has reached that track.
     */
    fun ensureAudioFileExtracted(sourceZipPath: String, targetAudioFile: File): Boolean {
        if (targetAudioFile.exists() && targetAudioFile.length() > 0L) return true
        if (sourceZipPath.isBlank()) return false
        val zipFile = File(sourceZipPath)
        if (!zipFile.exists() || !zipFile.isFile) return false

        return synchronized(this) {
            if (targetAudioFile.exists() && targetAudioFile.length() > 0L) return@synchronized true
            try {
                ZipFile(zipFile).use { zip ->
                    val targetNameLower = targetAudioFile.name.lowercase(Locale.ROOT)
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val entryFileName = entry.name.substringAfterLast("/").lowercase(Locale.ROOT)
                        if (entryFileName == targetNameLower) {
                            targetAudioFile.parentFile?.mkdirs()
                            val tempFile = File(targetAudioFile.parentFile, "${targetAudioFile.name}.part")
                            val buffer = ByteArray(IO_BUFFER_SIZE)
                            zip.getInputStream(entry).use { input ->
                                tempFile.outputStream().use { output ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } >= 0) {
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                            tempFile.renameTo(targetAudioFile)
                            return@synchronized targetAudioFile.exists() && targetAudioFile.length() > 0L
                        }
                    }
                }
                false
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Extracts all remaining audio tracks from `zipFile` into `targetExtractDir` in the background.
     */
    fun extractDaisyAudioFilesInBackground(zipFile: File, targetExtractDir: File) {
        if (!zipFile.exists() || !zipFile.isFile) return
        val fullMarker = File(targetExtractDir, ".daisy_extracted_v1")
        if (fullMarker.exists()) return

        try {
            ZipFile(zipFile).use { zip ->
                val canonicalRoot = targetExtractDir.canonicalPath
                val entries = zip.entries()
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val cleanEntryName = entry.name.replace('\\', '/').trimStart('/')
                    if (cleanEntryName.isBlank()) continue
                    val outFile = File(targetExtractDir, cleanEntryName)
                    if (!outFile.canonicalPath.startsWith(canonicalRoot)) continue
                    val ext = outFile.extension.lowercase(Locale.ROOT)
                    if (ext !in audioExtensions) continue
                    if (outFile.exists() && outFile.length() > 0L) continue

                    synchronized(this) {
                        if (!outFile.exists() || outFile.length() == 0L) {
                            outFile.parentFile?.mkdirs()
                            val tempFile = File(outFile.parentFile, "${outFile.name}.part")
                            zip.getInputStream(entry).use { input ->
                                tempFile.outputStream().use { output ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } >= 0) {
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                            tempFile.renameTo(outFile)
                        }
                    }
                }
            }
            fullMarker.writeText(System.currentTimeMillis().toString())
        } catch (_: Throwable) {}
    }

    /**
     * Resolves the extracted DAISY directory given either the `.zip` file or a file/directory inside the document folder.
     */
    fun resolveExtractedDaisyDirectory(sourceFileOrDir: File): File {
        if (sourceFileOrDir.isDirectory) {
            return sourceFileOrDir
        }
        val parentDir = sourceFileOrDir.parentFile ?: File(sourceFileOrDir.absolutePath + "_daisy")
        val extractSubdir = File(parentDir, "daisy_unpacked")
        if (sourceFileOrDir.exists() && sourceFileOrDir.name.lowercase(Locale.ROOT).endsWith(".zip")) {
            extractDaisyTextAndStructureFiles(sourceFileOrDir, extractSubdir)
            return extractSubdir
        }
        return if (extractSubdir.exists()) extractSubdir else parentDir
    }

    private data class RawNavNode(
        val id: String,
        val title: String,
        val level: Int,
        val href: String,
        val isPageTarget: Boolean,
        val pageLabel: String
    )

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains("&")) return input
        return input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace(Regex("&#(\\d+);")) { m ->
                val code = m.groupValues[1].toIntOrNull()
                if (code != null && code in 32..65535) code.toChar().toString() else ""
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                val code = m.groupValues[1].toIntOrNull(16)
                if (code != null && code in 32..65535) code.toChar().toString() else ""
            }
    }

    private fun stripHtmlTags(raw: String): String {
        if (!raw.contains('<') && !raw.contains('&')) {
            return raw.replace(Regex("\\s+"), " ").trim()
        }
        val sb = StringBuilder(raw.length)
        var inTag = false
        for (i in 0 until raw.length) {
            val c = raw[i]
            if (c == '<') {
                inTag = true
                sb.append(' ')
            } else if (c == '>') {
                inTag = false
            } else if (!inTag) {
                sb.append(c)
            }
        }
        return decodeHtmlEntities(sb.toString()).replace(Regex("\\s+"), " ").trim()
    }

    private fun extractAttributeValue(tagAttrs: String, attrName: String): String {
        val pattern = Regex("""(?i)\b${Regex.escape(attrName)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""")
        val m = pattern.find(tagAttrs) ?: return ""
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
        return decodeHtmlEntities(raw).trim()
    }

    private fun parseMetaValueByName(rawHtml: String, targetNameLower: String): String {
        val metaRegex = Regex("""(?is)<meta\b([^>]+)>""")
        for (m in metaRegex.findAll(rawHtml)) {
            val attrs = m.groupValues[1]
            val name = extractAttributeValue(attrs, "name").lowercase(Locale.ROOT)
            if (name == targetNameLower) {
                return extractAttributeValue(attrs, "content")
            }
        }
        return ""
    }

    private fun parseMetaFromHtml(rawHtml: String): Pair<String, String> {
        var foundTitle = ""
        var foundAuthor = ""
        val headSlice = if (rawHtml.length > 16384) rawHtml.substring(0, 16384) else rawHtml
        val metaRegex = Regex("""(?is)<meta\b([^>]+)>""")
        for (m in metaRegex.findAll(headSlice)) {
            val attrs = m.groupValues[1]
            val name = extractAttributeValue(attrs, "name").lowercase(Locale.ROOT)
            val content = extractAttributeValue(attrs, "content")
            if (content.isNotBlank()) {
                when (name) {
                    "dc:title", "dc.title", "title" -> if (foundTitle.isBlank()) foundTitle = content
                    "dc:creator", "dc.creator", "author", "ncc:narrator" -> if (foundAuthor.isBlank()) foundAuthor = content
                }
            }
        }
        if (foundTitle.isBlank()) {
            val titleMatch = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(headSlice)
            if (titleMatch != null) {
                foundTitle = stripHtmlTags(titleMatch.groupValues[1])
            }
        }
        return Pair(foundTitle, foundAuthor)
    }

    private fun parseMetaFromOpf(rawOpf: String): Pair<String, String> {
        var foundTitle = ""
        var foundAuthor = ""
        val titleMatch = Regex("""(?is)<(?:dc:)?title[^>]*>(.*?)</(?:dc:)?title>""").find(rawOpf)
        if (titleMatch != null) {
            foundTitle = stripHtmlTags(titleMatch.groupValues[1])
        }
        val creatorMatch = Regex("""(?is)<(?:dc:)?creator[^>]*>(.*?)</(?:dc:)?creator>""").find(rawOpf)
        if (creatorMatch != null) {
            foundAuthor = stripHtmlTags(creatorMatch.groupValues[1])
        }
        return Pair(foundTitle, foundAuthor)
    }

    /**
     * Fast O(N) non-backtracking parser for `ncc.html`.
     */
    private fun parseNccHtmlNodes(rawNcc: String): List<RawNavNode> {
        val nodes = mutableListOf<RawNavNode>()
        val openTagRegex = Regex("""(?is)<(h[1-6]|span)\b([^>]*)>""")
        var counter = 1

        for (match in openTagRegex.findAll(rawNcc)) {
            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            val attrs = match.groupValues[2]
            val classAttr = extractAttributeValue(attrs, "class").lowercase(Locale.ROOT)

            val isHeading = tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()
            val isPageSpan = tag == "span" && (
                classAttr.contains("page-normal") ||
                    classAttr.contains("page-front") ||
                    classAttr.contains("page-special") ||
                    classAttr.contains("pagenum")
                )
            if (!isHeading && !isPageSpan) continue

            val contentStart = match.range.last + 1
            val closeIdx = rawNcc.indexOf("</$tag", contentStart, ignoreCase = true)
            if (closeIdx < 0) continue
            val innerHtml = rawNcc.substring(contentStart, closeIdx.coerceAtMost(contentStart + 2000))

            val aOpenIdx = innerHtml.indexOf("<a", ignoreCase = true)
            var aAttrs = ""
            if (aOpenIdx >= 0) {
                val aCloseBracket = innerHtml.indexOf('>', aOpenIdx)
                if (aCloseBracket > aOpenIdx) {
                    aAttrs = innerHtml.substring(aOpenIdx + 2, aCloseBracket)
                }
            }
            val href = extractAttributeValue(aAttrs, "href").ifBlank { extractAttributeValue(attrs, "href") }
            val text = stripHtmlTags(innerHtml)
            if (text.isBlank() && href.isBlank()) continue

            val id = extractAttributeValue(attrs, "id")
                .ifBlank { extractAttributeValue(aAttrs, "id") }
                .ifBlank { "ncc_$counter" }
            counter++

            if (isHeading) {
                val lvl = tag[1].digitToInt().coerceIn(1, 6)
                nodes.add(
                    RawNavNode(
                        id = id,
                        title = text.ifBlank { "Section $counter" },
                        level = lvl,
                        href = href,
                        isPageTarget = false,
                        pageLabel = ""
                    )
                )
            } else {
                val cleanPageLabel = text.replace(Regex("^(?i)(page|side|p\\.|s\\.)\\s*"), "").trim().ifBlank { text }
                nodes.add(
                    RawNavNode(
                        id = id,
                        title = "Page $cleanPageLabel",
                        level = 0,
                        href = href,
                        isPageTarget = true,
                        pageLabel = cleanPageLabel
                    )
                )
            }
        }
        return nodes
    }

    private fun parseNcxNodes(rawNcx: String): Pair<String, List<RawNavNode>> {
        val nodes = mutableListOf<RawNavNode>()
        var docTitle = ""
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(rawNcx))

            var eventType = parser.eventType
            var inDocTitle = false
            var inNavLabel = false
            var navPointDepth = 0
            var inPageTarget = false
            val navStackIds = ArrayDeque<String>()
            val navStackLabels = ArrayDeque<String>()
            val navStackSrcs = ArrayDeque<String>()
            var currentPageId = ""
            var currentPageValue = ""
            var currentPageLabel = ""
            var currentPageSrc = ""
            var counter = 1

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.substringAfterLast(":").lowercase(Locale.ROOT)
                        when (name) {
                            "doctitle" -> inDocTitle = true
                            "navpoint" -> {
                                navPointDepth++
                                val id = parser.getAttributeValue(null, "id")?.trim().orEmpty().ifBlank { "ncx_${counter++}" }
                                navStackIds.addLast(id)
                                navStackLabels.addLast("")
                                navStackSrcs.addLast("")
                            }
                            "pagetarget" -> {
                                inPageTarget = true
                                currentPageId = parser.getAttributeValue(null, "id")?.trim().orEmpty().ifBlank { "pt_${counter++}" }
                                currentPageValue = parser.getAttributeValue(null, "value")?.trim().orEmpty()
                                currentPageLabel = ""
                                currentPageSrc = ""
                            }
                            "navlabel" -> inNavLabel = true
                            "text" -> {
                                val t = parser.nextText().trim()
                                if (inDocTitle && docTitle.isBlank()) {
                                    docTitle = t
                                } else if (inPageTarget && inNavLabel) {
                                    currentPageLabel = t
                                } else if (navPointDepth > 0 && inNavLabel && navStackLabels.isNotEmpty()) {
                                    navStackLabels.removeLast()
                                    navStackLabels.addLast(t)
                                }
                            }
                            "content" -> {
                                val src = parser.getAttributeValue(null, "src")?.trim().orEmpty()
                                if (inPageTarget) {
                                    currentPageSrc = src
                                } else if (navPointDepth > 0 && navStackSrcs.isNotEmpty()) {
                                    val existing = navStackSrcs.last()
                                    if (existing.isBlank()) {
                                        navStackSrcs.removeLast()
                                        navStackSrcs.addLast(src)
                                        val id = navStackIds.last()
                                        val label = navStackLabels.last()
                                        nodes.add(
                                            RawNavNode(
                                                id = id,
                                                title = label.ifBlank { "Section $counter" },
                                                level = navPointDepth.coerceIn(1, 6),
                                                href = src,
                                                isPageTarget = false,
                                                pageLabel = ""
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.substringAfterLast(":").lowercase(Locale.ROOT)
                        when (name) {
                            "doctitle" -> inDocTitle = false
                            "navlabel" -> inNavLabel = false
                            "navpoint" -> {
                                if (navStackIds.isNotEmpty()) navStackIds.removeLast()
                                if (navStackLabels.isNotEmpty()) navStackLabels.removeLast()
                                if (navStackSrcs.isNotEmpty()) navStackSrcs.removeLast()
                                navPointDepth = (navPointDepth - 1).coerceAtLeast(0)
                            }
                            "pagetarget" -> {
                                val lbl = currentPageLabel.ifBlank { currentPageValue }
                                if (lbl.isNotBlank() && currentPageSrc.isNotBlank()) {
                                    nodes.add(
                                        RawNavNode(
                                            id = currentPageId,
                                            title = "Page $lbl",
                                            level = 0,
                                            href = currentPageSrc,
                                            isPageTarget = true,
                                            pageLabel = lbl
                                        )
                                    )
                                }
                                inPageTarget = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return Pair(docTitle, nodes)
    }

    private fun parseOpfSmilSpineOrder(rawOpf: String): List<String> {
        val manifestMap = LinkedHashMap<String, String>()
        val itemRegex = Regex("""(?is)<item\b([^>]+)>""")
        for (m in itemRegex.findAll(rawOpf)) {
            val attrs = m.groupValues[1]
            val id = extractAttributeValue(attrs, "id")
            val href = extractAttributeValue(attrs, "href")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifestMap[id] = href
            }
        }
        val orderedHrefs = mutableListOf<String>()
        val itemrefRegex = Regex("""(?is)<itemref\b([^>]+)>""")
        for (m in itemrefRegex.findAll(rawOpf)) {
            val idref = extractAttributeValue(m.groupValues[1], "idref")
            val href = manifestMap[idref]
            if (!href.isNullOrBlank()) {
                orderedHrefs.add(href)
            }
        }
        return orderedHrefs
    }

    /**
     * Fast linear O(N) indexer for HTML/XML element IDs without any regex backreferences!
     * Scans opening tags that contain `id="..."` and extracts up to 500 characters of inner text.
     */
    private fun indexHtmlElementIds(
        rawContent: String,
        fileKey: String,
        idToTextMap: MutableMap<String, Pair<String, String>>
    ) {
        val fileLower = fileKey.lowercase(Locale.ROOT)
        val openTagWithIdRegex = Regex("""<([a-zA-Z0-9:_-]+)\b[^>]*\bid\s*=\s*(?:"([^"]+)"|'([^']+)')[^>]*>""")
        val skipContainerTags = setOf("html", "head", "body", "section", "div", "book", "dtbook", "bodymatter", "frontmatter", "rearmatter", "level1", "level2", "level3")

        for (m in openTagWithIdRegex.findAll(rawContent)) {
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            val idVal = m.groupValues[2].ifEmpty { m.groupValues[3] }.trim()
            if (idVal.isBlank()) continue
            val startIdx = m.range.last + 1
            if (startIdx >= rawContent.length) continue

            // Find the closing tag for this element or a reasonable bounded window (max 1200 chars)
            val windowEnd = (startIdx + 1200).coerceAtMost(rawContent.length)
            val closeTagIdx = if (tag in skipContainerTags) {
                // For container divs/sections, only grab up to the first child block end or 350 chars
                val nextBlockEnd = rawContent.indexOf("</", startIdx)
                if (nextBlockEnd in startIdx until windowEnd) nextBlockEnd else (startIdx + 350).coerceAtMost(rawContent.length)
            } else {
                val exactClose = rawContent.indexOf("</$tag", startIdx, ignoreCase = true)
                if (exactClose in startIdx until windowEnd) exactClose else windowEnd
            }

            val slice = rawContent.substring(startIdx, closeTagIdx)
            val txt = stripHtmlTags(slice)
            if (txt.isNotBlank()) {
                idToTextMap["$fileLower#$idVal"] = Pair(fileKey, txt)
                idToTextMap.putIfAbsent("#$idVal", Pair(fileKey, txt))
            }
        }
    }

    /**
     * Fast O(N) chapter and paragraph extractor for DAISY content HTML/XHTML files.
     * Avoids heavy multi-pass regexes on multi-megabyte single-file DAISY books.
     */
    private fun parseDaisyHtmlChaptersFast(
        rawHtml: String,
        fileKey: String,
        fallbackTitle: String
    ): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        val blockOpenRegex = Regex("""(?is)<(h[1-6]|p|blockquote|li|span)\b([^>]*)>""")
        val idAttrRegex = Regex("""(?i)\bid\s*=\s*(?:"([^"]+)"|'([^']+)')""")

        var currentTitle = fallbackTitle
        var paragraphs = mutableListOf<String>()
        var pageEqs = mutableListOf<String>()
        var semBlocks = mutableListOf<EpubSemanticBlock>()
        var currentPageEq = ""
        var paraCounter = 1
        var chapterCounter = 1

        fun flushChapter() {
            if (paragraphs.isNotEmpty()) {
                chapters.add(
                    EpubChapter(
                        id = "${fileKey}_ch_${chapterCounter++}",
                        title = currentTitle,
                        content = paragraphs.joinToString("\n\n"),
                        paragraphs = paragraphs,
                        paragraphPageEquivalents = pageEqs,
                        fileKey = fileKey,
                        semanticBlocks = semBlocks
                    )
                )
                paragraphs = mutableListOf()
                pageEqs = mutableListOf()
                semBlocks = mutableListOf()
                paraCounter = 1
            }
        }

        for (m in blockOpenRegex.findAll(rawHtml)) {
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            val attrs = m.groupValues[2]

            if (tag == "span") {
                val cls = extractAttributeValue(attrs, "class").lowercase(Locale.ROOT)
                if (cls.contains("page-normal") || cls.contains("page-front") || cls.contains("page-special") || cls.contains("pagenum")) {
                    val sIdx = m.range.last + 1
                    val eIdx = rawHtml.indexOf("</span", sIdx, ignoreCase = true).takeIf { it >= sIdx }
                        ?: (sIdx + 120).coerceAtMost(rawHtml.length)
                    val pgText = stripHtmlTags(rawHtml.substring(sIdx, eIdx.coerceAtMost(sIdx + 200)))
                        .replace(Regex("^(?i)(page|side|p\\.|s\\.)\\s*"), "")
                        .trim()
                    if (pgText.isNotBlank()) {
                        currentPageEq = pgText
                    }
                }
                continue
            }

            val contentStart = m.range.last + 1
            if (contentStart >= rawHtml.length) continue
            val closeIdx = rawHtml.indexOf("</$tag", contentStart, ignoreCase = true)
                .takeIf { it >= contentStart }
                ?: (contentStart + 2500).coerceAtMost(rawHtml.length)
            val boundedEnd = closeIdx.coerceAtMost(contentStart + 8000)
            val innerHtml = rawHtml.substring(contentStart, boundedEnd)
            val text = stripHtmlTags(innerHtml)
            if (text.isBlank()) continue

            val isH1OrH2 = tag == "h1" || tag == "h2"
            val isHeading = tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()

            if (isH1OrH2 && paragraphs.isNotEmpty()) {
                flushChapter()
                currentTitle = text
            } else if (isH1OrH2 && paragraphs.isEmpty()) {
                currentTitle = text
            }

            val ids = ArrayList<String>(4)
            val topId = extractAttributeValue(attrs, "id")
            if (topId.isNotBlank()) ids.add(topId)
            for (idM in idAttrRegex.findAll(innerHtml)) {
                val cid = idM.groupValues[1].ifEmpty { idM.groupValues[2] }.trim()
                if (cid.isNotBlank() && !ids.contains(cid)) ids.add(cid)
            }

            val formattedText = when {
                isHeading -> "## $text"
                tag == "li" -> "• $text"
                else -> text
            }
            val blockType = when {
                isHeading -> EpubSemanticBlockType.HEADING
                tag == "blockquote" -> EpubSemanticBlockType.BLOCKQUOTE
                tag == "li" -> EpubSemanticBlockType.LIST_ITEM
                else -> EpubSemanticBlockType.PARAGRAPH
            }
            paragraphs.add(formattedText)
            pageEqs.add(currentPageEq)
            semBlocks.add(
                EpubSemanticBlock(
                    type = blockType,
                    text = text,
                    headingLevel = if (isHeading) (tag[1].digitToIntOrNull() ?: 2) else 0,
                    anchorIds = ids,
                    paragraphNumberInChapter = if (isHeading) null else paraCounter++,
                    pageEquivalent = currentPageEq,
                    fileKey = fileKey
                )
            )
        }

        flushChapter()
        return chapters
    }

    private data class RawSmilPar(
        val parId: String,
        val textSrc: String,
        val textIdAttr: String,
        val audioSrc: String,
        val clipBeginRaw: String,
        val clipEndRaw: String
    )

    private fun parseSmilFilePars(rawSmil: String): List<RawSmilPar> {
        val result = mutableListOf<RawSmilPar>()
        var searchIdx = 0
        val len = rawSmil.length
        while (searchIdx < len) {
            val parStart = rawSmil.indexOf("<par", searchIdx, ignoreCase = true)
            if (parStart < 0) break
            val parOpenEnd = rawSmil.indexOf('>', parStart + 4)
            if (parOpenEnd < 0) break
            val parClose = rawSmil.indexOf("</par>", parOpenEnd + 1, ignoreCase = true)
            val nextSearch = if (parClose > parOpenEnd) parClose + 6 else parOpenEnd + 1

            val parAttrs = rawSmil.substring(parStart + 4, parOpenEnd)
            val inner = if (parClose > parOpenEnd) rawSmil.substring(parOpenEnd + 1, parClose) else ""
            val parId = extractAttributeValue(parAttrs, "id")

            var textSrc = ""
            var textIdAttr = ""
            val textStart = inner.indexOf("<text", ignoreCase = true)
            if (textStart >= 0) {
                val textEnd = inner.indexOf('>', textStart + 5)
                if (textEnd > textStart) {
                    val tAttrs = inner.substring(textStart + 5, textEnd)
                    textSrc = extractAttributeValue(tAttrs, "src")
                    textIdAttr = extractAttributeValue(tAttrs, "id")
                }
            }

            var audioSrc = ""
            var clipBeginRaw = ""
            var clipEndRaw = ""
            var aIdx = inner.indexOf("<audio", ignoreCase = true)
            var isFirstAudio = true
            while (aIdx >= 0) {
                val aEnd = inner.indexOf('>', aIdx + 6)
                if (aEnd < 0) break
                val aAttrs = inner.substring(aIdx + 6, aEnd)
                if (isFirstAudio) {
                    audioSrc = extractAttributeValue(aAttrs, "src")
                    clipBeginRaw = extractAttributeValue(aAttrs, "clip-begin")
                        .ifBlank { extractAttributeValue(aAttrs, "clipBegin") }
                    isFirstAudio = false
                }
                val endVal = extractAttributeValue(aAttrs, "clip-end")
                    .ifBlank { extractAttributeValue(aAttrs, "clipEnd") }
                if (endVal.isNotBlank()) {
                    clipEndRaw = endVal
                }
                aIdx = inner.indexOf("<audio", aEnd + 1, ignoreCase = true)
            }

            result.add(
                RawSmilPar(
                    parId = parId,
                    textSrc = textSrc,
                    textIdAttr = textIdAttr,
                    audioSrc = audioSrc,
                    clipBeginRaw = clipBeginRaw,
                    clipEndRaw = clipEndRaw
                )
            )
            searchIdx = nextSearch
        }
        return result
    }

    /**
     * Full parsing pipeline for DAISY 2.02 (`ncc.html` + `.smil` + HTML/audio),
     * DAISY 3 (`.opf` + `.ncx` + `.smil` + DTBook XML/audio), and Audio-only DAISY books.
     */
    fun parseDaisyBook(
        sourceFileOrDir: File,
        fallbackTitle: String = "DAISY Book",
        fallbackAuthor: String = "Unknown Author"
    ): DaisyBookIndex {
        val cacheKey = "${sourceFileOrDir.absolutePath}:${sourceFileOrDir.lastModified()}:${sourceFileOrDir.length()}"
        getCachedDaisyBook(cacheKey)?.let { cached ->
            if (DEBUG_LOAD_PERF) Log.d(DAISY_PERF_TAG, "DAISY parseDaisyBook cache hit for $cacheKey (0 ms)")
            return cached
        }
        val totalStartNs = System.nanoTime()
        val stageMs = LinkedHashMap<String, Long>()
        val tZipStart = System.nanoTime()


        val isZipSource = sourceFileOrDir.isFile && sourceFileOrDir.name.lowercase(Locale.ROOT).endsWith(".zip")
        val parentDir = sourceFileOrDir.parentFile ?: File(sourceFileOrDir.absolutePath + "_daisy")
        val rootDir = if (sourceFileOrDir.isDirectory) {
            sourceFileOrDir
        } else {
            File(parentDir, "daisy_unpacked")
        }

        val virtualAudioFiles = if (isZipSource && sourceFileOrDir.exists()) {
            extractDaisyTextAndStructureFiles(sourceFileOrDir, rootDir)
        } else {
            emptyList()
        }

        val extractedFiles = rootDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }.toList()
        stageMs["zip_read"] = (System.nanoTime() - tZipStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(DAISY_PERF_TAG, "DAISY stage zip_read: ${stageMs["zip_read"]} ms")
        val allFiles = (extractedFiles + virtualAudioFiles).distinctBy { it.absolutePath }

        val filesByName = LinkedHashMap<String, File>(allFiles.size * 2)
        val filesByRelativePath = LinkedHashMap<String, File>(allFiles.size * 2)
        for (f in allFiles) {
            filesByName.putIfAbsent(f.name.lowercase(Locale.ROOT), f)
            val rel = f.relativeToOrSelf(rootDir).path.replace('\\', '/').lowercase(Locale.ROOT)
            filesByRelativePath[rel] = f
        }

        fun findFileByHref(baseDir: File, rawHref: String): File? {
            val cleanHref = try {
                java.net.URLDecoder.decode(rawHref.substringBefore("#").trim(), "UTF-8")
            } catch (_: Throwable) {
                rawHref.substringBefore("#").trim()
            }
            if (cleanHref.isBlank()) return null
            val direct = File(baseDir, cleanHref)
            if (direct.exists() && direct.isFile) return direct
            val rel = cleanHref.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
            filesByRelativePath[rel]?.let { return it }
            val nameOnly = cleanHref.substringAfterLast("/").lowercase(Locale.ROOT)
            return filesByName[nameOnly]
        }

        val nccFile = extractedFiles.firstOrNull {
            val n = it.name.lowercase(Locale.ROOT)
            n == "ncc.html" || n == "ncc.htm"
        }
        val ncxFile = extractedFiles.firstOrNull { it.extension.equals("ncx", ignoreCase = true) }
        val opfFile = extractedFiles.firstOrNull { it.extension.equals("opf", ignoreCase = true) }
        val smilFiles = extractedFiles.filter {
            val ext = it.extension.lowercase(Locale.ROOT)
            ext == "smil" || ext == "smi"
        }
        val audioFiles = allFiles.filter {
            it.extension.lowercase(Locale.ROOT) in audioExtensions
        }

        var bookTitle = fallbackTitle.ifBlank { "DAISY Talking Book" }
        var bookAuthor = fallbackAuthor.ifBlank { "Unknown Author" }
        var formatVersion = when {
            nccFile != null -> "DAISY 2.02"
            opfFile != null || ncxFile != null -> "DAISY 3"
            else -> "DAISY 2.02"
        }

        // 1. Parse metadata from NCC, OPF, or NCX
        var cachedNccText = ""
        if (nccFile != null) {
            try {
                cachedNccText = nccFile.readText(Charsets.UTF_8)
                val (metaTitle, metaAuthor) = parseMetaFromHtml(cachedNccText)
                if (metaTitle.isNotBlank()) bookTitle = metaTitle
                if (metaAuthor.isNotBlank()) bookAuthor = metaAuthor
            } catch (_: Throwable) {}
        }
        var cachedOpfText = ""
        if (opfFile != null) {
            try {
                cachedOpfText = opfFile.readText(Charsets.UTF_8)
                val (dcTitle, dcCreator) = parseMetaFromOpf(cachedOpfText)
                if (dcTitle.isNotBlank()) bookTitle = dcTitle
                if (dcCreator.isNotBlank()) bookAuthor = dcCreator
            } catch (_: Throwable) {}
        }

        // 2. Parse Navigation Control Center (ncc.html for DAISY 2.02 or .ncx for DAISY 3)
        val rawNavNodes = mutableListOf<RawNavNode>()
        if (nccFile != null && cachedNccText.isNotBlank()) {
            try {
                rawNavNodes.addAll(parseNccHtmlNodes(cachedNccText))
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        } else if (ncxFile != null) {
            try {
                val rawNcx = ncxFile.readText(Charsets.UTF_8)
                val (ncxTitle, ncxNodes) = parseNcxNodes(rawNcx)
                if (ncxTitle.isNotBlank() && bookTitle == fallbackTitle) {
                    bookTitle = ncxTitle
                }
                rawNavNodes.addAll(ncxNodes)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        // 3. Determine ordered SMIL files (from NCC/NCX/OPF spine or natural filename order)
        val orderedSmilFiles = LinkedHashSet<File>()
        val navBaseDir = nccFile?.parentFile ?: ncxFile?.parentFile ?: opfFile?.parentFile ?: rootDir
        for (node in rawNavNodes) {
            val f = findFileByHref(navBaseDir, node.href)
            if (f != null && (f.extension.equals("smil", ignoreCase = true) || f.extension.equals("smi", ignoreCase = true))) {
                orderedSmilFiles.add(f)
            }
        }
        if (opfFile != null && cachedOpfText.isNotBlank()) {
            try {
                val spineHrefs = parseOpfSmilSpineOrder(cachedOpfText)
                for (href in spineHrefs) {
                    val f = findFileByHref(opfFile.parentFile ?: rootDir, href)
                    if (f != null && (f.extension.equals("smil", ignoreCase = true) || f.extension.equals("smi", ignoreCase = true))) {
                        orderedSmilFiles.add(f)
                    }
                }
            } catch (_: Throwable) {}
        }
        for (smil in smilFiles.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            orderedSmilFiles.add(smil)
        }

        // 4. Parse all content HTML / XHTML / DTBook XML files so we can look up text snippets by `#id`
        val contentFiles = extractedFiles.filter { f ->
            val name = f.name.lowercase(Locale.ROOT)
            val ext = f.extension.lowercase(Locale.ROOT)
            name != "ncc.html" && name != "ncc.htm" &&
                (ext == "html" || ext == "htm" || ext == "xhtml" || (ext == "xml" && !name.contains("container")))
        }.sortedBy { it.name.lowercase(Locale.ROOT) }

        val tContentParseStart = System.nanoTime()
        val idToTextMap = HashMap<String, Pair<String, String>>(4096)
        val cachedContentTexts = LinkedHashMap<File, String>()
        for (cf in contentFiles) {
            try {
                val rawContent = cf.readText(Charsets.UTF_8)
                cachedContentTexts[cf] = rawContent
                indexHtmlElementIds(rawContent, cf.name, idToTextMap)
            } catch (_: Throwable) {}
        }
        if (nccFile != null && cachedNccText.isNotBlank()) {
            try {
                indexHtmlElementIds(cachedNccText, nccFile.name, idToTextMap)
            } catch (_: Throwable) {}
        }
        stageMs["daisy_indexing"] = (System.nanoTime() - tContentParseStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(DAISY_PERF_TAG, "DAISY stage daisy_indexing: ${stageMs["daisy_indexing"]} ms")

        // 5. Parse all SMIL files into ordered DaisySmilClips
        val tSmilStart = System.nanoTime()
        val smilClips = ArrayList<DaisySmilClip>(2048)
        var runningGlobalMs = 0L
        var clipCounter = 0

        val smilFileToChapterTitle = LinkedHashMap<String, String>()
        for (node in rawNavNodes) {
            if (!node.isPageTarget && node.title.isNotBlank()) {
                val smilName = node.href.substringBefore("#").substringAfterLast("/").lowercase(Locale.ROOT)
                if (smilName.isNotBlank()) {
                    smilFileToChapterTitle.putIfAbsent(smilName, node.title)
                }
            }
        }

        var currentChapterTitle = rawNavNodes.firstOrNull { !it.isPageTarget }?.title ?: bookTitle
        var currentPageEquivalent = ""

        val pageTargetBySmilAnchor = HashMap<String, String>()
        val navTitleBySmilAnchor = HashMap<String, String>()
        for (node in rawNavNodes) {
            val smilName = node.href.substringBefore("#").substringAfterLast("/").lowercase(Locale.ROOT)
            val smilAnchor = node.href.substringAfter("#", "").trim()
            if (smilName.isNotBlank()) {
                val key = if (smilAnchor.isNotBlank()) "$smilName#$smilAnchor" else smilName
                if (node.isPageTarget && node.pageLabel.isNotBlank()) {
                    pageTargetBySmilAnchor[key] = node.pageLabel
                } else if (!node.isPageTarget && node.title.isNotBlank()) {
                    navTitleBySmilAnchor[key] = node.title
                }
            }
        }

        val orderedSmilList = orderedSmilFiles.toList()
        val parsedSmilPairs = if (orderedSmilList.size > 1) {
            runBlocking(smilParseDispatcher) {
                orderedSmilList.map { smilFile ->
                    async {
                        val rawSmil = try { smilFile.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                        val pars = if (rawSmil.isNotEmpty()) parseSmilFilePars(rawSmil) else emptyList()
                        smilFile to pars
                    }
                }.awaitAll()
            }
        } else {
            orderedSmilList.map { smilFile ->
                val rawSmil = try { smilFile.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                val pars = if (rawSmil.isNotEmpty()) parseSmilFilePars(rawSmil) else emptyList()
                smilFile to pars
            }
        }

        for ((smilFile, pars) in parsedSmilPairs) {
            try {
                val smilName = smilFile.name
                val smilKeyLower = smilName.lowercase(Locale.ROOT)
                smilFileToChapterTitle[smilKeyLower]?.let { currentChapterTitle = it }

                for (par in pars) {
                    val parId = par.parId
                    val textSrc = par.textSrc
                    val textIdAttr = par.textIdAttr
                    val textFileRaw = textSrc.substringBefore("#").substringAfterLast("/").trim()
                    val textAnchor = textSrc.substringAfter("#", "").trim()

                    if (parId.isNotBlank()) {
                        navTitleBySmilAnchor["$smilKeyLower#$parId"]?.let { currentChapterTitle = it }
                        pageTargetBySmilAnchor["$smilKeyLower#$parId"]?.let { currentPageEquivalent = it }
                    }
                    if (textIdAttr.isNotBlank()) {
                        navTitleBySmilAnchor["$smilKeyLower#$textIdAttr"]?.let { currentChapterTitle = it }
                        pageTargetBySmilAnchor["$smilKeyLower#$textIdAttr"]?.let { currentPageEquivalent = it }
                    }
                    if (textAnchor.isNotBlank()) {
                        navTitleBySmilAnchor["$smilKeyLower#$textAnchor"]?.let { currentChapterTitle = it }
                        pageTargetBySmilAnchor["$smilKeyLower#$textAnchor"]?.let { currentPageEquivalent = it }
                    }

                    val resolvedAudioFile = if (par.audioSrc.isNotBlank()) {
                        findFileByHref(smilFile.parentFile ?: rootDir, par.audioSrc)?.absolutePath ?: ""
                    } else ""

                    val clipBeginMs = parseSmilClockMs(par.clipBeginRaw.ifBlank { "0s" })
                    val rawClipEndMs = parseSmilClockMs(par.clipEndRaw.ifBlank { "0s" })
                    val clipEndMs = if (rawClipEndMs > clipBeginMs) rawClipEndMs else (clipBeginMs + 4000L)
                    val duration = (clipEndMs - clipBeginMs).coerceAtLeast(500L)

                    val lookupKeySpecific = "${textFileRaw.lowercase(Locale.ROOT)}#$textAnchor"
                    val lookupKeyGeneric = "#$textAnchor"
                    val resolvedTextPair = idToTextMap[lookupKeySpecific] ?: idToTextMap[lookupKeyGeneric]
                    val resolvedFileKey = resolvedTextPair?.first ?: textFileRaw.ifBlank { smilName }
                    val resolvedSnippet = resolvedTextPair?.second
                        ?: navTitleBySmilAnchor["$smilKeyLower#$parId"]
                        ?: currentChapterTitle

                    val clipId = parId.ifBlank { textIdAttr.ifBlank { "clip_${clipCounter + 1}" } }
                    smilClips.add(
                        DaisySmilClip(
                            id = clipId,
                            index = clipCounter,
                            smilFile = smilName,
                            parId = parId.ifBlank { textIdAttr },
                            textFileKey = resolvedFileKey,
                            textAnchorId = textAnchor.ifBlank { parId },
                            textSnippet = resolvedSnippet,
                            audioFile = resolvedAudioFile,
                            clipBeginMs = clipBeginMs,
                            clipEndMs = clipEndMs,
                            globalStartMs = runningGlobalMs,
                            globalEndMs = runningGlobalMs + duration,
                            chapterTitle = currentChapterTitle,
                            pageNumber = 1,
                            pageEquivalent = currentPageEquivalent
                        )
                    )
                    runningGlobalMs += duration
                    clipCounter++
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        stageMs["smil_parse"] = (System.nanoTime() - tSmilStart) / 1_000_000L
        if (DEBUG_LOAD_PERF) Log.d(DAISY_PERF_TAG, "DAISY stage smil_parse: ${stageMs["smil_parse"]} ms (${smilClips.size} clips)")


        // 6. Build O(1) lookup tables from SMIL clips so `nccNavItems` and `pageTargets` link in O(1) time
        val clipBySmilAndAnchor = HashMap<String, DaisySmilClip>(smilClips.size * 2)
        val clipBySmilFirst = HashMap<String, DaisySmilClip>()
        val clipByAnchorOnly = HashMap<String, DaisySmilClip>(smilClips.size)
        for (clip in smilClips) {
            val sLower = clip.smilFile.lowercase(Locale.ROOT)
            clipBySmilFirst.putIfAbsent(sLower, clip)
            if (clip.parId.isNotBlank()) {
                clipBySmilAndAnchor.putIfAbsent("$sLower#${clip.parId.lowercase(Locale.ROOT)}", clip)
                clipByAnchorOnly.putIfAbsent(clip.parId.lowercase(Locale.ROOT), clip)
            }
            if (clip.id.isNotBlank()) {
                clipBySmilAndAnchor.putIfAbsent("$sLower#${clip.id.lowercase(Locale.ROOT)}", clip)
            }
            if (clip.textAnchorId.isNotBlank()) {
                clipBySmilAndAnchor.putIfAbsent("$sLower#${clip.textAnchorId.lowercase(Locale.ROOT)}", clip)
                clipByAnchorOnly.putIfAbsent(clip.textAnchorId.lowercase(Locale.ROOT), clip)
            }
        }

        val navItems = mutableListOf<DaisyNavItem>()
        val pageTargets = mutableListOf<DaisyNavItem>()

        for ((idx, node) in rawNavNodes.withIndex()) {
            val hrefFile = node.href.substringBefore("#").substringAfterLast("/").trim()
            val hrefAnchor = node.href.substringAfter("#", "").trim()
            val sLower = hrefFile.lowercase(Locale.ROOT)
            val aLower = hrefAnchor.lowercase(Locale.ROOT)

            val matchedClip = when {
                sLower.isNotEmpty() && aLower.isNotEmpty() ->
                    clipBySmilAndAnchor["$sLower#$aLower"] ?: clipByAnchorOnly[aLower] ?: clipBySmilFirst[sLower]
                aLower.isNotEmpty() -> clipByAnchorOnly[aLower]
                sLower.isNotEmpty() -> clipBySmilFirst[sLower]
                else -> null
            }

            val item = DaisyNavItem(
                id = node.id.ifBlank { "nav_$idx" },
                title = node.title,
                level = node.level,
                smilHref = node.href,
                smilAnchor = hrefAnchor,
                textFileKey = matchedClip?.textFileKey ?: hrefFile,
                textAnchorId = matchedClip?.textAnchorId ?: hrefAnchor,
                audioFile = matchedClip?.audioFile ?: "",
                clipBeginMs = matchedClip?.clipBeginMs ?: 0L,
                clipEndMs = matchedClip?.clipEndMs ?: 0L,
                pageNumber = 1,
                isPageNumberTarget = node.isPageTarget,
                pageLabel = node.pageLabel
            )
            if (node.isPageTarget) {
                pageTargets.add(item)
            } else {
                navItems.add(item)
            }
        }

        if (navItems.isEmpty()) {
            val groupedBySmil = smilClips.groupBy { it.smilFile }
            var secIdx = 1
            for ((smilName, clips) in groupedBySmil) {
                val first = clips.first()
                navItems.add(
                    DaisyNavItem(
                        id = "smil_sec_$secIdx",
                        title = first.chapterTitle.ifBlank { "Section $secIdx ($smilName)" },
                        level = 1,
                        smilHref = smilName,
                        smilAnchor = first.parId,
                        textFileKey = first.textFileKey,
                        textAnchorId = first.textAnchorId,
                        audioFile = first.audioFile,
                        clipBeginMs = first.clipBeginMs,
                        clipEndMs = clips.last().clipEndMs,
                        pageNumber = 1
                    )
                )
                secIdx++
            }
        }

        // 7. Build EpubBook representation (Full-Text HTML/DTBook XML if available, or synthesized Audio-Only chapters)
        val hasFullTextContent = contentFiles.isNotEmpty()
        if (!hasFullTextContent && audioFiles.isNotEmpty()) {
            formatVersion = "DAISY Audio-Only"
        }

        val epubChapters = mutableListOf<EpubChapter>()
        val embeddedImages = LinkedHashMap<String, Bitmap>()
        var coverBitmap: Bitmap? = null

        for (imgFile in extractedFiles.filter { it.extension.lowercase(Locale.ROOT) in imageExtensions }) {
            try {
                val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                if (bmp != null) {
                    embeddedImages[imgFile.name] = bmp
                    if (coverBitmap == null && (imgFile.name.contains("cover", ignoreCase = true) || imgFile.name.contains("front", ignoreCase = true))) {
                        coverBitmap = bmp
                    }
                }
            } catch (_: Throwable) {}
        }
        if (coverBitmap == null) {
            coverBitmap = embeddedImages.values.firstOrNull()
                ?: try { EpubParser.generateBookCoverBitmap(bookTitle, bookAuthor) } catch (_: Throwable) { null }
        }

        if (hasFullTextContent) {
            for ((cIdx, cf) in contentFiles.withIndex()) {
                try {
                    val rawText = cachedContentTexts[cf] ?: cf.readText(Charsets.UTF_8)
                    val isXml = cf.extension.equals("xml", ignoreCase = true)
                    if (isXml) {
                        val xmlClusters = buildChaptersFromDtbookXml(rawText, cf.name, bookTitle)
                        epubChapters.addAll(xmlClusters)
                    } else {
                        val fastChapters = parseDaisyHtmlChaptersFast(
                            rawHtml = rawText,
                            fileKey = cf.name,
                            fallbackTitle = navItems.getOrNull(cIdx)?.title ?: bookTitle
                        )
                        epubChapters.addAll(fastChapters)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

        if (epubChapters.isEmpty()) {
            val synthesizedChapters = synthesizeChaptersFromDaisyTimeline(
                bookTitle = bookTitle,
                navItems = navItems,
                smilClips = smilClips,
                audioFiles = audioFiles
            )
            epubChapters.addAll(synthesizedChapters)
        }

        // Fast O(1) enrichment of semantic blocks using anchor HashMap
        val pageEqByAnchor = HashMap<String, String>()
        for (clip in smilClips) {
            if (clip.textAnchorId.isNotBlank() && clip.pageEquivalent.isNotBlank()) {
                pageEqByAnchor.putIfAbsent(clip.textAnchorId.lowercase(Locale.ROOT), clip.pageEquivalent)
            }
        }

        val enrichedChapters = epubChapters.map { ch ->
            val updatedBlocks = ch.semanticBlocks.map { sb ->
                if (sb.pageEquivalent.isNotBlank() || sb.anchorIds.isEmpty()) {
                    sb
                } else {
                    val resolvedEq = sb.anchorIds.firstNotNullOfOrNull { pageEqByAnchor[it.lowercase(Locale.ROOT)] }.orEmpty()
                    if (resolvedEq.isNotBlank()) sb.copy(pageEquivalent = resolvedEq) else sb
                }
            }
            ch.copy(semanticBlocks = updatedBlocks)
        }

        val epubBook = EpubBook(
            title = bookTitle,
            author = bookAuthor,
            description = "DAISY Talking Book ($formatVersion)",
            coverBitmap = coverBitmap,
            chapters = enrichedChapters,
            hasPageListMetadata = pageTargets.isNotEmpty() || enrichedChapters.any { ch -> ch.paragraphPageEquivalents.any { it.isNotBlank() } },
            embeddedImages = embeddedImages,
            sourceArchivePath = if (isZipSource) sourceFileOrDir.absolutePath else "",
            audioEntryNames = audioFiles.map { it.name }.toSet()
        )

        val hasAudio = audioFiles.isNotEmpty() && smilClips.any { it.audioFile.isNotBlank() }

        return DaisyBookIndex(
            formatVersion = formatVersion,
            title = bookTitle,
            author = bookAuthor,
            totalDurationMs = runningGlobalMs,
            extractedDir = rootDir.absolutePath,
            sourceArchivePath = if (isZipSource) sourceFileOrDir.absolutePath else "",
            nccNavItems = navItems,
            pageTargets = pageTargets,
            smilClips = smilClips,
            hasRecordedAudio = hasAudio,
            epubBook = epubBook
        ).also { parsedIndex ->
            putCachedDaisyBook(cacheKey, parsedIndex)
            if (DEBUG_LOAD_PERF) {
                val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                val top3 = stageMs.entries.sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { "${it.key}=${it.value}ms" }
                Log.d(DAISY_PERF_TAG, "DAISY parseDaisyBook summary: total=${totalMs}ms | slowest: $top3")
            }
        }
    }

    private fun buildChaptersFromDtbookXml(
        rawXml: String,
        fileKey: String,
        fallbackTitle: String
    ): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        val elemOpenRegex = Regex("""(?is)<(h[1-6]|hd|p|li|blockquote|sent|pagenum)\b([^>]*)>""")
        val idAttrRegex = Regex("""(?i)\bid\s*=\s*(?:"([^"]+)"|'([^']+)')""")

        var chTitle = fallbackTitle
        var paragraphs = mutableListOf<String>()
        var pageEqs = mutableListOf<String>()
        var semBlocks = mutableListOf<EpubSemanticBlock>()
        var currentPageEq = ""
        var paraCounter = 1
        var chIdx = 1

        fun flushChapter() {
            if (paragraphs.isNotEmpty()) {
                chapters.add(
                    EpubChapter(
                        id = "${fileKey}_ch_${chIdx++}",
                        title = chTitle,
                        content = paragraphs.joinToString("\n\n"),
                        paragraphs = paragraphs,
                        paragraphPageEquivalents = pageEqs,
                        fileKey = fileKey,
                        semanticBlocks = semBlocks
                    )
                )
                paragraphs = mutableListOf()
                pageEqs = mutableListOf()
                semBlocks = mutableListOf()
                paraCounter = 1
            }
        }

        for (m in elemOpenRegex.findAll(rawXml)) {
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            val attrs = m.groupValues[2]
            val startIdx = m.range.last + 1
            if (startIdx >= rawXml.length) continue
            val closeIdx = rawXml.indexOf("</$tag", startIdx, ignoreCase = true)
                .takeIf { it >= startIdx }
                ?: (startIdx + 2000).coerceAtMost(rawXml.length)
            val inner = rawXml.substring(startIdx, closeIdx.coerceAtMost(startIdx + 6000))
            val text = stripHtmlTags(inner)
            if (text.isBlank()) continue

            if (tag == "pagenum") {
                currentPageEq = text
                continue
            }
            val isHeading = tag == "hd" || tag.startsWith("h")
            if (isHeading && (tag == "h1" || tag == "h2" || tag == "hd")) {
                if (paragraphs.isNotEmpty()) {
                    flushChapter()
                }
                chTitle = text
            }

            val ids = ArrayList<String>(4)
            val topId = extractAttributeValue(attrs, "id")
            if (topId.isNotBlank()) ids.add(topId)
            for (idM in idAttrRegex.findAll(inner)) {
                val cid = idM.groupValues[1].ifEmpty { idM.groupValues[2] }.trim()
                if (cid.isNotBlank() && !ids.contains(cid)) ids.add(cid)
            }

            val formattedText = if (isHeading) "## $text" else text
            paragraphs.add(formattedText)
            pageEqs.add(currentPageEq)
            semBlocks.add(
                EpubSemanticBlock(
                    type = if (isHeading) EpubSemanticBlockType.HEADING else EpubSemanticBlockType.PARAGRAPH,
                    text = text,
                    headingLevel = if (isHeading) (tag.drop(1).toIntOrNull() ?: 2) else 0,
                    anchorIds = ids,
                    paragraphNumberInChapter = if (isHeading) null else paraCounter++,
                    pageEquivalent = currentPageEq,
                    fileKey = fileKey
                )
            )
        }

        flushChapter()
        return chapters
    }

    private fun synthesizeChaptersFromDaisyTimeline(
        bookTitle: String,
        navItems: List<DaisyNavItem>,
        smilClips: List<DaisySmilClip>,
        audioFiles: List<File>
    ): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        if (smilClips.isNotEmpty()) {
            val grouped = smilClips.groupBy { it.smilFile.ifBlank { it.chapterTitle } }
            var chCounter = 1
            for ((_, clips) in grouped) {
                val first = clips.first()
                val chTitle = first.chapterTitle.ifBlank { bookTitle }
                val paragraphs = mutableListOf<String>()
                val pageEqs = mutableListOf<String>()
                val semBlocks = mutableListOf<EpubSemanticBlock>()
                var paraCounter = 1

                for (clip in clips) {
                    val timeBadge = "[${formatTimeMs(clip.clipBeginMs)} – ${formatTimeMs(clip.clipEndMs)}]"
                    val snippet = clip.textSnippet.trim()
                    val displayLine = if (snippet.isNotBlank() && !snippet.equals(chTitle, ignoreCase = true)) {
                        "$snippet $timeBadge"
                    } else {
                        "Audio Segment ${clip.index + 1} • $chTitle $timeBadge"
                    }
                    val anchors = listOf(clip.textAnchorId, clip.parId, clip.id).filter { it.isNotBlank() }.distinct()
                    paragraphs.add(displayLine)
                    pageEqs.add(clip.pageEquivalent)
                    semBlocks.add(
                        EpubSemanticBlock(
                            type = EpubSemanticBlockType.PARAGRAPH,
                            text = displayLine,
                            anchorIds = anchors,
                            paragraphNumberInChapter = paraCounter++,
                            pageEquivalent = clip.pageEquivalent,
                            fileKey = clip.textFileKey.ifBlank { clip.smilFile }
                        )
                    )
                }
                val fk = first.textFileKey.ifBlank { first.smilFile }
                chapters.add(
                    EpubChapter(
                        id = "daisy_synth_${chCounter++}",
                        title = chTitle,
                        content = paragraphs.joinToString("\n\n"),
                        paragraphs = paragraphs,
                        paragraphPageEquivalents = pageEqs,
                        fileKey = fk,
                        semanticBlocks = semBlocks
                    )
                )
            }
        } else if (navItems.isNotEmpty()) {
            val paragraphs = navItems.map { "${it.title} (${it.formatTimecode()})" }
            val semBlocks = navItems.mapIndexed { i, nav ->
                EpubSemanticBlock(
                    type = EpubSemanticBlockType.PARAGRAPH,
                    text = "${nav.title} (${nav.formatTimecode()})",
                    anchorIds = listOf(nav.id, nav.textAnchorId, nav.smilAnchor).filter { it.isNotBlank() },
                    paragraphNumberInChapter = i + 1,
                    pageEquivalent = nav.pageLabel,
                    fileKey = nav.textFileKey
                )
            }
            chapters.add(
                EpubChapter(
                    id = "daisy_ncc_1",
                    title = bookTitle,
                    content = paragraphs.joinToString("\n\n"),
                    paragraphs = paragraphs,
                    paragraphPageEquivalents = navItems.map { it.pageLabel },
                    fileKey = "ncc.html",
                    semanticBlocks = semBlocks
                )
            )
        } else {
            val fallbackParas = if (audioFiles.isNotEmpty()) {
                audioFiles.mapIndexed { idx, file -> "Audio Track ${idx + 1}: ${file.name}" }
            } else {
                listOf("DAISY Book: $bookTitle")
            }
            chapters.add(
                EpubChapter(
                    id = "daisy_fallback_1",
                    title = bookTitle,
                    content = fallbackParas.joinToString("\n\n"),
                    paragraphs = fallbackParas,
                    fileKey = "ncc.html"
                )
            )
        }
        return chapters
    }

    /**
     * Parses DAISY 2.02 / DAISY 3 SMIL clock values:
     * - `npt=12.345s`
     * - `npt=00:01:23.45`
     * - `01:23.45`
     * - `4500ms`
     * - `15.2s`
     */
    fun parseSmilClockMs(rawClock: String): Long {
        val cleaned = rawClock.trim()
            .removePrefix("npt=")
            .removePrefix("smpte=")
            .trim()
        if (cleaned.isBlank()) return 0L

        return try {
            when {
                cleaned.endsWith("ms", ignoreCase = true) -> {
                    cleaned.dropLast(2).trim().toDoubleOrNull()?.toLong() ?: 0L
                }
                cleaned.endsWith("s", ignoreCase = true) && !cleaned.contains(":") -> {
                    val secs = cleaned.dropLast(1).trim().toDoubleOrNull() ?: 0.0
                    (secs * 1000.0).toLong()
                }
                cleaned.contains(":") -> {
                    val parts = cleaned.removeSuffix("s").split(":")
                    when (parts.size) {
                        3 -> {
                            val h = parts[0].trim().toLongOrNull() ?: 0L
                            val m = parts[1].trim().toLongOrNull() ?: 0L
                            val s = parts[2].trim().toDoubleOrNull() ?: 0.0
                            ((h * 3600 + m * 60) * 1000L) + (s * 1000.0).toLong()
                        }
                        2 -> {
                            val m = parts[0].trim().toLongOrNull() ?: 0L
                            val s = parts[1].trim().toDoubleOrNull() ?: 0.0
                            (m * 60 * 1000L) + (s * 1000.0).toLong()
                        }
                        else -> 0L
                    }
                }
                else -> {
                    val secs = cleaned.toDoubleOrNull() ?: 0.0
                    (secs * 1000.0).toLong()
                }
            }
        } catch (_: Throwable) {
            0L
        }
    }

    fun formatTimeMs(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
