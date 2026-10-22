package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.model.parseHighlightColor
import com.example.reader.MarkdownExporter
import com.example.ui.theme.KaleidoCobaltInk
import com.example.ui.theme.KaleidoPlumBorder
import com.example.ui.theme.KaleidoPlumFill
import com.example.ui.theme.KaleidoPlumInk
import com.example.ui.theme.kaleidoSwatchForTag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsListScreen(
    highlights: List<HighlightEntity>,
    documents: List<DocumentEntity>,
    settings: SettingsEntity,
    onNavigateBack: () -> Unit,
    onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit,
    onUpdateHighlight: (HighlightEntity) -> Unit = {},
    onDeleteHighlight: (HighlightEntity) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var highlightToEdit by remember { mutableStateOf<HighlightEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    val filteredHighlights = remember(highlights, searchQuery) {
        if (searchQuery.isBlank()) {
            highlights
        } else {
            highlights.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.highlightedText.contains(searchQuery, ignoreCase = true) ||
                it.userNotes.contains(searchQuery, ignoreCase = true) ||
                it.tags.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color.White)) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Highlights & Notes (${highlights.size})",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.Black
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White
                    )
                )
                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
            }
        },
        containerColor = Color.White
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .testTag("highlights_search_input"),
                placeholder = { Text("Search notes...", fontSize = 14.sp, color = Color(0xFF3F3F46), fontFamily = FontFamily.Monospace) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Black) },
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Black,
                    unfocusedBorderColor = Color.Black,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    cursorColor = Color.Black
                )
            )

            // Obsidian Vault & Markdown Export Hub
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val sb = StringBuilder()
                        sb.appendLine("---")
                        sb.appendLine("title: \"KOReader Library Highlights\"")
                        sb.appendLine("total_highlights: ${highlights.size}")
                        sb.appendLine("source: \"KOReader\"")
                        sb.appendLine("tags:")
                        sb.appendLine("  - koreader/all-highlights")
                        sb.appendLine("  - literature-notes")
                        sb.appendLine("---")
                        sb.appendLine()
                        sb.appendLine("# 📚 KOReader Library Highlights")
                        sb.appendLine()
                        highlights.forEach { h ->
                            val doc = documents.firstOrNull { it.id == h.documentId }
                            val docTitle = doc?.title ?: "Document"
                            val isPdf = doc?.fileType?.equals("PDF", ignoreCase = true) == true || h.printedPageLabel.isNotBlank()
                            val pageDisp = MarkdownExporter.formatHighlightPageDisplay(h, isPdf)
                            sb.appendLine("## [[$docTitle]] — $pageDisp")
                            sb.appendLine("> [!quote] ${h.title}")
                            sb.appendLine("> \"${h.highlightedText.trim().replace("\n", "\n> ")}\"")
                            sb.appendLine(">")
                            sb.appendLine("> — *$pageDisp* • [[$docTitle]]")
                            sb.appendLine()
                            if (h.userNotes.isNotBlank()) {
                                sb.appendLine("> [!note] Literature Reflection")
                                sb.appendLine("> ${h.userNotes.trim().replace("\n", "\n> ")}")
                                sb.appendLine()
                            }
                            sb.appendLine("---")
                            sb.appendLine()
                        }
                        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                        val file = File(exportDir, "KOReader-All-Highlights.md")
                        file.writeText(sb.toString(), Charsets.UTF_8)
                        MarkdownExporter.shareFile(context, file, "text/markdown", "Export All Highlights to Obsidian")
                    },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(38.dp),
                    shape = RoundedCornerShape(0.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    border = BorderStroke(1.5.dp, Color.Black),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = KaleidoPlumFill, contentColor = KaleidoPlumInk)
                ) {
                    Text("⚡ Export to Obsidian", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = KaleidoPlumInk)
                }

                OutlinedButton(
                    onClick = {
                        val sb = StringBuilder()
                        highlights.forEach { h ->
                            val doc = documents.firstOrNull { it.id == h.documentId }
                            val docTitle = doc?.title ?: "Document"
                            val isPdf = doc?.fileType?.equals("PDF", ignoreCase = true) == true || h.printedPageLabel.isNotBlank()
                            val pageDisp = MarkdownExporter.formatHighlightPageDisplay(h, isPdf)
                            sb.appendLine("### [[$docTitle]] ($pageDisp)")
                            sb.appendLine("> [!quote]")
                            sb.appendLine("> \"${h.highlightedText.trim().replace("\n", "\n> ")}\"")
                            if (h.userNotes.isNotBlank()) {
                                sb.appendLine("> [!note] Note: ${h.userNotes}")
                            }
                            sb.appendLine()
                        }
                        MarkdownExporter.copyToClipboard(context, sb.toString(), "Obsidian Highlights")
                        Toast.makeText(context, "Copied ${highlights.size} highlights in Obsidian format!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(0.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    border = BorderStroke(1.5.dp, Color.Black)
                ) {
                    Text("📋 Copy All MD", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }

            if (filteredHighlights.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FormatQuote,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No highlights yet" else "No matching notes found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = "Highlights you make while reading will appear here as personal literature notes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF71717A),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredHighlights, key = { it.id }) { highlight ->
                        val doc = documents.firstOrNull { it.id == highlight.documentId }
                        HighlightCardItem(
                            highlight = highlight,
                            document = doc,
                            settings = settings,
                            dateFormat = dateFormat,
                            onCardClick = {
                                if (doc != null) {
                                    onOpenDocumentAtPage(doc, highlight.pageNumber, highlight)
                                }
                            },
                            onEdit = { highlightToEdit = highlight },
                            onDelete = { onDeleteHighlight(highlight) },
                            onShare = {
                                if (doc != null) {
                                    val file = MarkdownExporter.createSingleHighlightFile(context, highlight, doc, settings)
                                    MarkdownExporter.shareFile(context, file, "text/markdown", "Share Note")
                                }
                            },
                            onCopy = {
                                if (doc != null) {
                                    val md = MarkdownExporter.generateHighlightMarkdown(highlight, doc, settings)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Markdown Note", md))
                                    Toast.makeText(context, "Note Markdown copied!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    val editingHighlight = highlightToEdit
    if (editingHighlight != null) {
        val doc = documents.firstOrNull { it.id == editingHighlight.documentId }
        CreateHighlightDialog(
            initialTitle = editingHighlight.title,
            initialQuote = editingHighlight.highlightedText,
            initialNotes = editingHighlight.userNotes,
            initialPage = editingHighlight.pageNumber,
            initialPrintedPageLabel = editingHighlight.printedPageLabel,
            initialChapter = editingHighlight.chapterTitle,
            initialParagraphCitation = editingHighlight.paragraphCitation,
            initialPageEquivalent = editingHighlight.pageEquivalent,
            initialEpubCfi = editingHighlight.epubCfi,
            initialColorHex = editingHighlight.colorHex,
            initialTags = editingHighlight.tags,
            documentTitle = doc?.title ?: "Document",
            documentPath = doc?.filePath.orEmpty(),
            isPdfDocument = doc?.fileType?.equals("PDF", ignoreCase = true) == true || editingHighlight.printedPageLabel.isNotBlank(),
            dialogTitle = "Edit Highlight & Note",
            saveButtonLabel = "Save Changes",
            onDismiss = { highlightToEdit = null },
            onSave = { title, quote, notes, page, printedPage, chapter, colorHex, tags, paragraphCitation, pageEquivalent, epubCfi ->
                onUpdateHighlight(
                    editingHighlight.copy(
                        title = title.trim().ifBlank { "Note - Page $page" },
                        highlightedText = quote.trim(),
                        userNotes = notes.trim(),
                        pageNumber = page,
                        printedPageLabel = printedPage.trim(),
                        chapterTitle = chapter.trim(),
                        paragraphCitation = paragraphCitation.trim(),
                        pageEquivalent = pageEquivalent.trim(),
                        epubCfi = epubCfi.trim(),
                        colorHex = colorHex,
                        tags = tags
                    )
                )
                highlightToEdit = null
                Toast.makeText(context, "Highlight updated!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun HighlightCardItem(
    highlight: HighlightEntity,
    document: DocumentEntity?,
    settings: SettingsEntity,
    dateFormat: SimpleDateFormat,
    onCardClick: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    val highlightColor = Color(parseHighlightColor(highlight.colorHex))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color.Black)
            .background(Color.White)
            .clickable { onCardClick() }
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(highlightColor)
                            .border(1.dp, Color.Black)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${MarkdownExporter.sanitizeFilename(highlight.title)}.md",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("list_edit_highlight_${highlight.id}")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Highlight", tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Markdown", tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share MD", tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Highlight Quote Box with Color E-Ink highlighter tint & crisp border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Color.Black)
                    .background(highlightColor.copy(alpha = 0.35f))
                    .padding(10.dp)
            ) {
                Text(
                    text = "“${highlight.highlightedText}”",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.Black,
                    lineHeight = 20.sp
                )
            }

            if (highlight.userNotes.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = highlight.userNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF18181B)
                )
            }

            val noteTags = com.example.reader.NoteSuggestionEngine.parseTagsList(highlight.tags)
            if (noteTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    noteTags.take(5).forEach { tag ->
                        val swatch = kaleidoSwatchForTag(tag)
                        androidx.compose.material3.Surface(
                            color = swatch.fill,
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, swatch.border)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                fontWeight = FontWeight.Normal,
                                color = swatch.ink,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = Color(0xFFD4D4D8))
            Spacer(modifier = Modifier.height(6.dp))

            // Metadata footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isPdf = document?.fileType?.equals("PDF", ignoreCase = true) == true || highlight.printedPageLabel.isNotBlank()
                val pageDisplay = MarkdownExporter.formatHighlightPageDisplay(highlight, isPdf)
                Text(
                    text = "${document?.title ?: "Document"} • $pageDisplay",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF18181B),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateFormat.format(Date(highlight.createdTimestamp)),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF18181B)
                )
            }
        }
    }
}
