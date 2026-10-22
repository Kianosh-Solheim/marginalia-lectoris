package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.StorageManager
import com.example.model.DocumentCategory
import com.example.reader.EpubParser
import com.example.reader.PdfDocumentRenderer
import com.example.ui.theme.DisableDialogDimming
import com.example.ui.theme.KaleidoCobaltFill
import com.example.ui.theme.KaleidoCobaltInk
import com.example.ui.theme.KaleidoSageFill
import com.example.ui.theme.KaleidoSageInk
import com.example.ui.theme.KaleidoTerracottaFill
import com.example.ui.theme.KaleidoTerracottaInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.kaleidoSwatchForFileType

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddDocumentDialog(
    customStoragePath: String = "",
    storageLocationType: String = "DOCUMENTS",
    initialSharedUri: Uri? = null,
    initialSharedText: String? = null,
    onDismiss: () -> Unit,
    onAddDocument: (title: String, author: String, category: String, fileType: String, filePath: String, coverName: String?, totalPages: Int, coverUri: String?, openImmediately: Boolean) -> Unit
) {
    val context = LocalContext.current
    val isSharedImport = initialSharedUri != null || !initialSharedText.isNullOrBlank()

    var selectedCategory by remember { mutableStateOf(DocumentCategory.BOOK) }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var detectedFileType by remember { mutableStateOf("EPUB") }
    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedCoverUri by remember { mutableStateOf<String?>(null) }
    var detectedPages by remember { mutableStateOf(1) }
    var articleTextContent by remember { mutableStateOf(initialSharedText ?: "") }
    var isProcessingFile by remember { mutableStateOf(false) }
    var isSavingDocument by remember { mutableStateOf(false) }
    var fileLoadErrorMessage by remember { mutableStateOf<String?>(null) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val simulateChosenFile: (String) -> Unit = { format ->
        validationMessage = null
        fileLoadErrorMessage = null
        val normalizedFormat = format.uppercase()
        val ext = when (normalizedFormat) {
            "PDF" -> "pdf"
            "DAISY" -> "zip"
            "MD" -> "md"
            "HTML" -> "html"
            "TXT" -> "txt"
            else -> "epub"
        }
        val sampleFileName = if (selectedCategory == DocumentCategory.BOOK) {
            "selected_book.$ext"
        } else {
            "selected_article.$ext"
        }
        detectedFileType = normalizedFormat
        selectedFileName = sampleFileName
        val currentCategoryName = selectedCategory.name
        val docTitle = title.ifBlank {
            if (selectedCategory == DocumentCategory.BOOK) "Untitled Book" else "Untitled Article"
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val folder = StorageManager.getDocumentFolder(
                    context = context,
                    category = currentCategoryName,
                    documentTitle = docTitle,
                    customPath = customStoragePath,
                    storageType = storageLocationType
                )
                val targetFile = File(folder, sampleFileName)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    targetFile.writeText(
                        when (normalizedFormat) {
                            "MD" -> "# $docTitle\n\nImported Markdown document."
                            "HTML" -> "<html><body><h1>$docTitle</h1><p>Imported HTML document.</p></body></html>"
                            else -> "$docTitle\n\nImported $normalizedFormat document."
                        }
                    )
                }
                val coverBmp = EpubParser.generateBookCoverBitmap(
                    docTitle,
                    author.ifBlank { normalizedFormat }
                )
                val savedCover = StorageManager.saveCoverImage(
                    context = context,
                    category = currentCategoryName,
                    documentTitle = docTitle,
                    bitmap = coverBmp,
                    customPath = customStoragePath,
                    storageType = storageLocationType
                )
                withContext(Dispatchers.Main) {
                    selectedFilePath = targetFile.absolutePath
                    selectedCoverUri = savedCover?.absolutePath
                    detectedPages = 12
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    selectedFilePath = "simulated://$sampleFileName"
                    detectedPages = 12
                }
            }
        }
    }

    val processSelectedUri: (Uri) -> Unit = { uri ->
        isProcessingFile = true
        fileLoadErrorMessage = null
        validationMessage = null
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}

                val rawFileName = getFileName(context, uri) ?: "document"
                val mimeType = try { context.contentResolver.getType(uri)?.lowercase() ?: "" } catch (_: Exception) { "" }
                val ext = rawFileName.substringAfterLast(".", "").lowercase()

                val headerSignature = try {
                    val buf = ByteArray(8)
                    val read = (if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                        File(uri.path!!).inputStream()
                    } else {
                        context.contentResolver.openInputStream(uri)
                    })?.use { it.read(buf) } ?: -1
                    if (read >= 4) buf else null
                } catch (_: Throwable) {
                    null
                }
                val isPdfMagic = headerSignature != null &&
                    headerSignature[0] == '%'.code.toByte() &&
                    headerSignature[1] == 'P'.code.toByte() &&
                    headerSignature[2] == 'D'.code.toByte() &&
                    headerSignature[3] == 'F'.code.toByte()
                val isZipMagic = headerSignature != null &&
                    headerSignature[0] == 'P'.code.toByte() &&
                    headerSignature[1] == 'K'.code.toByte()

                val fileType = when {
                    ext == "pdf" || mimeType.contains("pdf") || isPdfMagic -> "PDF"
                    ext == "zip" || mimeType.contains("zip") && !mimeType.contains("epub") -> "ZIP_CANDIDATE"
                    ext == "epub" || mimeType.contains("epub") || isZipMagic -> "EPUB"
                    ext == "md" || ext == "markdown" || mimeType.contains("markdown") -> "MD"
                    ext == "html" || ext == "htm" || ext == "xhtml" || mimeType.contains("html") -> "HTML"
                    ext == "txt" || mimeType.startsWith("text/") -> "TXT"
                    else -> "EPUB"
                }

                val defaultExt = when (fileType) {
                    "PDF" -> "pdf"
                    "ZIP_CANDIDATE" -> "zip"
                    "MD" -> "md"
                    "HTML" -> "html"
                    "TXT" -> "txt"
                    else -> "epub"
                }
                val fileName = if (rawFileName.contains(".")) rawFileName else "$rawFileName.$defaultExt"
                val initialTitle = fileName.substringBeforeLast(".").ifBlank { "Shared Document" }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    detectedFileType = if (fileType == "ZIP_CANDIDATE") "DAISY" else fileType
                    selectedFileName = fileName
                    if (title.isBlank()) {
                        title = initialTitle
                    }
                }

                val currentCategoryName = selectedCategory.name
                val destFile = StorageManager.saveDocumentFile(
                    context = context,
                    uri = uri,
                    documentTitle = initialTitle,
                    category = currentCategoryName,
                    originalFileName = fileName,
                    customPath = customStoragePath,
                    storageType = storageLocationType
                )

                if (destFile == null || !destFile.exists() || destFile.length() == 0L) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        fileLoadErrorMessage = "Could not read the selected file. Please try selecting it again."
                        isProcessingFile = false
                    }
                    return@launch
                }

                var resolvedFileType = fileType
                if (fileType == "ZIP_CANDIDATE" || (fileType == "EPUB" && isZipMagic)) {
                    if (com.example.reader.DaisyParserEngine.isDaisyZipArchive(destFile)) {
                        resolvedFileType = "DAISY"
                    } else if (fileType == "ZIP_CANDIDATE") {
                        resolvedFileType = "EPUB"
                    }
                }

                var resolvedTitle = initialTitle
                var resolvedAuthor = author
                var resolvedPages = 1
                var resolvedCoverUri: String? = null
                var detectedRecord: com.example.reader.BibRecord? = null

                if (resolvedFileType == "DAISY") {
                    try {
                        val quickMeta = com.example.reader.DaisyParserEngine.peekDaisyZipMetadata(
                            zipFile = destFile,
                            fallbackTitle = initialTitle,
                            fallbackAuthor = resolvedAuthor
                        )
                        if (quickMeta.title.isNotBlank()) {
                            resolvedTitle = quickMeta.title
                        }
                        if (quickMeta.author.isNotBlank() && quickMeta.author != "Unknown Author") {
                            resolvedAuthor = quickMeta.author
                        }
                        resolvedPages = quickMeta.estimatedPages.coerceAtLeast(1)
                        val coverBmp = quickMeta.coverBitmap
                            ?: EpubParser.generateBookCoverBitmap(resolvedTitle, resolvedAuthor.ifBlank { "DAISY" })
                        val savedCover = StorageManager.saveCoverImage(
                            context = context,
                            category = currentCategoryName,
                            documentTitle = resolvedTitle,
                            bitmap = coverBmp,
                            customPath = customStoragePath,
                            storageType = storageLocationType
                        )
                        resolvedCoverUri = savedCover?.absolutePath
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                } else if (resolvedFileType == "HTML") {
                    try {
                        val rawHtml = destFile.readText(Charsets.UTF_8)
                        val parsedHtml = EpubParser.parseHtmlDocument(
                            rawHtml = rawHtml,
                            fallbackTitle = initialTitle,
                            fallbackAuthor = resolvedAuthor.ifBlank { "Unknown Author" }
                        )
                        if (parsedHtml.title.isNotBlank() && parsedHtml.title != "HTML Document") {
                            resolvedTitle = parsedHtml.title
                        }
                        if (parsedHtml.author.isNotBlank() && parsedHtml.author != "Unknown Author") {
                            resolvedAuthor = parsedHtml.author
                        }
                        val (paginatedPages, _) = EpubParser.paginateBook(parsedHtml)
                        resolvedPages = paginatedPages.size.coerceAtLeast(1)
                        val coverBmp = EpubParser.generateBookCoverBitmap(resolvedTitle, resolvedAuthor.ifBlank { "Unknown" })
                        val savedCover = StorageManager.saveCoverImage(
                            context = context,
                            category = currentCategoryName,
                            documentTitle = resolvedTitle,
                            bitmap = coverBmp,
                            customPath = customStoragePath,
                            storageType = storageLocationType
                        )
                        resolvedCoverUri = savedCover?.absolutePath
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                } else if (resolvedFileType == "TXT" || resolvedFileType == "MD") {
                    try {
                        val lines = destFile.readLines().filter { it.isNotBlank() }
                        resolvedPages = (lines.size / 4).coerceAtLeast(1)
                        val coverBmp = EpubParser.generateBookCoverBitmap(resolvedTitle, resolvedAuthor.ifBlank { "Unknown" })
                        val savedCover = StorageManager.saveCoverImage(
                            context = context,
                            category = currentCategoryName,
                            documentTitle = resolvedTitle,
                            bitmap = coverBmp,
                            customPath = customStoragePath,
                            storageType = storageLocationType
                        )
                        resolvedCoverUri = savedCover?.absolutePath
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                } else if (resolvedFileType == "EPUB") {
                    try {
                        destFile.inputStream().use { stream ->
                            val epub = EpubParser.parseEpub(stream)
                            if (epub.title.isNotBlank() && epub.title != "Unknown Title") {
                                resolvedTitle = epub.title
                            }
                            if (epub.author.isNotBlank() && epub.author != "Unknown Author") {
                                resolvedAuthor = epub.author
                            }
                            val found = com.example.reader.MetadataDetector.forEpub(resolvedTitle, if (resolvedAuthor == author) "" else resolvedAuthor, fileName)
                            if (found.fullTitle.isNotBlank()) resolvedTitle = found.fullTitle
                            if (resolvedAuthor.isBlank() && found.authors.isNotBlank()) resolvedAuthor = found.authors
                            detectedRecord = found.record
                            val (paginatedPages, _) = EpubParser.paginateBook(epub)
                            resolvedPages = paginatedPages.size.coerceAtLeast(1)

                            val coverBmp = epub.coverBitmap ?: EpubParser.generateBookCoverBitmap(resolvedTitle, resolvedAuthor)
                            val savedCover = StorageManager.saveCoverImage(
                                context = context,
                                category = currentCategoryName,
                                documentTitle = resolvedTitle,
                                bitmap = coverBmp,
                                customPath = customStoragePath,
                                storageType = storageLocationType
                            )
                            resolvedCoverUri = savedCover?.absolutePath
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                } else {
                    // PDF
                    try {
                        // Title, subtitle, authors, DOI, ISBN and year from the file name, metadata and first pages.
                        val found = com.example.reader.MetadataDetector.fromPdf(context, destFile, rawFileName)
                        if (found.fullTitle.isNotBlank()) resolvedTitle = found.fullTitle
                        if (resolvedAuthor.isBlank() && found.authors.isNotBlank()) resolvedAuthor = found.authors
                        detectedRecord = found.record
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                    try {
                        val pdfRenderer = PdfDocumentRenderer(destFile, context)
                        resolvedPages = pdfRenderer.pageCount.coerceAtLeast(1)
                        val page0 = pdfRenderer.renderPage(0, targetWidth = 400)
                        pdfRenderer.close()
                        if (page0 != null) {
                            val savedCover = StorageManager.saveCoverImage(
                                context = context,
                                category = currentCategoryName,
                                documentTitle = resolvedTitle,
                                bitmap = page0,
                                customPath = customStoragePath,
                                storageType = storageLocationType
                            )
                            resolvedCoverUri = savedCover?.absolutePath
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }

                var finalStagedFile = destFile!!
                if (!StorageManager.sanitizeName(resolvedTitle).equals(StorageManager.sanitizeName(initialTitle), ignoreCase = true)) {
                    try {
                        val oldInitialFolder = finalStagedFile.parentFile
                        val resolvedFolder = StorageManager.getDocumentFolder(
                            context = context,
                            category = currentCategoryName,
                            documentTitle = resolvedTitle,
                            customPath = customStoragePath,
                            storageType = storageLocationType
                        )
                        if (oldInitialFolder != null && oldInitialFolder.absolutePath != resolvedFolder.absolutePath) {
                            val targetInResolved = File(resolvedFolder, finalStagedFile.name)
                            if (targetInResolved.exists() && targetInResolved.absolutePath != finalStagedFile.absolutePath) {
                                targetInResolved.delete()
                            }
                            val moved = finalStagedFile.renameTo(targetInResolved)
                            if (!moved) {
                                finalStagedFile.copyTo(targetInResolved, overwrite = true, bufferSize = 256 * 1024)
                                finalStagedFile.delete()
                            }
                            if (targetInResolved.exists() && targetInResolved.length() > 0L) {
                                finalStagedFile = targetInResolved
                                if (oldInitialFolder.name != "Books" && oldInitialFolder.name != "Articles") {
                                    oldInitialFolder.deleteRecursively()
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                detectedRecord?.let { com.example.reader.MetadataDetector.remember(finalStagedFile.absolutePath, it) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    detectedFileType = resolvedFileType
                    selectedFilePath = finalStagedFile.absolutePath
                    title = resolvedTitle
                    if (resolvedAuthor.isNotBlank()) {
                        author = resolvedAuthor
                    }
                    detectedPages = resolvedPages.coerceAtLeast(1)
                    selectedCoverUri = resolvedCoverUri
                    isProcessingFile = false
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    fileLoadErrorMessage = "Failed to process file: ${e.localizedMessage ?: "Unknown error"}"
                    isProcessingFile = false
                }
            }
        }
    }

    LaunchedEffect(initialSharedUri, initialSharedText) {
        if (initialSharedUri != null) {
            processSelectedUri(initialSharedUri)
        } else if (!initialSharedText.isNullOrBlank()) {
            selectedCategory = DocumentCategory.ARTICLE
            detectedFileType = "TXT"
            articleTextContent = initialSharedText
            if (title.isBlank()) {
                val firstLine = initialSharedText.lineSequence().firstOrNull { it.isNotBlank() }?.take(48)?.trim() ?: "Shared Article"
                title = firstLine
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            processSelectedUri(uri)
        } else if (selectedFilePath == null) {
            // Simulate a chosen file when tapped in environments without an external file selection
            simulateChosenFile(detectedFileType)
        }
    }

    val hasChosenFileOrText = selectedFilePath != null || articleTextContent.isNotBlank()
    val isReady = !isProcessingFile && !isSavingDocument && hasChosenFileOrText && title.isNotBlank()

    val completeSaveAction = { openImmediately: Boolean ->
        if (isProcessingFile || isSavingDocument) {
            validationMessage = "Please wait while the file finishes loading."
        } else if (!hasChosenFileOrText && title.isBlank()) {
            validationMessage = "Choose a file and enter a title before adding to files."
        } else if (!hasChosenFileOrText) {
            validationMessage = "Choose a file first before adding to files."
        } else if (title.isBlank()) {
            validationMessage = "Enter a publication title before adding to files."
        } else {
            validationMessage = null
            isSavingDocument = true
            val finalTitle = title.trim().ifBlank { selectedFileName?.substringBeforeLast(".") ?: "Untitled" }
            val finalAuthor = author.trim().ifBlank { "Unknown Author" }
            val currentCategory = selectedCategory.name
            val currentFileType = detectedFileType
            val currentFilePath = selectedFilePath
            val currentCoverUri = selectedCoverUri
            val currentPages = detectedPages
            val currentArticleText = articleTextContent

            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                var finalPath = currentFilePath ?: ""
                var finalCoverUri = currentCoverUri
                var finalPages = currentPages
                var finalFileType = currentFileType

                if (currentFilePath == null && currentArticleText.isNotBlank()) {
                    val savedFile = StorageManager.saveTextArticle(
                        context = context,
                        documentTitle = finalTitle,
                        content = currentArticleText,
                        category = currentCategory,
                        customPath = customStoragePath,
                        storageType = storageLocationType
                    )
                    if (savedFile != null) {
                        finalPath = savedFile.absolutePath
                        finalFileType = "TXT"
                        val lines = currentArticleText.lines().filter { it.isNotBlank() }
                        finalPages = (lines.size / 3).coerceAtLeast(1)
                        val coverBmp = EpubParser.generateBookCoverBitmap(finalTitle, finalAuthor)
                        val coverFile = StorageManager.saveCoverImage(
                            context = context,
                            category = currentCategory,
                            documentTitle = finalTitle,
                            bitmap = coverBmp,
                            customPath = customStoragePath,
                            storageType = storageLocationType
                        )
                        finalCoverUri = coverFile?.absolutePath
                    }
                } else if (finalPath.isNotBlank() && !finalPath.startsWith("simulated://")) {
                    val existingFile = File(finalPath)
                    val oldParentFolder = existingFile.parentFile
                    val targetFolder = StorageManager.getDocumentFolder(
                        context = context,
                        category = currentCategory,
                        documentTitle = finalTitle,
                        customPath = customStoragePath,
                        storageType = storageLocationType
                    )
                    if (existingFile.exists() && oldParentFolder?.absolutePath != targetFolder.absolutePath) {
                        val targetFile = File(targetFolder, existingFile.name)
                        try {
                            if (targetFile.exists()) targetFile.delete()
                            val moved = existingFile.renameTo(targetFile)
                            if (!moved) {
                                existingFile.copyTo(targetFile, overwrite = true, bufferSize = 256 * 1024)
                                existingFile.delete()
                            }
                            if (targetFile.exists() && targetFile.length() > 0L) {
                                finalPath = targetFile.absolutePath
                            }
                            if (!finalCoverUri.isNullOrBlank()) {
                                val oldCoverFile = File(finalCoverUri)
                                if (oldCoverFile.exists() && oldCoverFile.parentFile?.absolutePath == oldParentFolder?.absolutePath) {
                                    val movedCover = File(targetFolder, oldCoverFile.name)
                                    oldCoverFile.copyTo(movedCover, overwrite = true)
                                    if (movedCover.exists() && movedCover.length() > 0L) {
                                        finalCoverUri = movedCover.absolutePath
                                    }
                                }
                            }
                            if (oldParentFolder != null && oldParentFolder.name != "Books" && oldParentFolder.name != "Articles") {
                                oldParentFolder.deleteRecursively()
                            }
                        } catch (_: Throwable) {}
                    }
                    if (finalFileType == "DAISY" && finalPath.isNotBlank()) {
                        com.example.reader.DaisyIndexingService.startBackgroundIndexing(
                            context = context,
                            filePath = finalPath,
                            fallbackTitle = finalTitle,
                            fallbackAuthor = finalAuthor
                        )
                    }
                }

                StorageManager.saveHighlightMarkdown(
                    context = context,
                    category = currentCategory,
                    documentTitle = finalTitle,
                    highlights = emptyList(),
                    author = finalAuthor,
                    customPath = customStoragePath,
                    storageType = storageLocationType
                )

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isSavingDocument = false
                    onAddDocument(
                        finalTitle,
                        finalAuthor,
                        currentCategory,
                        finalFileType,
                        finalPath,
                        null,
                        finalPages,
                        finalCoverUri,
                        openImmediately
                    )
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DisableDialogDimming()

        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(Color.White, RectangleShape)
                .border(2.dp, Color.Black, RectangleShape)
                .testTag("add_or_share_document_dialog")
        ) {
            // Serif header band closed off by a 2px rule
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (isSharedImport) "Save to Marginalia Lectoris?" else "Add new file",
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.Black
                    )
                )
                if (isSharedImport) {
                    Text(
                        text = "Do you want to save this shared document to your Marginalia Lectoris library?",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF18181B)
                    )
                }
            }

            // 2px ink rule closing off the serif header band
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.Black)
            )

            // Body content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Book or Article joined segmented control
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Is this document a Book or an Article?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, Color.Black, RectangleShape)
                    ) {
                        SegmentedCategoryOption(
                            category = DocumentCategory.BOOK,
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            isSelected = selectedCategory == DocumentCategory.BOOK,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedCategory = DocumentCategory.BOOK }
                        )

                        // 2px vertical divider between joined buttons
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(46.dp)
                                .background(Color.Black)
                        )

                        SegmentedCategoryOption(
                            category = DocumentCategory.ARTICLE,
                            icon = Icons.Default.Article,
                            isSelected = selectedCategory == DocumentCategory.ARTICLE,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedCategory = DocumentCategory.ARTICLE }
                        )
                    }
                }

                // File picker row with text + format badges row (EPUB, PDF, DAISY, MD, HTML, plus plain TXT)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color.Black, RectangleShape)
                        .background(Color.White, RectangleShape)
                        .clickable(enabled = !isProcessingFile && !isSavingDocument) {
                            simulateChosenFile(detectedFileType)
                            try {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/pdf",
                                        "text/markdown",
                                        "text/html",
                                        "application/xhtml+xml",
                                        "text/plain",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            } catch (_: Throwable) {}
                        }
                        .padding(14.dp)
                        .testTag("choose_file_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isProcessingFile || isSavingDocument) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black
                                )
                            } else {
                                Icon(
                                    imageVector = if (selectedFilePath != null) Icons.Default.Check else Icons.Default.FileUpload,
                                    contentDescription = "Upload",
                                    tint = Color.Black,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        isProcessingFile -> "Loading ${selectedFileName ?: "selected file"}…"
                                        selectedFileName != null -> "Chosen: ${selectedFileName!!}"
                                        else -> "Select EPUB, PDF, DAISY (.zip), MD, HTML, or TXT"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                Text(
                                    text = when {
                                        isProcessingFile -> "Extracting metadata and pages in background…"
                                        fileLoadErrorMessage != null -> fileLoadErrorMessage!!
                                        selectedFilePath != null -> "$detectedFileType format • $detectedPages pages ready"
                                        else -> "Tap to choose file (.epub, .pdf, .zip DAISY, .md, .html, .txt)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (fileLoadErrorMessage != null) Color(0xFFB91C1C) else Color(0xFF3F3F46)
                                )
                            }
                        }

                        // Row of format badges: EPUB, PDF, DAISY, MD, HTML in Kaleido fills + plain TXT badge
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val formats = listOf("EPUB", "PDF", "DAISY", "MD", "HTML", "TXT")
                            formats.forEach { fmt ->
                                val isPlainTxt = fmt == "TXT"
                                val swatch = kaleidoSwatchForFileType(fmt)
                                val badgeFill = if (isPlainTxt) Color.White else swatch.fill
                                val isSelectedFormat = selectedFilePath != null && detectedFileType.equals(fmt, ignoreCase = true)

                                Box(
                                    modifier = Modifier
                                        .background(badgeFill, RectangleShape)
                                        .border(
                                            width = if (isSelectedFormat) 2.dp else 1.dp,
                                            color = Color.Black,
                                            shape = RectangleShape
                                        )
                                        .clickable(enabled = !isProcessingFile && !isSavingDocument) {
                                            simulateChosenFile(fmt)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("format_badge_${fmt.lowercase()}")
                                ) {
                                    Text(
                                        text = if (isSelectedFormat) "✓ $fmt" else fmt,
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Title input: square 2px input with small monospaced label above
                SquareInkTextField(
                    label = "TITLE (SOURCE DOCUMENT)",
                    value = title,
                    onValueChange = {
                        title = it
                        if (validationMessage != null && it.isNotBlank() && hasChosenFileOrText) {
                            validationMessage = null
                        }
                    },
                    placeholder = "Enter publication title",
                    testTag = "document_title_input"
                )

                // Author input: square 2px input with small monospaced label above and author hint beneath
                SquareInkTextField(
                    label = "AUTHOR(S)",
                    value = author,
                    onValueChange = { author = it },
                    placeholder = "e.g. Margaret Diane LeCompte, Jean J. Schensul",
                    hintBeneath = "Separate multiple authors with commas or 'and'",
                    testTag = "document_author_input"
                )

                // Inline feedback message when tapping "Add to files" early while incomplete
                if (validationMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, Color.Black, RectangleShape)
                            .background(Color(0xFFF7F7F5), RectangleShape)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("add_document_validation_message")
                    ) {
                        Text(
                            text = validationMessage!!,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                color = Color.Black
                            )
                        )
                    }
                }

                // Footer buttons: Cancel is outlined; "Add to files" starts as dashed outline and becomes solid black when ready
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Outlined Cancel button
                    Box(
                        modifier = Modifier
                            .border(2.dp, Color.Black, RectangleShape)
                            .background(Color.White, RectangleShape)
                            .clickable { onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("cancel_add_document_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                    }

                    if (isSharedImport) {
                        Box(
                            modifier = Modifier
                                .border(2.dp, Color.Black, RectangleShape)
                                .background(Color.White, RectangleShape)
                                .clickable { completeSaveAction(false) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("save_shared_to_library_only_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Save Only",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                        }
                    }

                    // "Add to files": dashed outline when incomplete (tappable to show inline message), solid black when ready
                    Box(
                        modifier = Modifier
                            .then(
                                if (isReady) {
                                    Modifier
                                        .background(Color.Black, RectangleShape)
                                        .border(2.dp, Color.Black, RectangleShape)
                                } else {
                                    Modifier
                                        .background(Color.White, RectangleShape)
                                        .drawBehind {
                                            val strokeWidthPx = 2.dp.toPx()
                                            val dashPx = 6.dp.toPx()
                                            val gapPx = 4.dp.toPx()
                                            drawRect(
                                                color = Color.Black,
                                                topLeft = Offset(strokeWidthPx / 2f, strokeWidthPx / 2f),
                                                size = androidx.compose.ui.geometry.Size(
                                                    size.width - strokeWidthPx,
                                                    size.height - strokeWidthPx
                                                ),
                                                style = Stroke(
                                                    width = strokeWidthPx,
                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
                                                )
                                            )
                                        }
                                }
                            )
                            .clickable { completeSaveAction(true) }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .testTag("confirm_add_document_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                isProcessingFile -> "Loading…"
                                isSavingDocument -> "Opening…"
                                isSharedImport -> "Save to Marginalia Lectoris"
                                else -> "Add to files"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isReady) Color.White else Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedCategoryOption(
    category: DocumentCategory,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Both Book and Article use muted Kaleido green + check mark when selected
    Box(
        modifier = modifier
            .height(46.dp)
            .background(if (isSelected) KaleidoSageFill else Color.White, RectangleShape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp)
            .testTag("category_card_${category.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = KaleidoSageInk,
                    modifier = Modifier.size(16.dp)
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = category.displayName,
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun SquareInkTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    hintBeneath: String? = null,
    testTag: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                color = Color.Black
            )
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color.Black
            ),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
                .background(Color.White, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag(testTag),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                fontFamily = FontFamily.Default,
                                fontSize = 14.sp,
                                color = Color(0xFF71717A)
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (!hintBeneath.isNullOrBlank()) {
            Text(
                text = hintBeneath,
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    color = Color(0xFF18181B)
                )
            )
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = it.getString(index)
                    }
                }
            }
        } catch (_: Throwable) {}
    }
    if (result.isNullOrBlank()) {
        val rawPath = try { java.net.URLDecoder.decode(uri.path ?: uri.toString(), "UTF-8") } catch (_: Throwable) { uri.path ?: "" }
        val cut = rawPath.lastIndexOf('/')
        result = if (cut != -1) rawPath.substring(cut + 1) else rawPath
    }
    return result?.takeIf { it.isNotBlank() }
}
