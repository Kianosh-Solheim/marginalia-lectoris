package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.HighlightEntity
import com.example.model.parseHighlightColor
import com.example.reader.EpubInlineStyleType
import com.example.reader.EpubPage
import kotlinx.coroutines.delay

/**
 * PDF Reader using HorizontalPager with KOReader-style controls:
 * - Tap middle: toggles full-screen reading mode on/off
 * - Tap left 20%: previous page
 * - Tap right 20%: next page
 * - Swipe left/right: flips pages
 * - Physical volume buttons: flips pages
 * - Typography settings: font size, justification, margins, line spacing, font family
 */
data class ActiveSelectionToolbarState(
    val quote: String,
    val dictionaryContext: String = quote,
    val pageNumber: Int,
    val printedPageLabel: String = "",
    val paragraphCitation: String = "",
    val pageEquivalent: String = "",
    val epubCfi: String = "",
    val plainLocus: String = "",
    val isPdfDocument: Boolean = false,
    val chapterTitle: String,
    val isCurrentlyDragging: Boolean,
    val canAddNextParagraph: Boolean,
    val showWordAdjustmentControls: Boolean = true,
    val onAddWord: () -> Unit = {},
    val onRemoveWord: () -> Unit = {},
    val onAddNextParagraph: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit,
    val onSaveNoteWithDialog: (colorHex: String) -> Unit,
    val onQuickSaveHighlight: (colorHex: String) -> Unit,
    val onSpeakSelection: () -> Unit = {},
    val onAddAsAuthor: (String) -> Unit = {},
    val onSetAsAbstract: (String) -> Unit = {},
    val hasUnusualPdfTextWarning: Boolean = false,
    val onCompareOrOcrSelection: (() -> Unit)? = null,
    /** Offered when the selection reaches the end of the page: keep this part and select the rest on the next page. */
    val onContinueOnNextPage: (() -> Unit)? = null,
    /** Set while finishing a selection that began on an earlier page, e.g. "p. 15". */
    val continuedFrom: String = "",
    val onCancelContinuation: (() -> Unit)? = null
)

