package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.EpubParser
import com.example.reader.PdfChapterHelper
import com.example.reader.PdfDocumentRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object StorageManager {

    fun sanitizeName(name: String): String {
        return name.replace(Regex("[/\\\\?%*:|\"<>]"), "_").trim()
            .ifBlank { "Untitled" }
    }

    /**
     * Resolves the primary root directory according to user settings.
     * Hierarchy:
     * Readmark/
     * ├── Articles/
     * │   └── [Article Title]/
     * │       ├── article.pdf or article.txt
     * │       └── highlight.md
     * └── Books/
     *     └── [Book Title]/
     *         ├── book.epub or book.pdf
     *         └── highlight.md
     */
    fun getRootDirectory(
        context: Context,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File {
        if (customPath.isNotBlank()) {
            val custom = File(customPath)
            if (custom.exists() || custom.mkdirs()) {
                File(custom, "Articles").mkdirs()
                File(custom, "Books").mkdirs()
                return custom
            }
        }

        // Primary persistent app storage: guaranteed writable and persistent across app restarts
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val readmarkDir = File(base, "Readmark")
        if (!readmarkDir.exists()) {
            readmarkDir.mkdirs()
        }
        File(readmarkDir, "Articles").mkdirs()
        File(readmarkDir, "Books").mkdirs()
        return readmarkDir
    }

    fun getBooksDirectory(context: Context, customPath: String = "", storageType: String = "DOCUMENTS"): File {
        val root = getRootDirectory(context, customPath, storageType)
        return File(root, "Books").apply { mkdirs() }
    }

    fun getArticlesDirectory(context: Context, customPath: String = "", storageType: String = "DOCUMENTS"): File {
        val root = getRootDirectory(context, customPath, storageType)
        return File(root, "Articles").apply { mkdirs() }
    }

    /**
     * Dedicated folder for an individual book or article:
     * Readmark/Books/[Title]/ or Readmark/Articles/[Title]/
     */
    fun getDocumentFolder(
        context: Context,
        category: String,
        documentTitle: String,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File {
        val parent = if (category.equals("ARTICLE", ignoreCase = true) || category.equals("Articles", ignoreCase = true)) {
            getArticlesDirectory(context, customPath, storageType)
        } else {
            getBooksDirectory(context, customPath, storageType)
        }
        val cleanFolder = sanitizeName(documentTitle)
        return File(parent, cleanFolder).apply { mkdirs() }
    }

    /**
     * Saves an imported document file into its dedicated book/article folder.
     * Supports both `content://` and `file://` URIs, with fallback descriptor opening
     * for external file managers ("Open with" / Share intents).
     */
    fun saveDocumentFile(
        context: Context,
        uri: Uri,
        documentTitle: String,
        category: String,
        originalFileName: String,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File? {
        return try {
            val folder = getDocumentFolder(context, category, documentTitle, customPath, storageType)
            val cleanFileName = sanitizeName(originalFileName).ifBlank { "document.epub" }
            val targetFile = File(folder, cleanFileName)

            // If URI points directly to the targetFile already, return it immediately
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                val sourceFile = File(uri.path!!)
                if (sourceFile.exists() && sourceFile.length() > 0L) {
                    if (sourceFile.canonicalPath != targetFile.canonicalPath) {
                        sourceFile.copyTo(targetFile, overwrite = true)
                    }
                    if (targetFile.exists() && targetFile.length() > 0L) {
                        return targetFile
                    }
                }
            }

            var copiedBytes = 0L
            val inputStream: InputStream? = try {
                context.contentResolver.openInputStream(uri)
            } catch (_: Throwable) {
                null
            } ?: try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
            } catch (_: Throwable) {
                null
            } ?: try {
                if (!uri.path.isNullOrBlank()) {
                    val f = File(uri.path!!)
                    if (f.exists()) f.inputStream() else null
                } else null
            } catch (_: Throwable) {
                null
            }

            inputStream?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    copiedBytes = input.copyTo(output, bufferSize = 256 * 1024)
                    output.flush()
                }
            }

            if (targetFile.exists() && (targetFile.length() > 0L || copiedBytes > 0L)) {
                targetFile
            } else {
                null
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates and saves a text article (.txt) inside Readmark/Articles/[Title]/
     */
    fun saveTextArticle(
        context: Context,
        documentTitle: String,
        content: String,
        category: String = "ARTICLE",
        fileName: String = "article.txt",
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File? {
        return try {
            val folder = getDocumentFolder(context, category, documentTitle, customPath, storageType)
            val file = File(folder, fileName)
            file.writeText(content, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves a highlight note inside the book's/article's folder.
     */
    fun saveHighlightNote(
        context: Context,
        category: String,
        documentTitle: String,
        noteTitle: String,
        markdownContent: String,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File? {
        return try {
            val folder = getDocumentFolder(context, category, documentTitle, customPath, storageType)
            val cleanName = sanitizeName(noteTitle)
            val filename = if (cleanName.endsWith(".md", ignoreCase = true)) cleanName else "$cleanName.md"
            val file = File(folder, filename)
            file.writeText(markdownContent, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves the MOC (Map of Content) file inside the book's/article's folder.
     */
    fun saveMoc(
        context: Context,
        category: String,
        documentTitle: String,
        mocTitle: String,
        markdownContent: String,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File? {
        return try {
            val folder = getDocumentFolder(context, category, documentTitle, customPath, storageType)
            val cleanName = sanitizeName(mocTitle)
            val filename = if (cleanName.endsWith(".md", ignoreCase = true)) cleanName else "$cleanName.md"
            val file = File(folder, filename)
            // Keeps a "# My Notes" section written by hand at the end of the overview.
            com.example.reader.FolderSync.writeKeepingUserSection(file, markdownContent)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves or updates the highlight.md file inside Readmark/[Category]/[DocumentTitle]/highlight.md
     */
    fun saveHighlightMarkdown(
        context: Context,
        category: String,
        documentTitle: String,
        highlights: List<HighlightEntity>,
        author: String = "Unknown Author",
        customPath: String = "",
        storageType: String = "DOCUMENTS",
        tags: String = ""
    ): File? {
        return try {
            val folder = getDocumentFolder(context, category, documentTitle, customPath, storageType)
            val file = File(folder, "highlight.md")

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateStr = dateFormat.format(Date())
            val parsedTags = com.example.reader.NoteSuggestionEngine.parseTagsList(tags)

            val sb = StringBuilder()
            sb.appendLine("---")
            sb.appendLine("title: \"${documentTitle}\"")
            sb.appendLine("author: \"${author}\"")
            sb.appendLine("category: \"${category}\"")
            sb.appendLine("date: ${dateStr}")
            sb.appendLine("type: literature-notes")
            if (parsedTags.isNotEmpty()) {
                sb.appendLine("tags:")
                parsedTags.forEach { t -> sb.appendLine("  - $t") }
            }
            sb.appendLine("total_highlights: ${highlights.size}")
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("# Highlights: ${documentTitle}")
            sb.appendLine("*By ${author}*")
            sb.appendLine()

            if (highlights.isEmpty()) {
                sb.appendLine("_No highlights added yet._")
            } else {
                for (h in highlights) {
                    val printed = h.printedPageLabel.trim()
                    val pageEq = h.pageEquivalent.trim()
                    val paraCite = h.paragraphCitation.trim()
                    val pageSummary = when {
                        printed.isNotEmpty() -> "PDF page number ${h.pageNumber}, printed page number $printed"
                        pageEq.isNotEmpty() -> "Page ${h.pageNumber} (Page Equivalent: $pageEq)"
                        else -> "Page ${h.pageNumber}"
                    }
                    sb.appendLine("---")
                    sb.appendLine("### ${h.title.ifBlank { "Note on $pageSummary" }}")
                    val metaParts = mutableListOf<String>()
                    if (paraCite.isNotEmpty()) {
                        metaParts.add("**Chapter + Paragraph**: $paraCite")
                    } else if (h.chapterTitle.isNotBlank()) {
                        metaParts.add("**Chapter**: ${h.chapterTitle}")
                    }
                    if (pageEq.isNotEmpty()) {
                        metaParts.add("**Page Equivalent**: $pageEq")
                    }
                    if (h.epubCfi.isNotBlank()) {
                        metaParts.add("**EPUB CFI**: `${h.epubCfi.trim()}`")
                    }
                    metaParts.add("**Page**: $pageSummary")
                    sb.appendLine(metaParts.joinToString(" | "))
                    sb.appendLine()
                    sb.appendLine("> ${h.highlightedText.trim()}")
                    sb.appendLine()
                    if (h.userNotes.isNotBlank()) {
                        sb.appendLine("**PKM Reflections**:")
                        sb.appendLine(h.userNotes.trim())
                        sb.appendLine()
                    }
                }
            }

            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves cover image inside the document's folder as `Title of Book - front cover.WebP` (and `cover.jpg` for legacy lookup).
     */
    fun saveCoverImage(
        context: Context,
        category: String,
        documentTitle: String,
        bitmap: Bitmap,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ): File? {
        return try {
            val folder = getDocumentFolder(context, category, documentTitle, customPath, storageType)
            val cleanTitle = sanitizeName(documentTitle).ifBlank { "Book" }
            val webpCoverFile = File(folder, "$cleanTitle - front cover.WebP")
            FileOutputStream(webpCoverFile).use { out ->
                @Suppress("DEPRECATION")
                val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 90, out)
            }
            val coverFile = File(folder, "cover.jpg")
            FileOutputStream(coverFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            webpCoverFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves cover bitmap with a given key/identifier for cache access.
     */
    fun saveCoverBitmap(
        context: Context,
        filenameOrKey: String,
        bitmap: Bitmap,
        customPath: String = ""
    ): File? {
        return try {
            val coversDir = File(getRootDirectory(context, customPath), ".covers").apply { mkdirs() }
            val cleanKey = sanitizeName(filenameOrKey)
            val file = File(coversDir, "$cleanKey.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decodes only the image bounds of a cover file (or bitmap) to compute its width/height aspect ratio
     * without allocating pixel memory. Returns 0f if unknown or invalid.
     */
    fun decodeCoverAspectRatio(coverPath: String?, fallbackBitmap: Bitmap? = null): Float {
        if (!coverPath.isNullOrBlank()) {
            try {
                val f = File(coverPath)
                if (f.exists() && f.length() > 0L) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        return (opts.outWidth.toFloat() / opts.outHeight.toFloat()).coerceIn(0.2f, 5.0f)
                    }
                }
            } catch (_: Throwable) {}
        }
        if (fallbackBitmap != null && fallbackBitmap.width > 0 && fallbackBitmap.height > 0) {
            return (fallbackBitmap.width.toFloat() / fallbackBitmap.height.toFloat()).coerceIn(0.2f, 5.0f)
        }
        return 0f
    }

    data class StorageStats(
        val rootPath: String,
        val booksCount: Int = 0,
        val articlesCount: Int = 0,
        val documentsCount: Int = 0,
        val notesCount: Int = 0,
        val vaultsCount: Int = 0,
        val totalFilesCount: Int = 0
    )

    fun getStorageStats(context: Context, customPath: String = "", storageType: String = "DOCUMENTS"): StorageStats {
        val root = getRootDirectory(context, customPath, storageType)
        val booksDir = getBooksDirectory(context, customPath, storageType)
        val articlesDir = getArticlesDirectory(context, customPath, storageType)

        val books = booksDir.listFiles()?.filter { it.isDirectory }?.size ?: 0
        val articles = articlesDir.listFiles()?.filter { it.isDirectory }?.size ?: 0

        var notes = 0
        var vaults = 0
        var total = 0
        if (root.exists()) {
            root.walkTopDown().forEach { f ->
                if (f.isFile) {
                    total++
                    if (f.extension.equals("md", ignoreCase = true)) notes++
                    if (f.extension.equals("zip", ignoreCase = true)) vaults++
                }
            }
        }

        return StorageStats(
            rootPath = root.absolutePath,
            booksCount = books,
            articlesCount = articles,
            documentsCount = books + articles,
            notesCount = notes,
            vaultsCount = vaults,
            totalFilesCount = total
        )
    }

    private const val DELETED_REGISTRY_FILENAME = ".deleted_documents.txt"

    private fun getDeletedRegistryFile(context: Context, customPath: String = "", storageType: String = "DOCUMENTS"): File {
        val root = getRootDirectory(context, customPath, storageType)
        return File(root, DELETED_REGISTRY_FILENAME)
    }

    fun getDeletedKeys(context: Context, customPath: String = "", storageType: String = "DOCUMENTS"): Set<String> {
        return try {
            val file = getDeletedRegistryFile(context, customPath, storageType)
            if (!file.exists()) emptySet()
            else file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun markDocumentDeleted(
        context: Context,
        document: DocumentEntity,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ) {
        try {
            val file = getDeletedRegistryFile(context, customPath, storageType)
            val keys = getDeletedKeys(context, customPath, storageType).toMutableSet()
            val bookFile = File(document.filePath)
            keys.add("path:${bookFile.absolutePath.lowercase(Locale.ROOT)}")
            runCatching { keys.add("canon:${bookFile.canonicalPath.lowercase(Locale.ROOT)}") }
            keys.add("title:${sanitizeName(document.title).lowercase(Locale.ROOT)}")
            bookFile.parentFile?.name?.let { parentName ->
                keys.add("folder:${sanitizeName(parentName).lowercase(Locale.ROOT)}")
            }
            computeFileContentSignature(bookFile)?.let { sig ->
                keys.add("sig:$sig")
            }
            file.writeText(keys.joinToString("\n"), Charsets.UTF_8)
        } catch (_: Throwable) {}
    }

    fun unmarkDocumentDeleted(
        context: Context,
        title: String,
        filePath: String,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ) {
        try {
            val file = getDeletedRegistryFile(context, customPath, storageType)
            if (!file.exists()) return
            val keys = getDeletedKeys(context, customPath, storageType).toMutableSet()
            val bookFile = File(filePath)
            keys.remove("path:${bookFile.absolutePath.lowercase(Locale.ROOT)}")
            runCatching { keys.remove("canon:${bookFile.canonicalPath.lowercase(Locale.ROOT)}") }
            keys.remove("title:${sanitizeName(title).lowercase(Locale.ROOT)}")
            bookFile.parentFile?.name?.let { parentName ->
                keys.remove("folder:${sanitizeName(parentName).lowercase(Locale.ROOT)}")
            }
            computeFileContentSignature(bookFile)?.let { sig ->
                keys.remove("sig:$sig")
            }
            file.writeText(keys.joinToString("\n"), Charsets.UTF_8)
        } catch (_: Throwable) {}
    }

    /**
     * Deletes a document's files/folder from disk when no other remaining DB document references them,
     * and records the deletion so scanAndSyncLibrary never resurrects it.
     */
    fun deleteDocumentFilesFromStorage(
        context: Context,
        deletedDoc: DocumentEntity,
        remainingDocs: List<DocumentEntity>,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ) {
        markDocumentDeleted(context, deletedDoc, customPath, storageType)
        try {
            val root = getRootDirectory(context, customPath, storageType)
            val rootCanon = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            val remainingPaths = remainingDocs.mapNotNull {
                runCatching { File(it.filePath).canonicalPath }.getOrNull() ?: it.filePath
            }.toSet()
            val remainingFolders = remainingDocs.mapNotNull { doc ->
                runCatching { File(doc.filePath).parentFile?.canonicalPath }.getOrNull()
            }.toSet()
            val remainingTitleFolders = remainingDocs.map { doc ->
                runCatching {
                    getDocumentFolder(context, doc.category, doc.title, customPath, storageType).canonicalPath
                }.getOrDefault("")
            }.filter { it.isNotEmpty() }.toSet()

            val targetFile = File(deletedDoc.filePath)
            val targetFileCanon = runCatching { targetFile.canonicalPath }.getOrDefault(targetFile.absolutePath)
            if (targetFile.exists() && targetFileCanon !in remainingPaths) {
                targetFile.delete()
            }

            val parentFolder = targetFile.parentFile
            val parentCanon = runCatching { parentFolder?.canonicalPath }.getOrNull()
            if (parentFolder != null && parentCanon != null &&
                parentCanon.startsWith(rootCanon) &&
                parentCanon != rootCanon &&
                parentFolder.name != "Books" &&
                parentFolder.name != "Articles" &&
                parentCanon !in remainingFolders &&
                parentCanon !in remainingTitleFolders
            ) {
                parentFolder.deleteRecursively()
            }

            val titleFolder = getDocumentFolder(context, deletedDoc.category, deletedDoc.title, customPath, storageType)
            val titleFolderCanon = runCatching { titleFolder.canonicalPath }.getOrDefault(titleFolder.absolutePath)
            if (titleFolder.exists() &&
                titleFolderCanon !in remainingFolders &&
                titleFolderCanon !in remainingTitleFolders
            ) {
                titleFolder.deleteRecursively()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Computes a fast, deterministic fingerprint of a book file using its extension, exact byte size,
     * and SHA-256 of its first 32KB + last 32KB.
     */
    fun computeFileContentSignature(file: File): String? {
        return try {
            if (!file.exists() || !file.isFile) return null
            val len = file.length()
            if (len <= 0L) return null
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val chunkSize = 32 * 1024
            java.io.RandomAccessFile(file, "r").use { raf ->
                val headBuf = ByteArray(minOf(len, chunkSize.toLong()).toInt())
                raf.seek(0L)
                val headRead = raf.read(headBuf)
                if (headRead > 0) digest.update(headBuf, 0, headRead)
                if (len > chunkSize) {
                    val tailOffset = maxOf(chunkSize.toLong(), len - chunkSize.toLong())
                    val tailBuf = ByteArray((len - tailOffset).toInt())
                    raf.seek(tailOffset)
                    val tailRead = raf.read(tailBuf)
                    if (tailRead > 0) digest.update(tailBuf, 0, tailRead)
                }
            }
            val hashHex = digest.digest().take(12).joinToString("") { "%02x".format(it) }
            "${file.extension.lowercase(Locale.ROOT)}:$len:$hashHex"
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Deduplicates existing DocumentEntity rows in the database that point to the same underlying book
     * (by canonical path, parent folder, sanitized title, or identical file content signature).
     * Preserves the primary document with highlights, reading progress, and tags.
     */
    private suspend fun deduplicateExistingDatabaseDocuments(
        context: Context,
        database: AppDatabase,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ) {
        val documentDao = database.documentDao()
        val highlightDao = database.highlightDao()
        val allDocs = documentDao.getAllDocumentsSnapshot()
        if (allDocs.size <= 1) return

        val highlightCounts = allDocs.associate { doc ->
            doc.id to (runCatching { highlightDao.getHighlightsListForDocument(doc.id).size }.getOrDefault(0))
        }

        // Score documents so we always keep the one the user actually interacted with
        fun scoreDoc(doc: DocumentEntity): Long {
            var score = 0L
            val hCount = highlightCounts[doc.id] ?: 0
            score += hCount * 1_000_000L
            if (doc.tags.isNotBlank()) score += 100_000L
            if (doc.currentPage > 1) score += doc.currentPage * 1_000L
            if (doc.readingProgress > 0f) score += (doc.readingProgress * 10_000f).toLong()
            if (doc.isPinnedToWidget) score += 50_000L
            if (File(doc.filePath).exists()) score += 10_000L
            // Prefer earlier created (smaller id) as tie-breaker
            score -= doc.id
            return score
        }

        val sortedDocs = allDocs.sortedByDescending { scoreDoc(it) }
        val keptDocs = mutableListOf<DocumentEntity>()
        val keptCanonPaths = mutableSetOf<String>()
        val keptParentFolders = mutableSetOf<String>()
        val keptSanitizedTitles = mutableSetOf<String>()
        val keptFileSignatures = mutableMapOf<String, DocumentEntity>()

        for (doc in sortedDocs) {
            // Folder books have no book file of their own; never merge them with anything.
            if (doc.fileType.equals("FOLDER", ignoreCase = true)) { keptDocs.add(doc); continue }
            val file = File(doc.filePath)
            val canonPath = runCatching { file.canonicalPath.lowercase(Locale.ROOT) }
                .getOrDefault(doc.filePath.lowercase(Locale.ROOT))
            val parentCanon = runCatching { file.parentFile?.canonicalPath?.lowercase(Locale.ROOT) }.getOrNull()
            val cleanTitleKey = "${doc.category.uppercase(Locale.ROOT)}:${sanitizeName(doc.title).lowercase(Locale.ROOT)}"
            val fileSig = computeFileContentSignature(file)

            val matchedPrimary: DocumentEntity? = when {
                canonPath in keptCanonPaths -> keptDocs.firstOrNull {
                    runCatching { File(it.filePath).canonicalPath.lowercase(Locale.ROOT) }
                        .getOrDefault(it.filePath.lowercase(Locale.ROOT)) == canonPath
                }
                parentCanon != null && parentCanon in keptParentFolders -> keptDocs.firstOrNull {
                    runCatching { File(it.filePath).parentFile?.canonicalPath?.lowercase(Locale.ROOT) }.getOrNull() == parentCanon
                }
                cleanTitleKey in keptSanitizedTitles -> keptDocs.firstOrNull {
                    "${it.category.uppercase(Locale.ROOT)}:${sanitizeName(it.title).lowercase(Locale.ROOT)}" == cleanTitleKey
                }
                fileSig != null && fileSig in keptFileSignatures -> keptFileSignatures[fileSig]
                else -> null
            }

            if (matchedPrimary != null) {
                // Reassign any highlights from the duplicate document to matchedPrimary so zero notes are lost
                try {
                    val dupHighlights = highlightDao.getHighlightsListForDocument(doc.id)
                    val primaryHighlights = highlightDao.getHighlightsListForDocument(matchedPrimary.id)
                    for (h in dupHighlights) {
                        val alreadyExists = primaryHighlights.any {
                            it.highlightedText.trim() == h.highlightedText.trim() && it.pageNumber == h.pageNumber
                        }
                        if (!alreadyExists) {
                            highlightDao.insertHighlight(h.copy(id = 0, documentId = matchedPrimary.id))
                        }
                        highlightDao.deleteHighlight(h)
                    }
                } catch (_: Throwable) {}

                // Merge tags if primary had none
                if (matchedPrimary.tags.isBlank() && doc.tags.isNotBlank()) {
                    documentDao.updateDocument(matchedPrimary.copy(tags = doc.tags))
                }

                // Remove the duplicate DB entry
                documentDao.deleteDocument(doc)

                // If the duplicate points to a different orphan folder on disk, remove that orphan folder
                val primaryFile = File(matchedPrimary.filePath)
                val primaryParentCanon = runCatching { primaryFile.parentFile?.canonicalPath }.getOrNull()
                val dupParent = file.parentFile
                val dupParentCanon = runCatching { dupParent?.canonicalPath }.getOrNull()
                if (dupParent != null && dupParentCanon != null &&
                    primaryParentCanon != null &&
                    !dupParentCanon.equals(primaryParentCanon, ignoreCase = true) &&
                    dupParent.name != "Books" && dupParent.name != "Articles"
                ) {
                    runCatching { dupParent.deleteRecursively() }
                }
            } else {
                keptDocs.add(doc)
                keptCanonPaths.add(canonPath)
                if (parentCanon != null) keptParentFolders.add(parentCanon)
                keptSanitizedTitles.add(cleanTitleKey)
                if (fileSig != null) keptFileSignatures[fileSig] = doc
            }
        }
    }

    /**
     * Scans Readmark/Books and Readmark/Articles directories and syncs with Room DB.
     * Ensures files are NEVER lost even when the app restarts, while preventing duplicate
     * or deleted books from ever being re-added automatically.
     */
    suspend fun scanAndSyncLibrary(
        context: Context,
        database: AppDatabase,
        customPath: String = "",
        storageType: String = "DOCUMENTS"
    ) {
        val documentDao = database.documentDao()
        val root = getRootDirectory(context, customPath, storageType)
        val categories = listOf("Books" to "BOOK", "Articles" to "ARTICLE")

        // First, heal any existing duplicate rows in Room DB and clean up their orphan folders
        try {
            deduplicateExistingDatabaseDocuments(context, database, customPath, storageType)
        } catch (_: Throwable) {}

        val deletedKeys = getDeletedKeys(context, customPath, storageType)
        val existingDocs = documentDao.getAllDocumentsSnapshot().toMutableList()

        val pathMap = mutableMapOf<String, DocumentEntity>()
        val canonPathMap = mutableMapOf<String, DocumentEntity>()
        val parentCanonMap = mutableMapOf<String, DocumentEntity>()
        val titleMap = mutableMapOf<String, DocumentEntity>()
        val sanitizedTitleMap = mutableMapOf<String, DocumentEntity>()
        val signatureMap = mutableMapOf<String, DocumentEntity>()

        fun indexDoc(doc: DocumentEntity) {
            pathMap[doc.filePath] = doc
            val f = File(doc.filePath)
            runCatching { canonPathMap[f.canonicalPath] = doc }
            runCatching { f.parentFile?.canonicalPath?.let { parentCanonMap[it] = doc } }
            runCatching {
                val expectedFolder = getDocumentFolder(context, doc.category, doc.title, customPath, storageType)
                parentCanonMap[expectedFolder.canonicalPath] = doc
            }
            titleMap[doc.title.trim().lowercase(Locale.ROOT)] = doc
            sanitizedTitleMap[sanitizeName(doc.title).lowercase(Locale.ROOT)] = doc
            computeFileContentSignature(f)?.let { sig ->
                signatureMap[sig] = doc
            }
        }

        existingDocs.forEach { indexDoc(it) }

        val validExtensions = setOf("pdf", "epub", "zip", "html", "htm", "xhtml", "txt")

        for ((folderName, category) in categories) {
            val catDir = File(root, folderName)
            if (!catDir.exists() || !catDir.isDirectory) continue

            val docFolders = catDir.listFiles()?.filter { it.isDirectory } ?: continue
            for (docFolder in docFolders) {
                val title = docFolder.name
                val candidateFiles = docFolder.listFiles()?.filter { file ->
                    file.isFile && file.extension.lowercase(Locale.ROOT) in validExtensions
                } ?: emptyList()
                if (candidateFiles.isEmpty()) continue

                // Prefer the file that is already registered in Room DB if multiple files exist in the folder
                val bookFile = candidateFiles.firstOrNull { candidate ->
                    pathMap.containsKey(candidate.absolutePath) ||
                        runCatching { canonPathMap.containsKey(candidate.canonicalPath) }.getOrDefault(false)
                } ?: candidateFiles.first()

                val bookFileCanon = runCatching { bookFile.canonicalPath }.getOrDefault(bookFile.absolutePath)
                val folderCanon = runCatching { docFolder.canonicalPath }.getOrDefault(docFolder.absolutePath)
                val cleanTitle = sanitizeName(title).ifBlank { "Book" }
                val fileSig = computeFileContentSignature(bookFile)

                // Check if this book was explicitly deleted by the user
                val wasExplicitlyDeleted =
                    "path:${bookFile.absolutePath.lowercase(Locale.ROOT)}" in deletedKeys ||
                        "canon:${bookFileCanon.lowercase(Locale.ROOT)}" in deletedKeys ||
                        "folder:${cleanTitle.lowercase(Locale.ROOT)}" in deletedKeys ||
                        "title:${cleanTitle.lowercase(Locale.ROOT)}" in deletedKeys ||
                        (fileSig != null && "sig:$fileSig" in deletedKeys)

                val matchedByMetadata = pathMap[bookFile.absolutePath]
                    ?: canonPathMap[bookFileCanon]
                    ?: parentCanonMap[folderCanon]
                    ?: titleMap[title.trim().lowercase(Locale.ROOT)]
                    ?: sanitizedTitleMap[cleanTitle.lowercase(Locale.ROOT)]
                    ?: (if (fileSig != null) signatureMap[fileSig] else null)

                if (matchedByMetadata == null && wasExplicitlyDeleted) {
                    // Clean up the leftover deleted folder so it never resurfaces
                    runCatching { docFolder.deleteRecursively() }
                    continue
                }

                // If matched by file signature to a document that already lives in a DIFFERENT folder on disk,
                // and that primary document's file exists, then this docFolder is a duplicate orphan folder!
                if (matchedByMetadata != null) {
                    val primaryFile = File(matchedByMetadata.filePath)
                    val primaryParentCanon = runCatching { primaryFile.parentFile?.canonicalPath }.getOrNull()
                    if (primaryFile.exists() && primaryFile.length() > 0L &&
                        primaryParentCanon != null &&
                        !primaryParentCanon.equals(folderCanon, ignoreCase = true)
                    ) {
                        // Delete the duplicate orphan folder so it doesn't clutter disk or re-trigger
                        runCatching { docFolder.deleteRecursively() }
                        continue
                    }
                }

                val ext = bookFile.extension.uppercase(Locale.ROOT)
                val fileType = when (ext) {
                    "PDF" -> "PDF"
                    "ZIP" -> if (com.example.reader.DaisyParserEngine.isDaisyZipArchive(bookFile)) "DAISY" else "EPUB"
                    "HTML", "HTM", "XHTML" -> "HTML"
                    "TXT" -> "TXT"
                    else -> "EPUB"
                }

                val webpCoverFile = File(docFolder, "$cleanTitle - front cover.WebP")
                val jpgCoverFile = File(docFolder, "cover.jpg")
                val coverUri = when {
                    webpCoverFile.exists() && webpCoverFile.length() > 0L -> webpCoverFile.absolutePath
                    jpgCoverFile.exists() && jpgCoverFile.length() > 0L -> jpgCoverFile.absolutePath
                    else -> null
                }

                if (matchedByMetadata != null) {
                    var updatedDoc = matchedByMetadata
                    val existingDocFile = File(matchedByMetadata.filePath)
                    val shouldUpdatePath = !existingDocFile.exists() || existingDocFile.length() == 0L
                    if (shouldUpdatePath && matchedByMetadata.filePath != bookFile.absolutePath) {
                        updatedDoc = updatedDoc.copy(filePath = bookFile.absolutePath)
                    }
                    if (coverUri != null && matchedByMetadata.coverUri.isNullOrBlank()) {
                        updatedDoc = updatedDoc.copy(coverUri = coverUri)
                    }
                    if (updatedDoc.coverAspectRatio <= 0f) {
                        val effectiveCoverPath = updatedDoc.coverUri ?: coverUri
                        val ratio = decodeCoverAspectRatio(effectiveCoverPath)
                        if (ratio > 0f) {
                            updatedDoc = updatedDoc.copy(coverAspectRatio = ratio)
                        }
                    }
                    // For PDFs with totalPages <= 1, native PdfRenderer page count is instant
                    if (fileType == "PDF" && updatedDoc.totalPages <= 1) {
                        try {
                            val renderer = PdfDocumentRenderer(bookFile, context)
                            val freshTotal = renderer.pageCount.coerceAtLeast(1)
                            renderer.close()
                            if (freshTotal > 1) {
                                val safeCur = updatedDoc.currentPage.coerceIn(1, freshTotal)
                                val freshProgress = ((safeCur - 1).toFloat() / (freshTotal - 1).toFloat()).coerceIn(0f, 0.99f)
                                updatedDoc = updatedDoc.copy(
                                    currentPage = safeCur,
                                    totalPages = freshTotal,
                                    readingProgress = freshProgress
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                    if (updatedDoc.estimatedReadingMinutes <= 0) {
                        val calcMins = com.example.reader.ReadingTimeEstimator.calculateReadingTimeMinutes(
                            file = bookFile,
                            fileType = fileType,
                            totalPages = updatedDoc.totalPages,
                            category = updatedDoc.category,
                            context = context
                        )
                        if (calcMins > 0) {
                            updatedDoc = updatedDoc.copy(estimatedReadingMinutes = calcMins)
                        }
                    }
                    if (updatedDoc != matchedByMetadata) {
                        documentDao.updateDocument(updatedDoc)
                        indexDoc(updatedDoc)
                    }
                } else {
                    // Unmatched folder on disk: inspect internal metadata first before inserting
                    var pages = 1
                    var author = "Unknown"
                    var resolvedTitle = title
                    var parsedCoverBitmap: Bitmap? = null
                    try {
                        if (fileType == "PDF") {
                            val renderer = PdfDocumentRenderer(bookFile, context)
                            pages = renderer.pageCount.coerceAtLeast(1)
                            parsedCoverBitmap = renderer.renderPage(0, targetWidth = 400)
                            renderer.close()
                        } else if (fileType == "DAISY") {
                            val quickMeta = com.example.reader.DaisyParserEngine.peekDaisyZipMetadata(
                                zipFile = bookFile,
                                fallbackTitle = title,
                                fallbackAuthor = author
                            )
                            if (quickMeta.title.isNotBlank()) resolvedTitle = quickMeta.title
                            if (quickMeta.author.isNotBlank() && quickMeta.author != "Unknown Author") {
                                author = quickMeta.author
                            }
                            pages = quickMeta.estimatedPages.coerceAtLeast(1)
                            parsedCoverBitmap = quickMeta.coverBitmap
                                ?: EpubParser.generateBookCoverBitmap(resolvedTitle, author)
                        } else if (fileType == "EPUB") {
                            bookFile.inputStream().use { stream ->
                                val epub = EpubParser.parseEpub(stream)
                                val (paginatedPages, _) = EpubParser.paginateBook(epub)
                                pages = paginatedPages.size.coerceAtLeast(1)
                                if (epub.title.isNotBlank() && epub.title != "Unknown Title") {
                                    resolvedTitle = epub.title
                                }
                                if (epub.author.isNotBlank() && epub.author != "Unknown Author") {
                                    author = epub.author
                                }
                                parsedCoverBitmap = epub.coverBitmap
                                    ?: EpubParser.generateBookCoverBitmap(resolvedTitle, author)
                            }
                        } else if (fileType == "HTML") {
                            val rawHtml = bookFile.readText(Charsets.UTF_8)
                            val parsedHtml = EpubParser.parseHtmlDocument(rawHtml, title, author)
                            val (paginatedPages, _) = EpubParser.paginateBook(parsedHtml)
                            pages = paginatedPages.size.coerceAtLeast(1)
                            if (parsedHtml.title.isNotBlank() && parsedHtml.title != "HTML Document") {
                                resolvedTitle = parsedHtml.title
                            }
                            if (parsedHtml.author.isNotBlank() && parsedHtml.author != "Unknown Author") {
                                author = parsedHtml.author
                            }
                            parsedCoverBitmap = EpubParser.generateBookCoverBitmap(resolvedTitle, author)
                        } else if (fileType == "TXT") {
                            val lines = bookFile.readLines().filter { it.isNotBlank() }
                            pages = (lines.size / 4).coerceAtLeast(1)
                            parsedCoverBitmap = EpubParser.generateBookCoverBitmap(resolvedTitle, author)
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }

                    // Check again using the parsed internal metadata title (e.g. EPUB <dc:title>)!
                    val cleanResolvedTitle = sanitizeName(resolvedTitle)
                    if ("title:${cleanResolvedTitle.lowercase(Locale.ROOT)}" in deletedKeys) {
                        runCatching { docFolder.deleteRecursively() }
                        continue
                    }
                    val matchedAfterParse = titleMap[resolvedTitle.trim().lowercase(Locale.ROOT)]
                        ?: sanitizedTitleMap[cleanResolvedTitle.lowercase(Locale.ROOT)]

                    if (matchedAfterParse != null) {
                        // This book is ALREADY in the database under its real metadata title!
                        val primaryFile = File(matchedAfterParse.filePath)
                        if (primaryFile.exists() && primaryFile.length() > 0L &&
                            !primaryFile.absolutePath.equals(bookFile.absolutePath, ignoreCase = true)
                        ) {
                            // Remove the duplicate folder with the raw filename
                            runCatching { docFolder.deleteRecursively() }
                        } else if (!primaryFile.exists()) {
                            val updated = matchedAfterParse.copy(filePath = bookFile.absolutePath)
                            documentDao.updateDocument(updated)
                            indexDoc(updated)
                        }
                        continue
                    }

                    if (coverUri == null && parsedCoverBitmap != null) {
                        saveCoverImage(context, category, title, parsedCoverBitmap!!, customPath, storageType)
                    }

                    val updatedWebpCover = File(docFolder, "$cleanTitle - front cover.WebP")
                    val updatedJpgCover = File(docFolder, "cover.jpg")
                    val finalCoverUri = when {
                        updatedWebpCover.exists() && updatedWebpCover.length() > 0L -> updatedWebpCover.absolutePath
                        updatedJpgCover.exists() && updatedJpgCover.length() > 0L -> updatedJpgCover.absolutePath
                        else -> coverUri
                    }

                    val finalCoverRatio = decodeCoverAspectRatio(finalCoverUri, parsedCoverBitmap)
                    val estimatedMins = com.example.reader.ReadingTimeEstimator.calculateReadingTimeMinutes(
                        file = bookFile,
                        fileType = fileType,
                        totalPages = pages,
                        category = category,
                        context = context
                    )

                    val specInfo = if (fileType == "EPUB") {
                        EpubParser.detectEpubSpecification(bookFile)
                    } else null

                    val newDoc = DocumentEntity(
                        title = resolvedTitle,
                        author = author,
                        filePath = bookFile.absolutePath,
                        fileType = fileType,
                        category = category,
                        coverUri = finalCoverUri,
                        totalPages = pages,
                        lastReadTimestamp = System.currentTimeMillis(),
                        coverAspectRatio = finalCoverRatio,
                        estimatedReadingMinutes = estimatedMins,
                        epubVersion = specInfo?.displayBadge.orEmpty(),
                        epubLayoutType = if (specInfo != null) {
                            if (specInfo.isFixedLayout) "pre-paginated" else "reflowable"
                        } else "",
                        epubFeaturesSummary = specInfo?.detailedLabel.orEmpty()
                    )
                    val newId = documentDao.insertDocument(newDoc)
                    indexDoc(newDoc.copy(id = newId))
                }

                // Ensure highlight.md exists in folder
                val highlightFile = File(docFolder, "highlight.md")
                if (!highlightFile.exists() && docFolder.exists()) {
                    saveHighlightMarkdown(
                        context = context,
                        category = category,
                        documentTitle = title,
                        highlights = emptyList(),
                        author = matchedByMetadata?.author ?: "Unknown",
                        customPath = customPath,
                        storageType = storageType
                    )
                }
            }
        }

        // Background backfill for existing EPUBs whose epubVersion is empty, processed sequentially on Dispatchers.IO
        withContext(Dispatchers.IO) {
            val allCurrentDocs = documentDao.getAllDocumentsSnapshot()
            for (doc in allCurrentDocs) {
                if (doc.fileType.equals("EPUB", ignoreCase = true) && doc.epubVersion.isEmpty()) {
                    val bookFile = File(doc.filePath)
                    val specInfo = EpubParser.detectEpubSpecification(bookFile)
                    val updatedDoc = doc.copy(
                        epubVersion = specInfo.displayBadge.ifBlank { "EPUB" },
                        epubLayoutType = if (specInfo.isFixedLayout) "pre-paginated" else "reflowable",
                        epubFeaturesSummary = specInfo.detailedLabel.ifBlank { "EPUB" }
                    )
                    documentDao.updateDocument(updatedDoc)
                }
            }
        }
    }

    /**
     * No-op: Example files removed per user specification.
     */
    fun seedInitialFilesIfEmpty(context: Context, customPath: String = "", storageType: String = "DOCUMENTS") {
        // No example files created
    }
}
