package com.example.server

import android.content.Context
import android.net.Uri
import com.example.data.StorageManager
import com.example.data.local.DocumentEntity
import com.example.reader.EpubParser
import com.example.reader.PdfDocumentRenderer
import java.io.File
import java.util.Locale

/**
 * Adds an uploaded file to the library the same way “Add document” does in the app: the file
 * goes into Readmark/Books|Articles/<Title>/, the title, author, page count and cover are read
 * from the file, and the document row is created (or an existing copy is updated).
 */
object LibraryImporter {

    val supportedExtensions = setOf("pdf", "epub", "zip", "html", "htm", "xhtml", "txt", "md", "markdown")

    suspend fun import(context: Context, ops: LibraryOps, upload: File, originalName: String, category: String): DocumentEntity {
        val settings = ops.repository.getSettings()
        val customPath = settings.customStoragePath
        val storageType = settings.storageLocationType
        val cat = if (category.equals("ARTICLE", true)) "ARTICLE" else "BOOK"

        val header = ByteArray(8).also { b -> upload.inputStream().use { it.read(b) } }
        val isPdfMagic = header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
        val isZipMagic = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        val ext = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val fileType = when {
            ext == "pdf" || isPdfMagic -> "PDF"
            ext == "zip" -> "ZIP_CANDIDATE"
            ext == "epub" || isZipMagic -> "EPUB"
            ext == "md" || ext == "markdown" -> "MD"
            ext == "html" || ext == "htm" || ext == "xhtml" -> "HTML"
            ext == "txt" -> "TXT"
            else -> throw IllegalArgumentException("“$originalName” is not a PDF, EPUB, DAISY, HTML, Markdown or text file.")
        }
        val defaultExt = when (fileType) { "PDF" -> "pdf"; "ZIP_CANDIDATE" -> "zip"; "MD" -> "md"; "HTML" -> "html"; "TXT" -> "txt"; else -> "epub" }
        val fileName = if (originalName.contains('.')) originalName else "$originalName.$defaultExt"
        val initialTitle = fileName.substringBeforeLast('.').ifBlank { "Uploaded Document" }

        val destFile = StorageManager.saveDocumentFile(
            context = context, uri = Uri.fromFile(upload), documentTitle = initialTitle, category = cat,
            originalFileName = fileName, customPath = customPath, storageType = storageType
        ) ?: throw IllegalStateException("Could not save “$originalName” on the tablet.")

        var resolvedType = fileType
        if (fileType == "ZIP_CANDIDATE" || (fileType == "EPUB" && isZipMagic)) {
            if (com.example.reader.DaisyParserEngine.isDaisyZipArchive(destFile)) resolvedType = "DAISY"
            else if (fileType == "ZIP_CANDIDATE") resolvedType = "EPUB"
        }

        var title = initialTitle
        var author = ""
        var pages = 1
        var cover: String? = null
        var detected: com.example.reader.BibRecord? = null

        fun saveCover(bmp: android.graphics.Bitmap?) {
            if (bmp == null) return
            cover = StorageManager.saveCoverImage(
                context = context, category = cat, documentTitle = title, bitmap = bmp,
                customPath = customPath, storageType = storageType
            )?.absolutePath
        }

        runCatching {
            when (resolvedType) {
                "DAISY" -> {
                    val meta = com.example.reader.DaisyParserEngine.peekDaisyZipMetadata(zipFile = destFile, fallbackTitle = initialTitle, fallbackAuthor = author)
                    if (meta.title.isNotBlank()) title = meta.title
                    if (meta.author.isNotBlank() && meta.author != "Unknown Author") author = meta.author
                    pages = meta.estimatedPages.coerceAtLeast(1)
                    saveCover(meta.coverBitmap ?: EpubParser.generateBookCoverBitmap(title, author.ifBlank { "DAISY" }))
                }
                "HTML" -> {
                    val parsed = EpubParser.parseHtmlDocument(rawHtml = destFile.readText(Charsets.UTF_8), fallbackTitle = initialTitle, fallbackAuthor = "Unknown Author")
                    if (parsed.title.isNotBlank() && parsed.title != "HTML Document") title = parsed.title
                    if (parsed.author.isNotBlank() && parsed.author != "Unknown Author") author = parsed.author
                    pages = EpubParser.paginateBook(parsed).first.size.coerceAtLeast(1)
                    saveCover(EpubParser.generateBookCoverBitmap(title, author.ifBlank { "Unknown" }))
                }
                "TXT", "MD" -> {
                    pages = (destFile.readLines().count { it.isNotBlank() } / 4).coerceAtLeast(1)
                    saveCover(EpubParser.generateBookCoverBitmap(title, author.ifBlank { "Unknown" }))
                }
                "EPUB" -> destFile.inputStream().use { stream ->
                    val epub = EpubParser.parseEpub(stream)
                    if (epub.title.isNotBlank() && epub.title != "Unknown Title") title = epub.title
                    if (epub.author.isNotBlank() && epub.author != "Unknown Author") author = epub.author
                    val found = com.example.reader.MetadataDetector.forEpub(title, author, originalName)
                    if (found.fullTitle.isNotBlank()) title = found.fullTitle
                    if (author.isBlank()) author = found.authors
                    detected = found.record
                    pages = EpubParser.paginateBook(epub).first.size.coerceAtLeast(1)
                    saveCover(epub.coverBitmap ?: EpubParser.generateBookCoverBitmap(title, author))
                }
                else -> {
                    runCatching { com.example.reader.MetadataDetector.fromPdf(context, destFile, originalName) }.getOrNull()?.let { found ->
                        if (found.fullTitle.isNotBlank()) title = found.fullTitle
                        if (author.isBlank()) author = found.authors
                        detected = found.record
                    }
                    val renderer = PdfDocumentRenderer(destFile, context)
                    pages = renderer.pageCount.coerceAtLeast(1)
                    val page0 = renderer.renderPage(0, targetWidth = 400)
                    renderer.close()
                    saveCover(page0)
                }
            }
        }

        // Move into a folder named after the real title, as the app's import does.
        var finalFile = destFile
        if (!StorageManager.sanitizeName(title).equals(StorageManager.sanitizeName(initialTitle), ignoreCase = true)) {
            runCatching {
                val oldFolder = finalFile.parentFile
                val newFolder = StorageManager.getDocumentFolder(context, cat, title, customPath, storageType)
                if (oldFolder != null && oldFolder.absolutePath != newFolder.absolutePath) {
                    val target = File(newFolder, finalFile.name)
                    if (target.exists() && target.absolutePath != finalFile.absolutePath) target.delete()
                    if (!finalFile.renameTo(target)) {
                        finalFile.copyTo(target, overwrite = true, bufferSize = 256 * 1024)
                        finalFile.delete()
                    }
                    if (target.exists() && target.length() > 0L) {
                        finalFile = target
                        // The cover was saved in the first folder; keep it with the book.
                        cover?.let { c ->
                            val old = File(c)
                            if (old.exists() && old.parentFile?.absolutePath == oldFolder.absolutePath) {
                                val moved = File(newFolder, old.name)
                                old.copyTo(moved, overwrite = true)
                                cover = moved.absolutePath
                            }
                        }
                        if (oldFolder.name != "Books" && oldFolder.name != "Articles") oldFolder.deleteRecursively()
                    }
                }
            }
        }

        detected?.let { com.example.reader.MetadataDetector.remember(finalFile.absolutePath, it) }
        return ops.addDocument(
            title = title,
            author = author,
            category = cat,
            fileType = resolvedType,
            filePath = finalFile.absolutePath,
            totalPages = pages,
            coverUri = cover
        )
    }
}