@Composable
internal fun SelectionActionToolbarCard(
    state: ActiveSelectionToolbarState,
    onAddAsAuthor: (String) -> Unit = state.onAddAsAuthor,
    onSetAsAbstract: (String) -> Unit = state.onSetAsAbstract,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var selectedColorHex by remember { mutableStateOf("#FFEB3B") }
    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    var showAskAi by remember { mutableStateOf(false) }
    val selectedColor = remember(selectedColorHex) {
        Color(parseHighlightColor(selectedColorHex))
    }

    val koreaderColors = remember {
        listOf(
            Triple("Yellow", Color(0xFFFFEB3B), "#FFEB3B"),
            Triple("Amber", Color(0xFFFFE082), "#FFE082"),
            Triple("Orange", Color(0xFFFF9800), "#FF9800"),
            Triple("Green", Color(0xFF81C784), "#81C784"),
            Triple("Blue", Color(0xFF64B5F6), "#64B5F6"),
            Triple("Purple", Color(0xFFBA68C8), "#BA68C8"),
            Triple("Coral", Color(0xFFEF5350), "#EF5350")
        )
    }

    val (pdfRangeStr, cleanPrinted) = com.example.reader.PdfToEbookParser.parseStoredPageLabels(
        primaryPdfPage = state.pageNumber,
        rawPrintedPageLabel = state.printedPageLabel
    )
    val isMultiPdf = pdfRangeStr.contains("–") || pdfRangeStr.contains("-")
    val isMultiPrinted = cleanPrinted.contains("–") || cleanPrinted.contains("-")
    val pdfPart = if (isMultiPdf) "PDF pages $pdfRangeStr" else "PDF page number $pdfRangeStr"
    val printedPart = if (isMultiPrinted) "printed pages $cleanPrinted" else "printed page number $cleanPrinted"

    val headerPageSubtitle = if (state.isPdfDocument) {
        if (cleanPrinted.isNotBlank()) {
            "$pdfPart, $printedPart"
        } else {
            pdfPart
        }
    } else {
        val parts = mutableListOf<String>()
        if (state.pageEquivalent.isNotBlank()) {
            val isMultiEq = state.pageEquivalent.contains("–") || state.pageEquivalent.contains("-")
            parts.add("page equivalent ${if (isMultiEq) "pp." else "p."} ${state.pageEquivalent}")
        } else if (cleanPrinted.isNotBlank()) {
            parts.add(printedPart)
        }
        if (state.paragraphCitation.isNotBlank()) {
            parts.add(state.paragraphCitation)
        } else if (state.chapterTitle.isNotBlank()) {
            parts.add("${state.chapterTitle.trim()}, ¶ 1")
        }
        parts.joinToString(" • ")
    }

    val resolvedLocus = state.plainLocus.ifBlank { headerPageSubtitle }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, Color.Black), RoundedCornerShape(0.dp))
            .testTag("selection_action_toolbar"),
        shape = RoundedCornerShape(0.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Top Row: Highlight Color Swatches Bar + Close Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F7F5))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    koreaderColors.forEach { (label, c, hex) ->
                        val isSelected = selectedColorHex.equals(hex, ignoreCase = true)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = Color.Black,
                                    shape = CircleShape
                                )
                                .clickable {
                                    if (isSelected) {
                                        state.onQuickSaveHighlight(hex)
                                    } else {
                                        selectedColorHex = hex
                                    }
                                }
                                .testTag("highlight_swatch_${label.lowercase()}")
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "$label selected",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = state.onClearSelection,
                    modifier = Modifier
                        .size(28.dp)
                        .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear Selection",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

            var showDictionary by remember { mutableStateOf(false) }
            if (showDictionary) {
                DictionaryLookupDialog(
                    passage = state.quote,
                    language = rememberBookLanguage(sample = state.dictionaryContext),
                    contextPassage = state.dictionaryContext,
                    dismiss = { showDictionary = false }
                )
            }

            // 2. Segmented Primary Action Row (Icon + Label cells in E-Ink Editorial Style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Action 1: Highlight (1-tap quick save in selected color)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(selectedColor.copy(alpha = 0.45f))
                        .clickable {
                            state.onQuickSaveHighlight(selectedColorHex)
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_quick_highlight_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Highlight",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Highlight",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action 2: Add Note (opens CreateHighlightDialog with selected color)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White)
                        .clickable {
                            state.onSaveNoteWithDialog(selectedColorHex)
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_save_note_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NoteAlt,
                        contentDescription = "Add Note",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Add Note",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action 3: Copy Quote
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White)
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Quote", state.quote)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied quote to clipboard", Toast.LENGTH_SHORT).show()
                            state.onClearSelection()
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_copy_quote_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Quote",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Copy",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action 4: Speak Selection (TTS / DAISY)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White)
                        .clickable {
                            state.onSpeakSelection()
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_speak_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Speak Selection",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Speak",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action: Dictionary (tablet dictionary, Ordbøkene / Wiktionary, or AI in context)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White)
                        .clickable { showDictionary = true }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_dictionary_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = "Look up in dictionary",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Define",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action: Ask AI — send the passage to ChatGPT, Claude, Gemini … to ask questions about it
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White)
                        .clickable { showAskAi = true }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_ask_ai_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Ask AI about this passage",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ask AI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action 5: Share Selection
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White)
                        .clickable {
                            val shareText = if (resolvedLocus.isNotBlank()) {
                                "“${state.quote}”\n— $resolvedLocus"
                            } else {
                                "“${state.quote}”"
                            }
                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Quote"))
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_share_quote_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Quote",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Share",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.Black)
                )

                // Action 6: More (reveals Add as Author & Set as Abstract/Summary)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isMoreMenuExpanded) Color(0xFFE4E4E7) else Color.White)
                        .clickable {
                            isMoreMenuExpanded = !isMoreMenuExpanded
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                        .testTag("selection_more_button"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isMoreMenuExpanded) Icons.Default.ExpandLess else Icons.Default.MoreHoriz,
                        contentDescription = "More",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "More",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                }
            }

            val readerDocument = LocalReaderDocument.current
            var showSetAs by remember { mutableStateOf(false) }
            var showCite by remember { mutableStateOf(false) }
            if (showSetAs && readerDocument != null) {
                SetAsDialog(selection = state.quote, document = readerDocument, onDismiss = { showSetAs = false },
                    pageNumber = state.pageNumber, chapterTitle = state.chapterTitle)
            }
            if (showAskAi) {
                AskAiDialog(quote = state.quote, locus = resolvedLocus, document = readerDocument, onDismiss = { showAskAi = false })
            }
            if (showCite && readerDocument != null) {
                CiteDialog(
                    document = readerDocument,
                    quote = state.quote,
                    page = com.example.reader.Citations.pageFor(
                        state.printedPageLabel, state.pageEquivalent, state.paragraphCitation,
                        state.pageNumber, state.isPdfDocument
                    ),
                    pageNumber = state.pageNumber,
                    chapterTitle = state.chapterTitle,
                    onDismiss = { showCite = false }
                )
            }

            if (isMoreMenuExpanded) {
                HorizontalDivider(thickness = 1.dp, color = Color.Black)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F7F5))
                        .testTag("selection_more_actions_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // More Action 1: Add as Author
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                val cleaned = DocumentMetadataHelper.cleanSelectedAuthorText(state.quote)
                                if (cleaned.isBlank()) {
                                    Toast.makeText(context, "Select text containing an author's name first", Toast.LENGTH_SHORT).show()
                                } else {
                                    isMoreMenuExpanded = false
                                    onAddAsAuthor(state.quote)
                                    state.onClearSelection()
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 10.dp)
                            .testTag("selection_add_as_author_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add as Author",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Add as Author",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(Color.Black)
                    )

                    // More Action 2: Set as Abstract/Summary
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                val cleaned = DocumentMetadataHelper.cleanSelectedAbstractText(state.quote)
                                if (cleaned.isBlank()) {
                                    Toast.makeText(context, "Select a passage of text first", Toast.LENGTH_SHORT).show()
                                } else {
                                    isMoreMenuExpanded = false
                                    onSetAsAbstract(state.quote)
                                    state.onClearSelection()
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 10.dp)
                            .testTag("selection_set_as_abstract_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Set as Abstract/Summary",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Set as Abstract/Summary",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (readerDocument != null) {
                    HorizontalDivider(thickness = 1.dp, color = Color.Black)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F7F5)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // More Action 3: Set as DOI / ISBN / year / publisher / journal …
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isMoreMenuExpanded = false; showSetAs = true }
                                .padding(vertical = 10.dp, horizontal = 10.dp)
                                .testTag("selection_set_as_field_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Set as… (DOI, ISBN, year…)",
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color.Black))
                        // More Action 4: Copy as citation
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isMoreMenuExpanded = false; showCite = true }
                                .padding(vertical = 10.dp, horizontal = 10.dp)
                                .testTag("selection_copy_citation_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Copy as citation",
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = Color.Black)

            // 3. Quote Preview & Citation / Reference Bar (matching HighlightsListScreen & App E-Paper Style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tinted Quote Box with crisp 1dp black border
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Black)
                        .background(selectedColor.copy(alpha = 0.30f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "“${state.quote}”",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic
                        ),
                        color = Color.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.hasUnusualPdfTextWarning && state.onCompareOrOcrSelection != null) {
                    Surface(
                        color = Color(0xFFD1B35C).copy(alpha = 0.35f),
                        shape = RoundedCornerShape(0.dp),
                        border = BorderStroke(1.dp, Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.onCompareOrOcrSelection.invoke() }
                            .testTag("selection_unusual_chars_warning_banner")
                    ) {
                        Text(
                            text = "⚠️ The selected text contains unusual characters. Compare it with OCR?",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                // Citation Locus + Reference / CFI / Wikilink Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (headerPageSubtitle.isNotBlank()) "Save selection ($headerPageSubtitle)" else "Save selection",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF18181B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("selection_toolbar_page_info")
                        )
                        if (!state.isPdfDocument && resolvedLocus.isNotBlank()) {
                            Text(
                                text = resolvedLocus,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = Color(0xFF3F3F46),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("epub_selection_locus_chip")
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        if (!state.isPdfDocument) {
                            OutlinedButton(
                                onClick = {
                                    val locusText = resolvedLocus.ifBlank { state.chapterTitle }
                                    clipboardManager.setText(AnnotatedString(locusText))
                                    Toast.makeText(context, "Copied: $locusText", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(26.dp)
                                    .testTag("copy_reference_toolbar_button")
                            ) {
                                Text(
                                    text = "Copy reference",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val cfiText = state.epubCfi.ifBlank { "epubcfi(/6/2!/4/2/1:0)" }
                                    clipboardManager.setText(AnnotatedString(cfiText))
                                    Toast.makeText(context, "Copied: $cfiText", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(26.dp)
                                    .testTag("copy_cfi_toolbar_button")
                            ) {
                                Text(
                                    text = "Copy CFI",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val wikilink = "[[${state.quote}]]"
                                val clip = ClipData.newPlainText("Obsidian Wikilink", wikilink)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied Obsidian Wikilink: $wikilink", Toast.LENGTH_SHORT).show()
                                state.onClearSelection()
                            },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                text = "[[Wikilink]]",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }

                // 4. Selection Range Adjustment Strip (for Classic EPUB & PDF custom word selection)
                if (state.showWordAdjustmentControls) {
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFFD4D4D8))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.onCompareOrOcrSelection != null) {
                            OutlinedButton(
                                onClick = { state.onCompareOrOcrSelection.invoke() },
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("selection_compare_ocr_button"),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFD1B35C).copy(alpha = 0.25f),
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Compare / OCR", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = state.onRemoveWord,
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                        ) {
                            Text("- Word", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = state.onAddWord,
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                        ) {
                            Text("+ Word", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }

                        if (state.canAddNextParagraph) {
                            OutlinedButton(
                                onClick = state.onAddNextParagraph,
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                            ) {
                                Text("+ Next Para", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = state.onSelectAll,
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(0.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                        ) {
                            Text("Select All", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 5. Selections across a page break: keep this part, finish on the next page (one note for both).
                if (state.continuedFrom.isNotBlank() || state.onContinueOnNextPage != null) {
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFFD4D4D8))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.continuedFrom.isNotBlank()) {
                            Text(
                                text = "Continues the selection from ${state.continuedFrom} — saved as one note",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = Color.Black, modifier = Modifier.weight(1f), maxLines = 2
                            )
                            state.onCancelContinuation?.let { cancel ->
                                OutlinedButton(
                                    onClick = cancel,
                                    modifier = Modifier.height(30.dp),
                                    shape = RoundedCornerShape(0.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                                ) { Text("Drop first part", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        state.onContinueOnNextPage?.let { cont ->
                            Button(
                                onClick = cont,
                                modifier = Modifier.height(30.dp).testTag("selection_continue_next_page"),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                            ) { Text("Continue on next page →", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

internal fun normalizeForQuoteSearch(text: String): String {
    return text
        .replace(Regex("^[…\\s.]+|[…\\s.]+$"), "")
        .replace(Regex("[\\s\\u00A0]+"), " ")
        .trim()
        .lowercase(java.util.Locale.ROOT)
}

/**
 * Finds the best matching page index (`0..pages.lastIndex`) for a [HighlightEntity] in a reflowable
 * EPUB/TXT/HTML book, even when the user has changed the font size, line spacing, or margins so that
 * the quote now appears on a completely different page or is split across a page boundary.
 */
internal fun findReflowPageIndexForHighlight(
    pages: List<EpubPage>,
    highlight: HighlightEntity
): Int {
    if (pages.isEmpty()) return 0
    val isFootnoteJumpBack = highlight.title.startsWith("Footnote Reference")
    val backlinkAnchor = if (isFootnoteJumpBack) highlight.userNotes.trim() else ""
    val sourceFileKey = if (isFootnoteJumpBack) highlight.paragraphCitation.trim() else ""

    // 0. If jumping back from a footnote, check exact anchor ID or fileKey + marker first in O(N) without string allocations!
    if (isFootnoteJumpBack && backlinkAnchor.isNotEmpty()) {
        val anchorIdx = pages.indexOfFirst { page ->
            !page.isCoverPage && page.anchorIds.any { it.equals(backlinkAnchor, ignoreCase = true) }
        }
        if (anchorIdx >= 0) return anchorIdx
    }

    // 0b. If the highlight has a canonical EPUB CFI (`epubcfi(...)`), resolve the page index directly via EpubCfiEngine!
    if (!isFootnoteJumpBack && highlight.epubCfi.isNotBlank()) {
        val cfiPageIdx = com.example.reader.EpubCfiEngine.resolveReflowPageIndexForCfi(
            pages = pages,
            rawCfi = highlight.epubCfi,
            fallbackQuote = highlight.highlightedText
        )
        if (cfiPageIdx != null && cfiPageIdx in pages.indices) {
            return cfiPageIdx
        }
    }

    val rawQuote = highlight.highlightedText.trim()
        .removePrefix("…")
        .removeSuffix("…")
        .removePrefix("...")
        .removeSuffix("...")
        .trim()
    val normQuote = normalizeForQuoteSearch(rawQuote)
    val normChapter = normalizeForQuoteSearch(highlight.chapterTitle)

    if (normQuote.isEmpty()) {
        if (normChapter.isNotEmpty()) {
            val chIdx = pages.indexOfFirst { !it.isCoverPage && normalizeForQuoteSearch(it.chapterTitle) == normChapter }
            if (chIdx >= 0) return chIdx
        }
        return (highlight.pageNumber - 1).coerceIn(0, pages.size - 1)
    }

    val firstSegment = rawQuote.lineSequence().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: rawQuote
    val normFirstSegment = normalizeForQuoteSearch(firstSegment)
    val quoteWords = normFirstSegment.split(" ").filter { it.isNotBlank() }
    // Skip partial first word if snippet was sliced mid-word
    val middleWords = if (quoteWords.size >= 6) {
        quoteWords.drop(1).take(5).joinToString(" ")
    } else {
        quoteWords.take(5).joinToString(" ")
    }
    val prefix4Words = quoteWords.take(5).joinToString(" ")
    val prefix2Words = quoteWords.take(3).joinToString(" ")

    // 1. Fast case-insensitive substring check on raw paragraphs before normalizing
    val rawProbe = if (firstSegment.length > 28) firstSegment.substring(4, 26).trim() else firstSegment
    if (rawProbe.length >= 8) {
        val fastIdx = pages.indexOfFirst { page ->
            !page.isCoverPage &&
                (!isFootnoteJumpBack || normChapter.isEmpty() || normalizeForQuoteSearch(page.chapterTitle) == normChapter) &&
                page.paragraphs.withIndex().any { (pIdx, para) ->
                    val sbType = page.semanticBlocks.getOrNull(pIdx)?.type
                    (!isFootnoteJumpBack || sbType != com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF) &&
                        para.contains(rawProbe, ignoreCase = true)
                }
        }
        if (fastIdx >= 0) return fastIdx
    }

    // 2. Pre-normalize each page's non-footnote text ONCE (never inside nested loops!)
    val pageNormTexts = Array(pages.size) { idx ->
        val page = pages[idx]
        if (page.isCoverPage) ""
        else {
            val sb = StringBuilder()
            for ((pIdx, para) in page.paragraphs.withIndex()) {
                if (isFootnoteJumpBack && page.semanticBlocks.getOrNull(pIdx)?.type == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF) {
                    continue
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(para)
            }
            normalizeForQuoteSearch(sb.toString())
        }
    }

    // 3. Match normQuote, normFirstSegment, or middleWords on pre-computed pageNormTexts (prioritizing matching chapter)
    if (normChapter.isNotEmpty()) {
        val sameChapterIdx = pages.indices.firstOrNull { idx ->
            val pNorm = pageNormTexts[idx]
            pNorm.isNotEmpty() &&
                normalizeForQuoteSearch(pages[idx].chapterTitle) == normChapter &&
                (pNorm.contains(normQuote) ||
                    (normFirstSegment.length >= 8 && pNorm.contains(normFirstSegment)) ||
                    (middleWords.length >= 8 && pNorm.contains(middleWords)))
        }
        if (sameChapterIdx != null) return sameChapterIdx
    }

    val singlePageIdx = pages.indices.firstOrNull { idx ->
        val pNorm = pageNormTexts[idx]
        pNorm.isNotEmpty() &&
            (pNorm.contains(normQuote) ||
                (normFirstSegment.length >= 8 && pNorm.contains(normFirstSegment)) ||
                (middleWords.length >= 8 && pNorm.contains(middleWords)))
    }
    if (singlePageIdx != null) return singlePageIdx

    // 4. Match across adjacent pages (`pageNormTexts[i]` + `pageNormTexts[i + 1]`) when a paragraph or quote was split across a page break!
    for (i in 0 until pages.size - 1) {
        val p1Norm = pageNormTexts[i]
        val p2Norm = pageNormTexts[i + 1]
        if (p1Norm.isEmpty()) continue
        val combinedTwoPages = "$p1Norm $p2Norm"
        if (combinedTwoPages.contains(normQuote) ||
            (normFirstSegment.length >= 8 && combinedTwoPages.contains(normFirstSegment)) ||
            (middleWords.length >= 8 && combinedTwoPages.contains(middleWords))
        ) {
            return if (prefix2Words.isNotEmpty() && p1Norm.contains(prefix2Words)) i else (i + 1)
        }
    }

    // 5. Match opening words (first 5 words, or first 3 words) prioritizing the same chapter
    if (prefix4Words.length >= 6) {
        val sameChapterIdx = pages.indices.firstOrNull { idx ->
            pageNormTexts[idx].isNotEmpty() &&
                (normChapter.isEmpty() || normalizeForQuoteSearch(pages[idx].chapterTitle) == normChapter) &&
                pageNormTexts[idx].contains(prefix4Words)
        }
        if (sameChapterIdx != null) return sameChapterIdx

        val anyChapterIdx = pages.indices.firstOrNull { idx ->
            pageNormTexts[idx].isNotEmpty() && pageNormTexts[idx].contains(prefix4Words)
        }
        if (anyChapterIdx != null) return anyChapterIdx
    }

    if (prefix2Words.length >= 5) {
        val fallbackPrefixIdx = pages.indices.firstOrNull { idx ->
            pageNormTexts[idx].isNotEmpty() &&
                (normChapter.isEmpty() || normalizeForQuoteSearch(pages[idx].chapterTitle) == normChapter) &&
                pageNormTexts[idx].contains(prefix2Words)
        }
        if (fallbackPrefixIdx != null) return fallbackPrefixIdx
    }

    if (sourceFileKey.isNotEmpty()) {
        val fileIdx = pages.indexOfFirst { !it.isCoverPage && it.fileKey.equals(sourceFileKey, ignoreCase = true) }
        if (fileIdx >= 0) return fileIdx
    }
    if (normChapter.isNotEmpty()) {
        val chIdx = pages.indexOfFirst { !it.isCoverPage && normalizeForQuoteSearch(it.chapterTitle) == normChapter }
        if (chIdx >= 0) return chIdx
    }

    return (highlight.pageNumber - 1).coerceIn(0, pages.size - 1)
}

internal fun findAllHighlightSpans(
    displayParagraph: String,
    highlights: List<HighlightEntity>,
    semanticBlock: com.example.reader.EpubSemanticBlock? = null,
    chapterTitle: String = ""
): List<Triple<Int, Int, HighlightEntity>> {
    if (displayParagraph.isEmpty() || highlights.isEmpty()) return emptyList()
    val results = mutableListOf<Triple<Int, Int, HighlightEntity>>()
    for (h in highlights) {
        val rawQuote = h.highlightedText.trim()
        if (rawQuote.isEmpty() && h.epubCfi.isBlank()) continue

        // 0. Canonical EPUB CFI 1.1 resolution against the paragraph / semantic block when present
        if (h.epubCfi.isNotBlank() && semanticBlock != null) {
            val cfiSpan = com.example.reader.EpubCfiEngine.resolveHighlightSpanInParagraph(
                displayParagraph = displayParagraph,
                chapterTitle = chapterTitle,
                semanticBlock = semanticBlock,
                highlight = h
            )
            if (cfiSpan != null && cfiSpan.second > cfiSpan.first) {
                results.add(Triple(cfiSpan.first, cfiSpan.second, h))
                continue
            }
        }

        if (rawQuote.isEmpty()) continue

        // 1. Exact case-insensitive substring match in displayParagraph
        val exactIdx = displayParagraph.indexOf(rawQuote, ignoreCase = true)
        if (exactIdx >= 0) {
            results.add(Triple(exactIdx, (exactIdx + rawQuote.length).coerceAtMost(displayParagraph.length), h))
            continue
        }

        // 2. Multi-paragraph highlight: check each paragraph segment of the highlight quote
        val quoteSegments = rawQuote.split(Regex("\\n+")).map { it.trim() }.filter { it.length >= 3 }
        var matchedSegment = false
        for (seg in quoteSegments) {
            val segIdx = displayParagraph.indexOf(seg, ignoreCase = true)
            if (segIdx >= 0) {
                results.add(Triple(segIdx, (segIdx + seg.length).coerceAtMost(displayParagraph.length), h))
                matchedSegment = true
            } else if (displayParagraph.length >= 12 && seg.contains(displayParagraph.trim(), ignoreCase = true)) {
                results.add(Triple(0, displayParagraph.length, h))
                matchedSegment = true
            }
        }
        if (matchedSegment) continue

        // 3. Partial / reflowed paragraph match (e.g. if font size changed or paragraph was split across pages)
        val trimmedPara = displayParagraph.trim()
        if (trimmedPara.length >= 12 && rawQuote.contains(trimmedPara, ignoreCase = true)) {
            results.add(Triple(0, displayParagraph.length, h))
            continue
        }

        // 4. Prefix/Suffix overlap when a highlight or its containing paragraph was split across two pages after font-size reflow!
        val maxOverlapCheck = minOf(rawQuote.length, trimmedPara.length)
        var foundSplitOverlap = false
        if (maxOverlapCheck >= 10) {
            // 4a. Check if the end of displayParagraph is the beginning of rawQuote (highlight starts at bottom of this page and continues onto next page)
            for (len in maxOverlapCheck downTo 10) {
                val quotePrefix = rawQuote.substring(0, len).trim()
                if (quotePrefix.length >= 10 && displayParagraph.trimEnd().endsWith(quotePrefix, ignoreCase = true)) {
                    val startPos = displayParagraph.lastIndexOf(quotePrefix, ignoreCase = true)
                    if (startPos >= 0) {
                        results.add(Triple(startPos, displayParagraph.length, h))
                        foundSplitOverlap = true
                        break
                    }
                }
            }
            // 4b. Check if the beginning of displayParagraph is the end of rawQuote (highlight started on previous page and finishes at top of this page)
            if (!foundSplitOverlap) {
                for (len in maxOverlapCheck downTo 10) {
                    val quoteSuffix = rawQuote.substring(rawQuote.length - len).trim()
                    if (quoteSuffix.length >= 10 && displayParagraph.trimStart().startsWith(quoteSuffix, ignoreCase = true)) {
                        val endPos = displayParagraph.indexOf(quoteSuffix, ignoreCase = true) + quoteSuffix.length
                        if (endPos > 0) {
                            results.add(Triple(0, endPos.coerceAtMost(displayParagraph.length), h))
                            foundSplitOverlap = true
                            break
                        }
                    }
                }
            }
        }
        if (foundSplitOverlap) continue

        // 5. Fallback probe for quotes with minor whitespace differences
        val probeLen = minOf(28, rawQuote.length)
        if (probeLen >= 10) {
            val prefix = rawQuote.take(probeLen).trim()
            val prefixIdx = displayParagraph.indexOf(prefix, ignoreCase = true)
            if (prefixIdx >= 0) {
                results.add(Triple(prefixIdx, (prefixIdx + rawQuote.length).coerceAtMost(displayParagraph.length), h))
                continue
            }
        }
    }
    return results
}

@Composable
fun SelectableParagraphItem(
    paragraph: String,
    pageIndex: Int,
    chapterTitle: String,
    activeTheme: ReaderTheme,
    activeFontFamily: FontFamily,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    activeTextAlign: TextAlign,
    matchingHighlights: List<HighlightEntity>,
    footnotesMap: Map<String, String>,
    semanticBlock: com.example.reader.EpubSemanticBlock? = null,
    footnoteEntries: Map<String, com.example.reader.EpubFootnoteEntry> = emptyMap(),
    embeddedImageBitmap: android.graphics.Bitmap? = null,
    onInternalLinkClick: ((href: String, targetFileKey: String, targetAnchorId: String) -> Unit)? = null,
    onFootnoteEntryClick: ((com.example.reader.EpubFootnoteEntry) -> Unit)? = null,
    onJumpBackFromFootnote: ((com.example.reader.EpubFootnoteEntry) -> Unit)? = null,
    followingParagraphs: List<String> = emptyList(),
    shouldClearSelection: Boolean = false,
    isPdfDocument: Boolean = false,
    resolveQuotePageSpan: ((quote: String, fallbackPage: Int) -> com.example.reader.PdfQuotePageSpan)? = null,
    resolvePassageCitation: ((quote: String, chapterTitle: String) -> com.example.reader.EpubPassageCitation)? = null,
    onToggleUi: () -> Unit,
    onTurnPageDirection: (Int) -> Unit = {},
    onFootnoteClick: (number: String, text: String) -> Unit,
    onRequestHighlight: (quote: String, page: Int, chapterTitle: String, colorHex: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit,
    onQuickSaveHighlight: (quote: String, page: Int, chapterTitle: String, colorHex: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit = { _, _, _, _, _, _, _ -> },
    onSpeakSelection: (quote: String, page: Int) -> Unit = { _, _ -> },
    onEditHighlight: (HighlightEntity) -> Unit = {},
    onSelectionActive: (Boolean) -> Unit = {},
    onSelectionDragRootY: (Float?) -> Unit = {},
    onMergedParagraphCountChanged: (Int) -> Unit = {},
    onSelectionToolbarStateChanged: (ActiveSelectionToolbarState?) -> Unit = {},
    activeDaisyAnchorId: String = "",
    activeDaisyTextSnippet: String = "",
    onDaisyPhraseTapped: ((anchorId: String, textSnippet: String) -> Unit)? = null,
    paragraphStyleSetting: String = "BOTH",
    isFirstAfterHeading: Boolean = false
) {
    val density = LocalDensity.current.density
    val hapticFeedback = LocalHapticFeedback.current
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var boxCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragCurrentIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var activeSelectionRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var extraParagraphCount by remember(paragraph) { mutableIntStateOf(0) }
    var lastAppendedTimeMs by remember { mutableStateOf(0L) }
    var lastDragRootOffset by remember { mutableStateOf<Offset?>(null) }
    var hadReportedToolbar by remember { mutableStateOf(false) }

    val blockType = semanticBlock?.type
    val headingLevel = remember(paragraph, semanticBlock) {
        when {
            semanticBlock?.type == com.example.reader.EpubSemanticBlockType.HEADING ->
                semanticBlock.headingLevel.coerceIn(1, 6)
            paragraph.startsWith("## ") -> 2
            else -> 0
        }
    }
    val isChapterHeading = headingLevel > 0
    val isBlockquote = blockType == com.example.reader.EpubSemanticBlockType.BLOCKQUOTE
    val isFigureOrCaption = blockType == com.example.reader.EpubSemanticBlockType.FIGURE ||
        blockType == com.example.reader.EpubSemanticBlockType.FIGCAPTION
    val isFootnoteDefBlock = blockType == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF
    val isPreformattedBlock = blockType == com.example.reader.EpubSemanticBlockType.PREFORMATTED
    val isDividerBlock = blockType == com.example.reader.EpubSemanticBlockType.DIVIDER

    val baseParagraph = remember(paragraph, isChapterHeading, semanticBlock) {
        val raw = paragraph.ifBlank { semanticBlock?.text.orEmpty() }
        if (isChapterHeading || raw.startsWith("#")) raw.replace(Regex("^#+\\s*"), "").trim() else raw
    }
    val displayParagraph = remember(baseParagraph, extraParagraphCount, followingParagraphs) {
        if (extraParagraphCount <= 0 || followingParagraphs.isEmpty()) {
            baseParagraph
        } else {
            val appended = followingParagraphs.take(extraParagraphCount).map {
                if (it.startsWith("#")) it.replace(Regex("^#+\\s*"), "").trim() else it
            }
            (listOf(baseParagraph) + appended).joinToString("\n\n")
        }
    }
    val listMarkerRegex = remember { Regex("^(?:•|◦|▪|\\d+\\.|\\[\\d+\\]|—)\\s+") }
    val isListOrNoteItem = remember(displayParagraph, blockType) {
        blockType == com.example.reader.EpubSemanticBlockType.LIST_ITEM ||
            blockType == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF ||
            blockType == com.example.reader.EpubSemanticBlockType.DEFINITION_DESC ||
            listMarkerRegex.containsMatchIn(displayParagraph.trimStart())
    }

    val footnoteRegex = remember { Regex("\\[(\\d+[a-zA-Z]?)\\]|\\*(\\d+)") }

    // Keep updated references so mutating selection state during a gesture NEVER restarts pointerInput
    val currentParagraph by rememberUpdatedState(displayParagraph)
    val currentLayoutResult by rememberUpdatedState(textLayoutResult)
    val currentBoxCoordinates by rememberUpdatedState(boxCoordinates)
    val currentOnSelectionActive by rememberUpdatedState(onSelectionActive)
    val currentOnSelectionDragRootY by rememberUpdatedState(onSelectionDragRootY)
    val currentOnToggleUi by rememberUpdatedState(onToggleUi)
    val currentOnTurnPageDirection by rememberUpdatedState(onTurnPageDirection)
    val currentOnFootnoteClick by rememberUpdatedState(onFootnoteClick)
    val currentFootnotesMap by rememberUpdatedState(footnotesMap)
    val currentFootnoteEntries by rememberUpdatedState(footnoteEntries)
    val currentSemanticBlock by rememberUpdatedState(semanticBlock)
    val currentOnInternalLinkClick by rememberUpdatedState(onInternalLinkClick)
    val currentOnFootnoteEntryClick by rememberUpdatedState(onFootnoteEntryClick)
    val currentOnJumpBackFromFootnote by rememberUpdatedState(onJumpBackFromFootnote)
    val currentChapterTitle by rememberUpdatedState(chapterTitle)
    val currentFollowingParagraphs by rememberUpdatedState(followingParagraphs)
    val currentExtraCount by rememberUpdatedState(extraParagraphCount)
    val currentOnMergedParagraphCountChanged by rememberUpdatedState(onMergedParagraphCountChanged)
    val currentOnSelectionToolbarStateChanged by rememberUpdatedState(onSelectionToolbarStateChanged)
    val currentOnEditHighlight by rememberUpdatedState(onEditHighlight)
    val currentOnDaisyPhraseTapped by rememberUpdatedState(onDaisyPhraseTapped)

    val resolvedHighlightSpans = remember(displayParagraph, matchingHighlights, semanticBlock, chapterTitle) {
        findAllHighlightSpans(displayParagraph, matchingHighlights, semanticBlock, chapterTitle)
    }
    val currentHighlightSpans by rememberUpdatedState(resolvedHighlightSpans)

    val clearThisSelection = {
        activeSelectionRange = null
        dragStartIndex = null
        dragCurrentIndex = null
        isDragging = false
        extraParagraphCount = 0
        lastDragRootOffset = null
        currentOnMergedParagraphCountChanged(0)
        currentOnSelectionDragRootY(null)
        currentOnSelectionActive(false)
        if (hadReportedToolbar) {
            hadReportedToolbar = false
            currentOnSelectionToolbarStateChanged(null)
        }
    }

    LaunchedEffect(shouldClearSelection) {
        if (shouldClearSelection && (activeSelectionRange != null || isDragging || extraParagraphCount > 0)) {
            clearThisSelection()
        }
    }

    LaunchedEffect(extraParagraphCount) {
        currentOnMergedParagraphCountChanged(extraParagraphCount)
    }

    // While dragging (including holding finger stationary near the bottom edge while the page auto-scrolls),
    // continuously pull in subsequent paragraphs across page boundaries and extend the selection highlight!
    LaunchedEffect(isDragging, lastDragRootOffset) {
        if (isDragging && lastDragRootOffset != null) {
            while (isDragging) {
                val rootPos = lastDragRootOffset ?: break
                val coords = currentBoxCoordinates
                val layout = currentLayoutResult
                if (coords != null && coords.isAttached && layout != null && currentParagraph.isNotEmpty()) {
                    val localPos = Offset(
                        x = rootPos.x - coords.positionInRoot().x,
                        y = rootPos.y - coords.positionInRoot().y
                    )
                    val nowMs = System.currentTimeMillis()
                    if (localPos.y > layout.size.height - (52f * density) &&
                        currentExtraCount < currentFollowingParagraphs.size &&
                        (nowMs - lastAppendedTimeMs) > 180L
                    ) {
                        lastAppendedTimeMs = nowMs
                        extraParagraphCount = currentExtraCount + 1
                    }
                    val anchorChar = (dragStartIndex ?: activeSelectionRange?.first ?: 0)
                        .coerceIn(0, (currentParagraph.length - 1).coerceAtLeast(0))
                    val (anchorStart, anchorEnd) = findWordBoundaries(currentParagraph, anchorChar)
                    val curChar = getClampedCharOffset(layout, localPos, currentParagraph.length)
                    val (curStart, curEnd) = findWordBoundaries(currentParagraph, curChar)
                    val newStart = minOf(anchorStart, curStart).coerceIn(0, currentParagraph.length)
                    val newEnd = maxOf(anchorEnd, curEnd).coerceIn(0, currentParagraph.length)
                    dragCurrentIndex = curEnd
                    activeSelectionRange = Pair(newStart, newEnd)
                }
                delay(45L)
            }
        }
    }

    LaunchedEffect(activeSelectionRange, dragStartIndex, dragCurrentIndex, isDragging, displayParagraph, extraParagraphCount, followingParagraphs.size) {
        val effectiveRange = activeSelectionRange ?: if (isDragging && dragStartIndex != null && dragCurrentIndex != null) {
            Pair(minOf(dragStartIndex!!, dragCurrentIndex!!), maxOf(dragStartIndex!!, dragCurrentIndex!!))
        } else null

        if (effectiveRange != null && displayParagraph.isNotEmpty()) {
            val s = effectiveRange.first.coerceIn(0, displayParagraph.length)
            val e = effectiveRange.second.coerceIn(0, displayParagraph.length)
            val quote = if (e > s) displayParagraph.substring(s, e).trim() else ""
            if (quote.isNotEmpty()) {
                hadReportedToolbar = true
                val resolvedSpan = resolveQuotePageSpan?.invoke(quote, pageIndex + 1)
                val resolvedPageNum = resolvedSpan?.startPdfPage ?: (pageIndex + 1)
                val resolvedPrintedLabel = resolvedSpan?.toStoredPrintedPageLabel() ?: ""
                val resolvedCitation = resolvePassageCitation?.invoke(quote, chapterTitle)
                val resolvedParaCite = resolvedCitation?.chapterAndParagraphCitation ?: ""
                val resolvedPageEq = resolvedCitation?.pageEquivalent ?: ""
                val resolvedEpubCfi = resolvedCitation?.epubCfi ?: ""
                currentOnSelectionToolbarStateChanged(
                    ActiveSelectionToolbarState(
                        dictionaryContext = paragraph,
                        quote = quote,
                        pageNumber = resolvedPageNum,
                        printedPageLabel = resolvedPrintedLabel,
                        paragraphCitation = resolvedParaCite,
                        pageEquivalent = resolvedPageEq,
                        epubCfi = resolvedEpubCfi,
                        isPdfDocument = isPdfDocument,
                        chapterTitle = chapterTitle,
                        isCurrentlyDragging = isDragging,
                        canAddNextParagraph = extraParagraphCount < followingParagraphs.size,
                        onAddWord = {
                            if (e >= displayParagraph.length && extraParagraphCount < followingParagraphs.size) {
                                val nextParaRaw = followingParagraphs[extraParagraphCount]
                                val nextClean = if (nextParaRaw.startsWith("## ")) nextParaRaw.removePrefix("## ").trim() else nextParaRaw
                                val firstWordEnd = nextClean.indexOf(' ').let { if (it > 0) it else nextClean.length }
                                val newEnd = displayParagraph.length + 2 + firstWordEnd
                                extraParagraphCount++
                                activeSelectionRange = Pair(s, newEnd)
                            } else {
                                var newEnd = e
                                while (newEnd < displayParagraph.length && !Character.isWhitespace(displayParagraph[newEnd])) newEnd++
                                while (newEnd < displayParagraph.length && Character.isWhitespace(displayParagraph[newEnd])) newEnd++
                                while (newEnd < displayParagraph.length && !Character.isWhitespace(displayParagraph[newEnd])) newEnd++
                                activeSelectionRange = Pair(s, newEnd.coerceAtMost(displayParagraph.length))
                            }
                        },
                        onRemoveWord = {
                            var newEnd = e
                            while (newEnd > s && Character.isWhitespace(displayParagraph[newEnd - 1])) newEnd--
                            while (newEnd > s && !Character.isWhitespace(displayParagraph[newEnd - 1])) newEnd--
                            if (newEnd > s) {
                                activeSelectionRange = Pair(s, newEnd)
                            }
                        },
                        onAddNextParagraph = {
                            if (extraParagraphCount < followingParagraphs.size) {
                                val nextParaRaw = followingParagraphs[extraParagraphCount]
                                val nextClean = if (nextParaRaw.startsWith("## ")) nextParaRaw.removePrefix("## ").trim() else nextParaRaw
                                val newTotalLen = displayParagraph.length + 2 + nextClean.length
                                extraParagraphCount++
                                activeSelectionRange = Pair(s, newTotalLen)
                            }
                        },
                        onSelectAll = {
                            activeSelectionRange = Pair(0, displayParagraph.length)
                        },
                        onClearSelection = {
                            clearThisSelection()
                        },
                        onSaveNoteWithDialog = { colorHex ->
                            onRequestHighlight(quote, resolvedPageNum, chapterTitle, colorHex, resolvedParaCite, resolvedPageEq, resolvedEpubCfi)
                            clearThisSelection()
                        },
                        onQuickSaveHighlight = { colorHex ->
                            onQuickSaveHighlight(quote, resolvedPageNum, chapterTitle, colorHex, resolvedParaCite, resolvedPageEq, resolvedEpubCfi)
                            clearThisSelection()
                        },
                        onSpeakSelection = {
                            onSpeakSelection(quote, resolvedPageNum)
                            clearThisSelection()
                        }
                    )
                )
            } else if (hadReportedToolbar) {
                hadReportedToolbar = false
                currentOnSelectionToolbarStateChanged(null)
            }
        } else if (hadReportedToolbar) {
            hadReportedToolbar = false
            currentOnSelectionToolbarStateChanged(null)
        }
    }

    val activeDaisySpan = remember(displayParagraph, semanticBlock, activeDaisyAnchorId, activeDaisyTextSnippet) {
        val cleanAnchor = activeDaisyAnchorId.trim()
        val cleanSnippet = activeDaisyTextSnippet.trim()
        if (cleanAnchor.isEmpty() && cleanSnippet.isEmpty()) {
            null
        } else {
            val anchorMatch = cleanAnchor.isNotEmpty() &&
                semanticBlock?.anchorIds?.any { it.equals(cleanAnchor, ignoreCase = true) } == true
            if (cleanSnippet.isNotEmpty()) {
                val exactIdx = displayParagraph.indexOf(cleanSnippet, ignoreCase = true)
                if (exactIdx >= 0) {
                    Pair(exactIdx, (exactIdx + cleanSnippet.length).coerceAtMost(displayParagraph.length))
                } else {
                    val probe = cleanSnippet.take(32).trim()
                    val probeIdx = if (probe.length >= 8) displayParagraph.indexOf(probe, ignoreCase = true) else -1
                    if (probeIdx >= 0) {
                        Pair(probeIdx, (probeIdx + cleanSnippet.length).coerceAtMost(displayParagraph.length))
                    } else if (anchorMatch) {
                        Pair(0, displayParagraph.length)
                    } else null
                }
            } else if (anchorMatch) {
                Pair(0, displayParagraph.length)
            } else null
        }
    }
    val isActiveDaisyBlock = activeDaisySpan != null

    val isBodyParagraphBlock = !isChapterHeading &&
        !isBlockquote &&
        !isListOrNoteItem &&
        !isFigureOrCaption &&
        !isFootnoteDefBlock &&
        !isPreformattedBlock &&
        !isDividerBlock &&
        semanticBlock?.isVerse != true &&
        semanticBlock?.authorAlignment != "center" &&
        semanticBlock?.authorAlignment != "right" &&
        (blockType == null || blockType == com.example.reader.EpubSemanticBlockType.PARAGRAPH)

    val normalizedParagraphStyle = remember(paragraphStyleSetting) {
        paragraphStyleSetting.uppercase()
    }
    val shouldIndentFirstLine = isBodyParagraphBlock &&
        !isFirstAfterHeading &&
        (normalizedParagraphStyle == "INDENT" || normalizedParagraphStyle == "BOTH")
    val shouldAddParagraphSpacing = isBodyParagraphBlock &&
        (normalizedParagraphStyle == "SPACED" || normalizedParagraphStyle == "BOTH")

    val annotatedText = remember(displayParagraph, isListOrNoteItem, semanticBlock, resolvedHighlightSpans, activeDaisySpan, dragStartIndex, dragCurrentIndex, isDragging, activeSelectionRange, activeTheme, shouldIndentFirstLine, fontSizeSp) {
        buildAnnotatedString {
            if (shouldIndentFirstLine && displayParagraph.isNotEmpty()) {
                addStyle(
                    ParagraphStyle(textIndent = TextIndent(firstLine = (fontSizeSp * 1.5f).sp)),
                    0,
                    displayParagraph.length
                )
            }
            append(displayParagraph)

            // Style list bullets and numbered note prefixes (e.g. "• ", "1. ", "[1] ")
            if (isListOrNoteItem) {
                val markerMatch = listMarkerRegex.find(displayParagraph.trimStart())
                if (markerMatch != null) {
                    val leadingSpaces = displayParagraph.length - displayParagraph.trimStart().length
                    val mStart = leadingSpaces + markerMatch.range.first
                    val mEnd = (leadingSpaces + markerMatch.range.last + 1).coerceAtMost(displayParagraph.length)
                    addStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = activeTheme.textColor
                        ),
                        mStart,
                        mEnd
                    )
                }
            }

            // Saved persistent highlight styling: Renders every highlight matching this paragraph with its saved color!
            for ((startIdx, endIdx, h) in resolvedHighlightSpans) {
                if (startIdx >= 0 && endIdx > startIdx && endIdx <= displayParagraph.length) {
                    val highlightBgColor = Color(parseHighlightColor(h.colorHex))
                    addStyle(
                        SpanStyle(
                            background = highlightBgColor,
                            color = Color(0xFF18181B),
                            fontWeight = FontWeight.Medium
                        ),
                        startIdx,
                        endIdx
                    )
                }
            }

            // Active DAISY SMIL audio narration phrase highlight
            if (activeDaisySpan != null) {
                val ds = activeDaisySpan.first.coerceIn(0, displayParagraph.length)
                val de = activeDaisySpan.second.coerceIn(0, displayParagraph.length)
                if (de > ds) {
                    val daisyBg = if (activeTheme == ReaderTheme.DARK) Color(0xFF78350F) else Color(0xFFFDE68A)
                    val daisyFg = if (activeTheme == ReaderTheme.DARK) Color(0xFFFEF3C7) else Color(0xFF18181B)
                    addStyle(
                        SpanStyle(
                            background = daisyBg,
                            color = daisyFg,
                            fontWeight = FontWeight.SemiBold
                        ),
                        ds,
                        de
                    )
                }
            }

            // Semantic inline links (<a href="..."> and epub:type="noteref")
            if (!isChapterHeading || semanticBlock?.inlineLinks?.any { it.isFootnoteRef } == true) {
                semanticBlock?.inlineLinks?.forEach { link ->
                    if (isChapterHeading && !link.isFootnoteRef) return@forEach
                    val s = link.startChar.coerceIn(0, displayParagraph.length)
                    val e = link.endChar.coerceIn(0, displayParagraph.length)
                    if (e > s) {
                        if (link.isFootnoteRef) {
                            addStyle(
                                SpanStyle(
                                    color = if (activeTheme == ReaderTheme.DARK) Color(0xFF93C5FD) else Color(0xFF1D4ED8),
                                    background = if (activeTheme == ReaderTheme.DARK) Color(0xFF1E3A8A).copy(alpha = 0.45f) else Color(0xFFDBEAFE),
                                    fontWeight = FontWeight.Bold
                                ),
                                s,
                                e
                            )
                        } else {
                            addStyle(
                                SpanStyle(
                                    color = if (activeTheme == ReaderTheme.DARK) Color(0xFF93C5FD) else Color(0xFF2563EB),
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline
                                ),
                                s,
                                e
                            )
                        }
                    }
                }
            }

            // Readium CSS / ts-toolkit inline formatting spans (italic, bold, underline, strikethrough, etc.)
            semanticBlock?.inlineStyles?.forEach { span ->
                val s = span.startChar.coerceIn(0, displayParagraph.length)
                val e = span.endChar.coerceIn(0, displayParagraph.length)
                if (e > s) {
                    val spanStyle = when (span.type) {
                        EpubInlineStyleType.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        EpubInlineStyleType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        EpubInlineStyleType.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                        EpubInlineStyleType.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                        EpubInlineStyleType.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        EpubInlineStyleType.SUPERSCRIPT -> SpanStyle(
                            baselineShift = BaselineShift.Superscript,
                            fontSize = (fontSizeSp * 0.75f).sp
                        )
                        EpubInlineStyleType.SUBSCRIPT -> SpanStyle(
                            baselineShift = BaselineShift.Subscript,
                            fontSize = (fontSizeSp * 0.75f).sp
                        )
                        EpubInlineStyleType.SMALL -> SpanStyle(fontSize = (fontSizeSp * 0.85f).sp)
                        EpubInlineStyleType.MONOSPACE -> SpanStyle(fontFamily = FontFamily.Monospace)
                        EpubInlineStyleType.MARK -> SpanStyle(
                            background = if (activeTheme == ReaderTheme.DARK) Color(0xFF854D0E) else Color(0xFFFEF08A),
                            color = if (activeTheme == ReaderTheme.DARK) Color(0xFFFEF08A) else Color(0xFF713F12)
                        )
                        EpubInlineStyleType.DROP_CAP -> SpanStyle(
                            fontSize = (fontSizeSp * 2.4f).sp,
                            fontWeight = FontWeight.Bold
                        )
                        EpubInlineStyleType.SMALL_CAPS -> SpanStyle(
                            fontFeatureSettings = "smcp",
                            fontWeight = FontWeight.Medium
                        )
                    }
                    addStyle(spanStyle, s, e)
                }
            }

            // Inline <code> spans rendered in monospace
            semanticBlock?.inlineCodeRanges?.forEach { range ->
                val s = range.first.coerceIn(0, displayParagraph.length)
                val e = (range.last + 1).coerceIn(0, displayParagraph.length)
                if (e > s) {
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = activeTheme.textColor
                        ),
                        s,
                        e
                    )
                }
            }

            // In-file Footnote references: [1], [2], *1 (pill-badge styled as a footnote, not plain link)
            footnoteRegex.findAll(displayParagraph).forEach { match ->
                addStyle(
                    SpanStyle(
                        color = if (activeTheme == ReaderTheme.DARK) Color(0xFF93C5FD) else Color(0xFF1D4ED8),
                        background = if (activeTheme == ReaderTheme.DARK) Color(0xFF1E3A8A).copy(alpha = 0.45f) else Color(0xFFDBEAFE),
                        fontWeight = FontWeight.Bold
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }

            // Active dragging highlight preview: Canary Yellow highlighter
            if (isDragging && dragStartIndex != null && dragCurrentIndex != null) {
                val s = minOf(dragStartIndex!!, dragCurrentIndex!!).coerceIn(0, displayParagraph.length)
                val e = maxOf(dragStartIndex!!, dragCurrentIndex!!).coerceIn(0, displayParagraph.length)
                if (e > s) {
                    addStyle(
                        SpanStyle(
                            background = Color(0xFFFFF176).copy(alpha = 0.85f),
                            color = Color.Black,
                            fontWeight = FontWeight.SemiBold
                        ),
                        s,
                        e
                    )
                }
            } else if (activeSelectionRange != null) {
                // Completed selection waiting for user action in Canary Yellow
                val s = activeSelectionRange!!.first.coerceIn(0, displayParagraph.length)
                val e = activeSelectionRange!!.second.coerceIn(0, displayParagraph.length)
                if (e > s) {
                    addStyle(
                        SpanStyle(
                            background = Color(0xFFFFEB3B),
                            color = Color(0xFF18181B),
                            fontWeight = FontWeight.Bold
                        ),
                        s,
                        e
                    )
                }
            }
        }
    }

    val resolvedFootnoteEntryForDefBlock = remember(isFootnoteDefBlock, semanticBlock, footnoteEntries, baseParagraph) {
        if (!isFootnoteDefBlock || footnoteEntries.isEmpty()) null
        else {
            val fnId = semanticBlock?.footnoteId?.trim().orEmpty()
            val markerMatch = Regex("^(?:\\[(\\d+[a-zA-Z]?)\\]|(\\d+[a-zA-Z]?)[.):])").find(baseParagraph.trim())
                ?.let { it.groups[1]?.value ?: it.groups[2]?.value }.orEmpty()
            footnoteEntries[fnId]
                ?: footnoteEntries[markerMatch]
                ?: footnoteEntries["fn$markerMatch"]
                ?: footnoteEntries["fn-$markerMatch"]
                ?: footnoteEntries["note$markerMatch"]
                ?: footnoteEntries["en$markerMatch"]
                ?: footnoteEntries.values.firstOrNull {
                    (fnId.isNotEmpty() && it.footnoteAnchorId.equals(fnId, ignoreCase = true)) ||
                        (markerMatch.isNotEmpty() && it.displayMarker.equals(markerMatch, ignoreCase = true))
                }
        }
    }

    val headingScale = when (headingLevel) {
        1 -> 1.42f
        2 -> 1.26f
        3 -> 1.15f
        4 -> 1.08f
        5, 6 -> 1.03f
        else -> if (isFigureOrCaption || isFootnoteDefBlock) 0.92f else 1.0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = when {
                    headingLevel in 1..2 -> 16.dp
                    headingLevel >= 3 -> 10.dp
                    isBlockquote || isFigureOrCaption || isFootnoteDefBlock -> 8.dp
                    isBodyParagraphBlock -> 0.dp
                    else -> 4.dp
                },
                bottom = when {
                    isChapterHeading -> 8.dp
                    isBlockquote || isFigureOrCaption || isFootnoteDefBlock -> 8.dp
                    isBodyParagraphBlock -> if (shouldAddParagraphSpacing) (fontSizeSp * 0.6f).dp else 0.dp
                    else -> 4.dp
                }
            )
    ) {
        if (embeddedImageBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = embeddedImageBitmap.asImageBitmap(),
                contentDescription = semanticBlock?.imageAlt?.ifBlank { "Figure" } ?: "Figure",
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, activeTheme.dividerColor, RoundedCornerShape(8.dp))
            )
        }

        if (isDividerBlock) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                color = activeTheme.dividerColor
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isBlockquote) {
                            Modifier
                                .padding(start = 8.dp, end = 4.dp)
                                .background(activeTheme.barColor.copy(alpha = 0.45f), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                                .drawWithContent {
                                    drawContent()
                                    drawLine(
                                        color = Color(0xFF7C3AED).copy(alpha = 0.75f),
                                        start = Offset(0f, 0f),
                                        end = Offset(0f, size.height),
                                        strokeWidth = 3.dp.toPx()
                                    )
                                }
                                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 8.dp)
                        } else if (isFootnoteDefBlock) {
                            Modifier
                                .background(activeTheme.barColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .border(1.dp, activeTheme.dividerColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        } else {
                            Modifier
                        }
                    )
                    .onGloballyPositioned { coords ->
                        boxCoordinates = coords
                    }
                    .pointerInput(baseParagraph) {
                        detectCustomTextSelectionGestures(
                            getParagraphText = { currentParagraph },
                            getTextLayoutResult = { currentLayoutResult },
                            getActiveSelection = { activeSelectionRange },
                            getInteractiveRanges = {
                                val ranges = mutableListOf<IntRange>()
                                currentSemanticBlock?.inlineLinks?.forEach { link ->
                                    val s = link.startChar.coerceIn(0, currentParagraph.length)
                                    val e = (link.endChar - 1).coerceIn(s, (currentParagraph.length - 1).coerceAtLeast(0))
                                    if (link.endChar > link.startChar) ranges.add(s..e)
                                }
                                footnoteRegex.findAll(currentParagraph).forEach { m ->
                                    ranges.add(m.range)
                                }
                                ranges
                            },
                            onSelectionChange = { newRange ->
                                activeSelectionRange = newRange
                            },
                            onDragPreviewChange = { start, current, dragging ->
                                dragStartIndex = start
                                dragCurrentIndex = current
                                isDragging = dragging
                            },
                            onSelectionActive = { active ->
                                currentOnSelectionActive(active)
                                if (!active) {
                                    lastDragRootOffset = null
                                    currentOnSelectionDragRootY(null)
                                }
                            },
                            onLongPressActivated = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragPositionInParent = { localOffset ->
                                if (localOffset == null) {
                                    lastDragRootOffset = null
                                    currentOnSelectionDragRootY(null)
                                } else {
                                    val coords = currentBoxCoordinates
                                    if (coords != null && coords.isAttached) {
                                        val rootPos = Offset(
                                            x = coords.positionInRoot().x + localOffset.x,
                                            y = coords.positionInRoot().y + localOffset.y
                                        )
                                        lastDragRootOffset = rootPos
                                        currentOnSelectionDragRootY(rootPos.y)
                                    }
                                    val layout = currentLayoutResult
                                    val nowMs = System.currentTimeMillis()
                                    if (layout != null &&
                                        localOffset.y > layout.size.height - (42f * density) &&
                                        currentExtraCount < currentFollowingParagraphs.size &&
                                        (nowMs - lastAppendedTimeMs) > 180L
                                    ) {
                                        lastAppendedTimeMs = nowMs
                                        extraParagraphCount = currentExtraCount + 1
                                    }
                                }
                            },
                            onTapNonSelection = { offset ->
                                val layout = currentLayoutResult
                                if (layout != null) {
                                    val tapChar = getClampedCharOffset(layout, offset, currentParagraph.length)
                                    val touchTolPx = 24f * density

                                    // 1. Check semantic inline links (<a href="..."> or explicit epub:type="noteref") with bounding-box proximity tolerance
                                    val semanticLinks = currentSemanticBlock?.inlineLinks ?: emptyList()
                                    val semanticRanges = semanticLinks.map { link ->
                                        val s = link.startChar.coerceIn(0, (currentParagraph.length - 1).coerceAtLeast(0))
                                        val e = (link.endChar - 1).coerceIn(s, (currentParagraph.length - 1).coerceAtLeast(0))
                                        s..e
                                    }
                                    val tappedSemanticIdx = findTappedInlineLinkIndex(
                                        layout = layout,
                                        offset = offset,
                                        text = currentParagraph,
                                        inlineLinkRanges = semanticRanges,
                                        touchTolerancePx = touchTolPx
                                    )
                                    val tappedSemanticLink = if (tappedSemanticIdx != null) semanticLinks.getOrNull(tappedSemanticIdx) else null

                                    // 2. Check bracketed footnote markers [1], [2], *1 with bounding-box proximity tolerance
                                    val fnMatches = footnoteRegex.findAll(currentParagraph).toList()
                                    val fnRanges = fnMatches.map { it.range }
                                    val tappedFnIdx = findTappedInlineLinkIndex(
                                        layout = layout,
                                        offset = offset,
                                        text = currentParagraph,
                                        inlineLinkRanges = fnRanges,
                                        touchTolerancePx = touchTolPx
                                    )
                                    val fnMatch = if (tappedFnIdx != null) fnMatches.getOrNull(tappedFnIdx) else null

                                    val tappedHighlight = currentHighlightSpans.firstOrNull { (s, e, _) ->
                                        tapChar in s..e
                                    }?.third

                                    fun resolveBestFootnoteEntryAndText(rawKey: String, anchorKey: String, markerNum: String): Pair<com.example.reader.EpubFootnoteEntry?, String> {
                                        val cleanMarker = markerNum.trim('[', ']', '*', ' ')
                                        val candidates = listOf(rawKey, anchorKey, cleanMarker, "fn$cleanMarker", "fn-$cleanMarker", "fn_$cleanMarker", "note$cleanMarker", "note-$cleanMarker", "en$cleanMarker")
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                        var matchedEntry: com.example.reader.EpubFootnoteEntry? = null
                                        for (c in candidates) {
                                            val e = currentFootnoteEntries[c]
                                            if (e != null) {
                                                matchedEntry = e
                                                if (e.noteText.isNotBlank()) break
                                            }
                                        }
                                        if (matchedEntry == null || matchedEntry.noteText.isBlank()) {
                                            val byValue = currentFootnoteEntries.values.firstOrNull { e ->
                                                e.noteText.isNotBlank() && (
                                                    (cleanMarker.isNotEmpty() && e.displayMarker.equals(cleanMarker, ignoreCase = true)) ||
                                                        (anchorKey.isNotEmpty() && e.footnoteAnchorId.equals(anchorKey, ignoreCase = true)) ||
                                                        (rawKey.isNotEmpty() && e.noteId.equals(rawKey, ignoreCase = true))
                                                    )
                                            } ?: currentFootnoteEntries.values.firstOrNull { e ->
                                                (cleanMarker.isNotEmpty() && e.displayMarker.equals(cleanMarker, ignoreCase = true)) ||
                                                    (anchorKey.isNotEmpty() && e.footnoteAnchorId.equals(anchorKey, ignoreCase = true))
                                            }
                                            if (byValue != null) {
                                                matchedEntry = if (matchedEntry != null && matchedEntry.sourceContextSnippet.isNotBlank() && byValue.sourceContextSnippet.isBlank()) {
                                                    byValue.copy(
                                                        sourceFileKey = matchedEntry.sourceFileKey,
                                                        sourceChapterIndex = matchedEntry.sourceChapterIndex,
                                                        sourceChapterTitle = matchedEntry.sourceChapterTitle,
                                                        sourceParagraphNumber = matchedEntry.sourceParagraphNumber,
                                                        sourceAnchorId = matchedEntry.sourceAnchorId,
                                                        sourceContextSnippet = matchedEntry.sourceContextSnippet
                                                    )
                                                } else byValue
                                            }
                                        }
                                        var resolvedText = matchedEntry?.noteText.orEmpty()
                                        if (resolvedText.isBlank()) {
                                            for (c in candidates) {
                                                val mapVal = currentFootnotesMap[c]
                                                if (!mapVal.isNullOrBlank()) {
                                                    resolvedText = mapVal
                                                    break
                                                }
                                            }
                                        }
                                        if (resolvedText.isNotBlank() && matchedEntry != null && matchedEntry.noteText.isBlank()) {
                                            matchedEntry = matchedEntry.copy(noteText = resolvedText)
                                        }
                                        return Pair(matchedEntry, resolvedText)
                                    }

                                    if (tappedSemanticLink != null) {
                                        if (tappedSemanticLink.isFootnoteRef) {
                                            val markerClean = tappedSemanticLink.label.trim('[', ']', '*', ' ')
                                            val fnKey = tappedSemanticLink.footnoteId.ifBlank {
                                                tappedSemanticLink.targetAnchorId.ifBlank { markerClean }
                                            }
                                            val (entry, resolvedText) = resolveBestFootnoteEntryAndText(
                                                rawKey = fnKey,
                                                anchorKey = tappedSemanticLink.targetAnchorId,
                                                markerNum = markerClean
                                            )
                                            if (entry != null && entry.noteText.isNotBlank() && currentOnFootnoteEntryClick != null) {
                                                currentOnFootnoteEntryClick?.invoke(entry)
                                            } else if (resolvedText.isNotBlank()) {
                                                val displayNum = entry?.displayMarker?.ifBlank { markerClean } ?: markerClean.ifBlank { fnKey }
                                                if (entry != null && currentOnFootnoteEntryClick != null) {
                                                    currentOnFootnoteEntryClick?.invoke(entry.copy(noteText = resolvedText))
                                                } else {
                                                    currentOnFootnoteClick(displayNum, resolvedText)
                                                }
                                            } else if (tappedSemanticLink.targetHref.isNotBlank() && currentOnInternalLinkClick != null &&
                                                (tappedSemanticLink.targetFileKey.isNotBlank() || tappedSemanticLink.targetAnchorId.isNotBlank())
                                            ) {
                                                // If the pop-up note target cannot be resolved to note text, fall back to normal link navigation
                                                currentOnInternalLinkClick?.invoke(
                                                    tappedSemanticLink.targetHref,
                                                    tappedSemanticLink.targetFileKey,
                                                    tappedSemanticLink.targetAnchorId
                                                )
                                            } else {
                                                val displayNum = entry?.displayMarker?.ifBlank { markerClean } ?: markerClean.ifBlank { fnKey }
                                                currentOnFootnoteClick(displayNum, "Footnote $displayNum referenced in $currentChapterTitle")
                                            }
                                        } else if (currentOnInternalLinkClick != null && tappedSemanticLink.targetHref.isNotBlank()) {
                                            currentOnInternalLinkClick?.invoke(
                                                tappedSemanticLink.targetHref,
                                                tappedSemanticLink.targetFileKey,
                                                tappedSemanticLink.targetAnchorId
                                            )
                                        }
                                    } else if (fnMatch != null) {
                                        val fnNum = fnMatch.groups[1]?.value ?: fnMatch.groups[2]?.value ?: ""
                                        // If user tapped the marker on a FOOTNOTE_DEF block itself, show context / jump back!
                                        if (currentSemanticBlock?.type == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF) {
                                            val (defEntry, defText) = resolveBestFootnoteEntryAndText(
                                                rawKey = currentSemanticBlock?.footnoteId.orEmpty(),
                                                anchorKey = currentSemanticBlock?.footnoteId.orEmpty(),
                                                markerNum = fnNum
                                            )
                                            if (defEntry != null && currentOnFootnoteEntryClick != null) {
                                                currentOnFootnoteEntryClick?.invoke(
                                                    if (defEntry.noteText.isBlank()) defEntry.copy(noteText = defText.ifBlank { currentParagraph }) else defEntry
                                                )
                                            } else {
                                                val fnText = defText.ifBlank { currentParagraph }
                                                currentOnFootnoteClick(fnNum, fnText)
                                            }
                                        } else {
                                            val (entry, resolvedText) = resolveBestFootnoteEntryAndText(
                                                rawKey = fnNum,
                                                anchorKey = fnNum,
                                                markerNum = fnNum
                                            )
                                            if (entry != null && entry.noteText.isNotBlank() && currentOnFootnoteEntryClick != null) {
                                                currentOnFootnoteEntryClick?.invoke(entry)
                                            } else {
                                                val fnText = resolvedText.ifBlank { "Footnote $fnNum referenced in $currentChapterTitle" }
                                                if (entry != null && currentOnFootnoteEntryClick != null) {
                                                    currentOnFootnoteEntryClick?.invoke(entry.copy(noteText = fnText))
                                                } else {
                                                    currentOnFootnoteClick(fnNum, fnText)
                                                }
                                            }
                                        }
                                    } else if (tappedHighlight != null) {
                                        currentOnEditHighlight(tappedHighlight)
                                    } else if (currentSemanticBlock?.type == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF &&
                                        offset.x >= size.width * 0.18f && offset.x <= size.width * 0.82f
                                    ) {
                                        val defId = currentSemanticBlock?.footnoteId.orEmpty()
                                        val defEntry = currentFootnoteEntries[defId]
                                            ?: currentFootnoteEntries.values.firstOrNull { it.footnoteAnchorId == defId }
                                        if (defEntry != null && currentOnFootnoteEntryClick != null) {
                                            currentOnFootnoteEntryClick?.invoke(defEntry)
                                        }
                                    } else if (offset.x < size.width * 0.18f) {
                                        currentOnTurnPageDirection(-1)
                                    } else if (offset.x > size.width * 0.82f) {
                                        currentOnTurnPageDirection(1)
                                    } else if (currentOnDaisyPhraseTapped != null) {
                                        val firstAnchor = currentSemanticBlock?.anchorIds?.firstOrNull().orEmpty()
                                        currentOnDaisyPhraseTapped?.invoke(firstAnchor, currentParagraph)
                                    }
                                } else {
                                    if (offset.x < size.width * 0.18f) {
                                        currentOnTurnPageDirection(-1)
                                    } else if (offset.x > size.width * 0.82f) {
                                        currentOnTurnPageDirection(1)
                                    }
                                }
                            }
                        )
                    }
                    .drawWithContent {
                        drawContent()
                        // Draw KOReader-style visual selection handles at start and end of active selection
                        val sel = activeSelectionRange
                        val layout = textLayoutResult
                        if (sel != null && layout != null && displayParagraph.isNotEmpty()) {
                            val sIdx = sel.first.coerceIn(0, displayParagraph.length - 1)
                            val eIdx = (sel.second - 1).coerceIn(0, displayParagraph.length - 1)
                            val startBox = layout.getBoundingBox(sIdx)
                            val endBox = layout.getBoundingBox(eIdx)
                            val handleColor = Color(0xFF7C3AED)
                            val stemWidth = 2.5.dp.toPx()
                            val handleRadius = 5.dp.toPx()

                            // Start handle (top-left stem + circle)
                            drawLine(
                                color = handleColor,
                                start = Offset(startBox.left, startBox.top),
                                end = Offset(startBox.left, startBox.bottom),
                                strokeWidth = stemWidth
                            )
                            drawCircle(
                                color = handleColor,
                                radius = handleRadius,
                                center = Offset(startBox.left, startBox.top)
                            )

                            // End handle (bottom-right stem + circle)
                            drawLine(
                                color = handleColor,
                                start = Offset(endBox.right, endBox.top),
                                end = Offset(endBox.right, endBox.bottom),
                                strokeWidth = stemWidth
                            )
                            drawCircle(
                                color = handleColor,
                                radius = handleRadius,
                                center = Offset(endBox.right, endBox.bottom)
                            )
                        }
                    }
                    .padding(
                        start = when {
                            isListOrNoteItem && !isFootnoteDefBlock -> (14 + (semanticBlock?.listLevel ?: 0) * 12).dp
                            else -> 4.dp
                        },
                        end = 4.dp,
                        top = 2.dp,
                        bottom = 2.dp
                    )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = annotatedText,
                        textAlign = when {
                            semanticBlock?.authorAlignment == "center" -> TextAlign.Center
                            semanticBlock?.authorAlignment == "right" -> TextAlign.End
                            semanticBlock?.authorAlignment == "justify" -> TextAlign.Justify
                            semanticBlock?.authorAlignment == "left" -> TextAlign.Start
                            isChapterHeading || isListOrNoteItem || isPreformattedBlock -> TextAlign.Start
                            isFigureOrCaption -> TextAlign.Center
                            else -> activeTextAlign
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = if (isPreformattedBlock) FontFamily.Monospace else activeFontFamily,
                            fontSize = (fontSizeSp * headingScale).sp,
                            fontWeight = when {
                                isChapterHeading -> FontWeight.Bold
                                blockType == com.example.reader.EpubSemanticBlockType.DEFINITION_TERM -> FontWeight.SemiBold
                                else -> FontWeight.Normal
                            },
                            fontStyle = if (isBlockquote || isFigureOrCaption) FontStyle.Italic else FontStyle.Normal,
                            lineHeight = (if (isChapterHeading) fontSizeSp * headingScale * 1.25f else fontSizeSp * headingScale * lineSpacingMultiplier).sp
                        ),
                        color = if (isFigureOrCaption || isBlockquote) activeTheme.secondaryTextColor else activeTheme.textColor,
                        onTextLayout = { textLayoutResult = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Bidirectional footnote backlink bar when rendering a FOOTNOTE_DEF block in the book
                    if (isFootnoteDefBlock && resolvedFootnoteEntryForDefBlock != null) {
                        val fnEntry = resolvedFootnoteEntryForDefBlock
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val citeInfo = buildString {
                                if (fnEntry.sourceChapterTitle.isNotBlank()) {
                                    append("Ref: ")
                                    append(fnEntry.sourceChapterTitle)
                                }
                                if (fnEntry.sourceParagraphNumber > 0) {
                                    append(", ¶ ")
                                    append(fnEntry.sourceParagraphNumber)
                                }
                            }
                            if (citeInfo.isNotEmpty()) {
                                Text(
                                    text = citeInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = activeTheme.secondaryTextColor
                                )
                            }
                            Surface(
                                color = Color(0xFF2563EB).copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .clickable {
                                        if (onJumpBackFromFootnote != null) {
                                            onJumpBackFromFootnote(fnEntry)
                                        } else if (onFootnoteEntryClick != null) {
                                            onFootnoteEntryClick(fnEntry)
                                        }
                                    }
                                    .testTag("footnote_def_back_to_text_${fnEntry.displayMarker}")
                            ) {
                                Text(
                                    text = "↩ Tilbake til tekst",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTheme == ReaderTheme.DARK) Color(0xFF93C5FD) else Color(0xFF2563EB),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun buildStyledParagraphAnnotatedString(
    displayParagraph: String,
    paragraphStyleSetting: String,
    fontSizeSp: Float = 18f,
    isBodyParagraphBlock: Boolean = true,
    isFirstAfterHeading: Boolean = false
): AnnotatedString {
    val normStyle = paragraphStyleSetting.uppercase()
    val shouldIndent = isBodyParagraphBlock &&
        !isFirstAfterHeading &&
        (normStyle == "INDENT" || normStyle == "BOTH")
    return buildAnnotatedString {
        if (shouldIndent && displayParagraph.isNotEmpty()) {
            addStyle(
                ParagraphStyle(textIndent = TextIndent(firstLine = (fontSizeSp * 1.5f).sp)),
                0,
                displayParagraph.length
            )
        }
        append(displayParagraph)
    }
}
