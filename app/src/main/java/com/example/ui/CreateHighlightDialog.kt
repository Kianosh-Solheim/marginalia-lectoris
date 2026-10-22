package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HighlightPalette
import com.example.reader.NoteAiAssistant
import com.example.ui.theme.DisableDialogDimming
import com.example.ui.theme.KaleidoAmberBorder
import com.example.ui.theme.KaleidoAmberFill
import com.example.ui.theme.KaleidoAmberInk
import com.example.ui.theme.KaleidoCobaltBorder
import com.example.ui.theme.KaleidoCobaltFill
import com.example.ui.theme.KaleidoCobaltInk
import com.example.ui.theme.KaleidoPlumBorder
import com.example.ui.theme.KaleidoPlumFill
import com.example.ui.theme.KaleidoPlumInk
import com.example.ui.theme.kaleidoSwatchForTag
import kotlinx.coroutines.launch

@Composable
fun CreateHighlightDialog(
    initialTitle: String? = null,
    initialQuote: String,
    initialNotes: String = "",
    initialPage: Int,
    initialPrintedPageLabel: String = "",
    initialChapter: String,
    initialParagraphCitation: String = "",
    initialPageEquivalent: String = "",
    initialEpubCfi: String = "",
    initialColorHex: String = "#FFEB3B",
    initialTags: String = "",
    documentTitle: String,
    documentPath: String = LocalBookPath.current,
    isPdfDocument: Boolean = false,
    dialogTitle: String = "New Literature Note",
    saveButtonLabel: String = "Save Note",
    onDismiss: () -> Unit,
    onSave: (title: String, quote: String, notes: String, page: Int, printedPageLabel: String, chapter: String, colorHex: String, tags: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit
) {
    var quote by remember(initialQuote) { mutableStateOf(initialQuote) }
    var notes by remember(initialNotes) { mutableStateOf(initialNotes) }
    var pageNumber by remember(initialPage) { mutableIntStateOf(initialPage) }
    var printedPageLabel by remember(initialPrintedPageLabel) { mutableStateOf(initialPrintedPageLabel) }
    var chapter by remember(initialChapter) { mutableStateOf(initialChapter) }
    var paragraphCitation by remember(initialParagraphCitation, initialChapter) {
        mutableStateOf(
            initialParagraphCitation.ifBlank {
                if (initialChapter.isNotBlank()) "${initialChapter.trim()}, ¶ 1" else "Chapter 1, ¶ 1"
            }
        )
    }
    var pageEquivalent by remember(initialPageEquivalent) { mutableStateOf(initialPageEquivalent) }
    var epubCfi by remember(initialEpubCfi, initialQuote, initialChapter, initialParagraphCitation) {
        mutableStateOf(
            if (initialEpubCfi.isNotBlank()) {
                com.example.reader.EpubCfiEngine.normalizeCfi(initialEpubCfi, includeHashPrefix = false)
            } else if (!isPdfDocument && initialQuote.isNotBlank()) {
                com.example.reader.EpubCfiEngine.generateCfiForBookSelection(
                    epubBook = null,
                    quote = initialQuote,
                    fallbackChapterTitle = initialChapter,
                    fallbackParagraphCitation = initialParagraphCitation
                )
            } else ""
        )
    }

    // Offline, non-AI suggestions (no network, no API key) so every note gets a distinct, meaningful title.
    val suggestedTitles = remember(quote, notes, chapter, pageNumber) {
        com.example.reader.NoteSuggestionEngine.suggestTitles(
            highlightedText = quote,
            userNotes = notes,
            chapterTitle = chapter,
            pageNumber = pageNumber
        )
    }
    var title by remember(initialTitle, initialQuote, initialPage) {
        val suggested = if (!initialTitle.isNullOrBlank()) {
            initialTitle
        } else {
            com.example.reader.NoteSuggestionEngine.suggestTitles(
                highlightedText = initialQuote,
                userNotes = initialNotes,
                chapterTitle = initialChapter,
                pageNumber = initialPage
            ).firstOrNull() ?: "Note - Page $initialPage"
        }
        mutableStateOf(suggested)
    }

    var activeTags by remember(initialTags) {
        mutableStateOf(com.example.reader.NoteSuggestionEngine.parseTagsList(initialTags))
    }
    var customTagInput by remember { mutableStateOf("") }

    val suggestedTags = remember(quote, notes, chapter, documentTitle, activeTags) {
        com.example.reader.NoteSuggestionEngine.suggestTags(
            highlightedText = quote,
            userNotes = notes,
            chapterTitle = chapter,
            documentTitle = documentTitle,
            existingTags = activeTags
        )
    }

    val bookLanguage = rememberBookLanguage(documentPath, initialQuote)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isGeneratingAiTitle by remember { mutableStateOf(false) }
    var aiSuggestedTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var aiTitleLanguage by remember { mutableStateOf<String?>(null) }

    var isGeneratingAiTags by remember { mutableStateOf(false) }
    var aiSuggestedTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var aiTagsLanguage by remember { mutableStateOf<String?>(null) }

    var selectedColor by remember(initialColorHex) {
        mutableStateOf(
            HighlightPalette.firstOrNull { it.hex.equals(initialColorHex, ignoreCase = true) }
                ?: HighlightPalette.first()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(2.dp, Color.Black),
        title = {
            DisableDialogDimming()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = dialogTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                if (isPdfDocument || printedPageLabel.isNotBlank()) {
                    val (pdfRangeStr, cleanPrinted) = com.example.reader.PdfToEbookParser.parseStoredPageLabels(
                        primaryPdfPage = pageNumber,
                        rawPrintedPageLabel = printedPageLabel
                    )
                    val isMultiPdf = pdfRangeStr.contains("–") || pdfRangeStr.contains("-")
                    val isMultiPrinted = cleanPrinted.contains("–") || cleanPrinted.contains("-")
                    val pdfSummaryPart = if (isMultiPdf) "PDF pages $pdfRangeStr" else "PDF page number $pdfRangeStr"
                    val printedSummaryPart = if (isMultiPrinted) "printed pages $cleanPrinted" else "printed page number $cleanPrinted"
                    val pageSummary = if (cleanPrinted.isNotBlank()) {
                        "$pdfSummaryPart, $printedSummaryPart"
                    } else {
                        pdfSummaryPart
                    }
                    Text(
                        text = pageSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = KaleidoPlumInk,
                        modifier = Modifier.testTag("dialog_pdf_printed_page_summary")
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Note Title (Filename) Row with AI Spark Button beside it
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Note Title (Filename: [title].md)") },
                        supportingText = {
                            val langBadge = aiTitleLanguage?.let { " • AI ($it)" } ?: ""
                            Text(
                                text = "Becomes the filename in your PKM vault$langBadge",
                                color = Color(0xFF18181B)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("highlight_title_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color.Black,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedLabelColor = Color.Black
                        )
                    )

                    val isOnline = NoteAiAssistant.isInternetAvailable(context)
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = if (isOnline) KaleidoAmberFill else Color.White,
                        border = BorderStroke(
                            1.5.dp,
                            if (isOnline) Color.Black else Color(0xFFD4D4D8)
                        ),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(48.dp)
                            .clickable(enabled = !isGeneratingAiTitle) {
                                if (!NoteAiAssistant.isInternetAvailable(context)) {
                                    Toast.makeText(
                                        context,
                                        "Internet connection required for AI title suggestion",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@clickable
                                }
                                isGeneratingAiTitle = true
                                coroutineScope.launch {
                                    val result = NoteAiAssistant.suggestNoteTitle(context = context, bookLanguage = bookLanguage,
                                        highlightedText = quote,
                                        userNotes = notes,
                                        chapterTitle = chapter,
                                        documentTitle = documentTitle,
                                        pageNumber = pageNumber
                                    )
                                    isGeneratingAiTitle = false
                                    result.onSuccess { aiResp ->
                                        if (aiResp.primaryTitle.isNotBlank()) {
                                            title = aiResp.primaryTitle
                                        }
                                        aiSuggestedTitles = (listOf(aiResp.primaryTitle) + aiResp.alternatives)
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                        aiTitleLanguage = aiResp.detectedLanguage
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            it.message ?: "Could not generate AI title",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            .testTag("ai_spark_title_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isGeneratingAiTitle) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = KaleidoAmberInk
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Suggest Summarizing Note Filename (English or Norwegian)",
                                    tint = if (isOnline) KaleidoAmberInk else Color(0xFF71717A),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // AI-Generated Title Alternatives (when AI Spark is clicked)
                AnimatedVisibility(visible = aiSuggestedTitles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF7C3AED),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "AI Summarizing Filename Suggestions (${aiTitleLanguage ?: "Auto"}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7C3AED)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            aiSuggestedTitles.forEachIndexed { idx, candidateTitle ->
                                val isCurrentTitle = title.trim().equals(candidateTitle, ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (isCurrentTitle) KaleidoAmberFill else Color.White,
                                    border = BorderStroke(
                                        if (isCurrentTitle) 1.5.dp else 1.dp,
                                        Color.Black
                                    ),
                                    modifier = Modifier
                                        .clickable { title = candidateTitle }
                                        .testTag("ai_suggested_title_chip_$idx")
                                ) {
                                    Text(
                                        text = candidateTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isCurrentTitle) FontWeight.Bold else FontWeight.SemiBold,
                                        color = Color.Black,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Suggested Titles based on Highlighted Text
                if (suggestedTitles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Suggested Titles (from highlighted text):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3F3F46)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            suggestedTitles.forEachIndexed { idx, candidateTitle ->
                                val isCurrentTitle = title.trim().equals(candidateTitle, ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (isCurrentTitle) KaleidoCobaltFill else Color.White,
                                    border = BorderStroke(if (isCurrentTitle) 1.5.dp else 1.dp, Color.Black),
                                    modifier = Modifier
                                        .clickable { title = candidateTitle }
                                        .testTag("suggested_title_chip_$idx")
                                ) {
                                    Text(
                                        text = candidateTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isCurrentTitle) FontWeight.Bold else FontWeight.Medium,
                                        color = Color.Black,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = quote,
                    onValueChange = { quote = it },
                    label = { Text("Highlighted Quote") },
                    placeholder = { Text("Quote excerpt...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("highlight_quote_input"),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedLabelColor = Color.Black
                    )
                )

                DictionaryButton(quote, bookLanguage)
                NoteAssistance(quote, bookLanguage) { addition ->
                    notes = if (notes.isBlank()) addition else notes.trimEnd() + "\n\n" + addition
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Personal PKM Reflections") },
                    placeholder = { Text("Your synthesis, critique, or reflections...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("highlight_notes_input"),
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedLabelColor = Color.Black
                    )
                )

                // Individual Note Tags & Suggested Tags Section
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Note Tags (${activeTags.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Exported only to this note (separate from book tags)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color(0xFF18181B)
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val isOnline = NoteAiAssistant.isInternetAvailable(context)
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (isOnline) KaleidoAmberFill else Color.White,
                                    border = BorderStroke(1.5.dp, if (isOnline) Color.Black else Color(0xFFD4D4D8)),
                                    modifier = Modifier
                                        .clickable(enabled = !isGeneratingAiTags) {
                                            if (!NoteAiAssistant.isInternetAvailable(context)) {
                                                Toast.makeText(
                                                    context,
                                                    "Internet connection required for AI tag suggestions",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@clickable
                                            }
                                            isGeneratingAiTags = true
                                            coroutineScope.launch {
                                                val result = NoteAiAssistant.suggestNoteTags(context = context, bookLanguage = bookLanguage,
                                                    highlightedText = quote,
                                                    userNotes = notes,
                                                    chapterTitle = chapter,
                                                    documentTitle = documentTitle,
                                                    existingTags = activeTags
                                                )
                                                isGeneratingAiTags = false
                                                result.onSuccess { aiResp ->
                                                    aiSuggestedTags = aiResp.tags.filterNot { candidate ->
                                                        activeTags.any { it.equals(candidate, ignoreCase = true) }
                                                    }
                                                    aiTagsLanguage = aiResp.detectedLanguage
                                                }.onFailure {
                                                    Toast.makeText(
                                                        context,
                                                        it.message ?: "Could not generate AI tags",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                        .testTag("ai_spark_note_tags_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (isGeneratingAiTags) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.8.dp,
                                                color = KaleidoAmberInk
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = "AI Suggest Note Tags",
                                                tint = if (isOnline) KaleidoAmberInk else Color(0xFF71717A),
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        Text(
                                            text = "AI Tags",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isOnline) KaleidoAmberInk else Color(0xFF71717A)
                                        )
                                    }
                                }
                            }
                        }

                        // Active Tags Row
                        if (activeTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeTags.forEach { tag ->
                                    val swatch = kaleidoSwatchForTag(tag)
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = swatch.fill,
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .clickable {
                                                activeTags = activeTags.filterNot { it.equals(tag, ignoreCase = true) }
                                            }
                                            .testTag("active_note_tag_$tag")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                                fontWeight = FontWeight.Normal,
                                                color = swatch.ink
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove tag $tag",
                                                tint = Color.Black,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No note-specific tags added yet. Tap AI Tags, suggested tags below, or type your own.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                        }

                        // AI-Suggested Note Tags Section (when AI Spark is clicked)
                        val remainingAiTags = aiSuggestedTags.filterNot { candidate ->
                            activeTags.any { it.equals(candidate, ignoreCase = true) }
                        }
                        AnimatedVisibility(visible = remainingAiTags.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color(0xFF7C3AED),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "AI Suggested Tags (${aiTagsLanguage ?: "Auto"}):",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF7C3AED)
                                        )
                                    }
                                    Text(
                                        text = "+ Add All AI",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF7C3AED),
                                        modifier = Modifier
                                            .clickable {
                                                activeTags = com.example.reader.NoteSuggestionEngine.parseTagsList(
                                                    (activeTags + remainingAiTags).joinToString(", ")
                                                )
                                            }
                                            .testTag("add_all_ai_note_tags_btn")
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    remainingAiTags.forEachIndexed { idx, aiTag ->
                                        Surface(
                                            shape = RoundedCornerShape(0.dp),
                                            color = KaleidoAmberFill,
                                            border = BorderStroke(1.dp, Color.Black),
                                            modifier = Modifier
                                                .clickable {
                                                    activeTags = com.example.reader.NoteSuggestionEngine.parseTagsList(
                                                        (activeTags + aiTag).joinToString(", ")
                                                    )
                                                }
                                                .testTag("ai_suggested_note_tag_chip_$idx")
                                        ) {
                                            Text(
                                                text = "+ #$aiTag",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Add Custom Tag Input Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = customTagInput,
                                onValueChange = { customTagInput = it },
                                placeholder = { Text("Add tag (e.g. epistemology, erkjennelse)...", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("custom_note_tag_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Black,
                                    unfocusedBorderColor = Color(0xFFD4D4D8),
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    val newTags = com.example.reader.NoteSuggestionEngine.parseTagsList(customTagInput)
                                    if (newTags.isNotEmpty()) {
                                        activeTags = com.example.reader.NoteSuggestionEngine.parseTagsList(
                                            (activeTags + newTags).joinToString(", ")
                                        )
                                        customTagInput = ""
                                    }
                                },
                                enabled = customTagInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("add_custom_note_tag_button")
                            ) {
                                Text("+ Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        PreviousTagsPicker(selected = activeTags, typed = customTagInput, forNotes = true) { picked ->
                            activeTags = com.example.reader.NoteSuggestionEngine.parseTagsList((activeTags + picked).joinToString(", "))
                            if (customTagInput.isNotBlank() && !customTagInput.contains(',')) customTagInput = ""
                        }

                        // Suggested Tags from Highlighted Text
                        if (suggestedTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Suggested Tags (from text):",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF52525B)
                                )
                                Text(
                                    text = "+ Add All Suggested",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier
                                        .clickable {
                                            val merged = com.example.reader.NoteSuggestionEngine.parseTagsList(
                                                (activeTags + suggestedTags.take(4)).joinToString(", ")
                                            )
                                            activeTags = merged
                                        }
                                        .testTag("add_all_suggested_tags_btn")
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                suggestedTags.forEachIndexed { idx, sugTag ->
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .clickable {
                                                activeTags = com.example.reader.NoteSuggestionEngine.parseTagsList(
                                                    (activeTags + sugTag).joinToString(", ")
                                                )
                                            }
                                            .testTag("suggested_tag_chip_$idx")
                                    ) {
                                        Text(
                                            text = "+ #$sugTag",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                            fontWeight = FontWeight.Normal,
                                            color = Color.Black,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "E-Ink Shading Style",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HighlightPalette.forEach { colorOption ->
                        val isSelected = selectedColor.hex.equals(colorOption.hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(colorOption.colorValue))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.Black else Color(0xFFD4D4D8),
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = colorOption }
                        )
                    }
                }

                if (isPdfDocument || printedPageLabel.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pageNumber.toString(),
                            onValueChange = { pageNumber = it.toIntOrNull() ?: pageNumber },
                            label = { Text("PDF Page #") },
                            modifier = Modifier
                                .weight(0.45f)
                                .testTag("highlight_pdf_page_input"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color(0xFFD4D4D8),
                                focusedLabelColor = Color.Black
                            )
                        )
                        OutlinedTextField(
                            value = printedPageLabel,
                            onValueChange = { printedPageLabel = it },
                            label = { Text("Printed Page #") },
                            placeholder = { Text("e.g. 3 or xiv") },
                            modifier = Modifier
                                .weight(0.55f)
                                .testTag("highlight_printed_page_input"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color(0xFFD4D4D8),
                                focusedLabelColor = Color.Black
                            )
                        )
                    }
                    OutlinedTextField(
                        value = chapter,
                        onValueChange = { chapter = it },
                        label = { Text("Chapter") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color(0xFFD4D4D8),
                            focusedLabelColor = Color.Black
                        )
                    )
                    OutlinedTextField(
                        value = paragraphCitation,
                        onValueChange = { paragraphCitation = it },
                        label = { Text("Chapter + Paragraph") },
                        placeholder = { Text("e.g. Chapter 1, ¶ 3") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("highlight_chapter_paragraph_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color(0xFFD4D4D8),
                            focusedLabelColor = Color.Black
                        )
                    )
                } else {
                    OutlinedTextField(
                        value = pageEquivalent,
                        onValueChange = { pageEquivalent = it },
                        label = { Text("Page Equivalent") },
                        placeholder = { Text("e.g. 42 or xiv (if present in EPUB Page List)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("highlight_page_equivalent_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color(0xFFD4D4D8),
                            focusedLabelColor = Color.Black
                        )
                    )
                    OutlinedTextField(
                        value = paragraphCitation,
                        onValueChange = { paragraphCitation = it },
                        label = { Text("Chapter + Paragraph") },
                        placeholder = { Text("e.g. Chapter 1: Foundations, ¶ 3") },
                        supportingText = { Text("Reflow-independent citation (Chapter + Paragraph)", color = Color(0xFF71717A)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("highlight_chapter_paragraph_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color(0xFFD4D4D8),
                            focusedLabelColor = Color.Black
                        )
                    )
                    OutlinedTextField(
                        value = epubCfi,
                        onValueChange = { epubCfi = it },
                        label = { Text("EPUB CFI (Canonical Fragment Identifier)") },
                        placeholder = { Text("e.g. epubcfi(/6/2!/4/2,/1:0,/1:42)") },
                        supportingText = { Text("Machine-readable W3C/IDPF EPUB CFI 1.1 selection locator", color = Color(0xFF71717A)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("highlight_epub_cfi_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color(0xFFD4D4D8),
                            focusedLabelColor = Color.Black
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pendingTags = if (customTagInput.isNotBlank()) {
                        activeTags + com.example.reader.NoteSuggestionEngine.parseTagsList(customTagInput)
                    } else {
                        activeTags
                    }
                    val finalTagsStr = com.example.reader.NoteSuggestionEngine.joinTagsList(pendingTags)
                    val resolvedParaCitation = paragraphCitation.trim().ifBlank {
                        if (chapter.isNotBlank()) "${chapter.trim()}, ¶ 1" else "Chapter 1, ¶ 1"
                    }
                    val resolvedEpubCfi = if (!isPdfDocument) {
                        epubCfi.trim().takeIf { com.example.reader.EpubCfiEngine.isValidEpubCfi(it) }
                            ?.let { com.example.reader.EpubCfiEngine.normalizeCfi(it, includeHashPrefix = false) }
                            ?: com.example.reader.EpubCfiEngine.generateCfiForBookSelection(
                                epubBook = null,
                                quote = quote,
                                fallbackChapterTitle = chapter,
                                fallbackParagraphCitation = resolvedParaCitation
                            )
                    } else ""
                    onSave(
                        title,
                        quote,
                        notes,
                        pageNumber,
                        printedPageLabel.trim(),
                        chapter,
                        selectedColor.hex,
                        finalTagsStr,
                        resolvedParaCitation,
                        pageEquivalent.trim(),
                        resolvedEpubCfi
                    )
                },
                enabled = title.isNotBlank() && quote.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.testTag("save_highlight_button")
            ) {
                Text(saveButtonLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Black)
            }
        }
    )
}
