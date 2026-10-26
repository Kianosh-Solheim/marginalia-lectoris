package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.data.StorageManager
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.reader.EpubParser
import com.example.reader.NoteAiAssistant
import com.example.reader.MarkdownExporter
import com.example.reader.NoteSuggestionEngine
import com.example.reader.PdfDocumentRenderer
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
import com.example.ui.theme.KaleidoSageBorder
import com.example.ui.theme.KaleidoSageFill
import com.example.ui.theme.KaleidoSageInk
import com.example.ui.theme.KaleidoTerracottaBorder
import com.example.ui.theme.KaleidoTerracottaFill
import com.example.ui.theme.KaleidoTerracottaInk
import com.example.ui.theme.kaleidoSwatchForTag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context menu dialog shown when a user long-presses or right-clicks a document item in the Library view.
 */
@Composable
fun LibraryItemContextMenuDialog(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onPreviewNotesMoc: () -> Unit,
    onShareClick: () -> Unit = {},
    onExportAllNotesAndSource: () -> Unit,
    onEditDocumentInfo: () -> Unit = {},
    onUpdateDocumentTags: (String) -> Unit = {},
    onOpenDocument: () -> Unit,
    onTogglePinToWidget: () -> Unit,
    onDeleteDocument: () -> Unit,
    onOpenChapters: () -> Unit = {},
    onAddToFolderBook: () -> Unit = {},
    onChangeCategory: (String) -> Unit = {},
    onOpenBibliography: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val mocTitle = remember(document.title, settings.mocTitleFormat) {
        MarkdownExporter.getMocTitle(document.title, settings)
    }
    val mocFilename = remember(mocTitle) {
        "${MarkdownExporter.sanitizeFilename(mocTitle)}.md"
    }

    var activeBookTags by remember(document.id, document.tags) {
        mutableStateOf(NoteSuggestionEngine.parseTagsList(document.tags))
    }
    var inlineTagInput by remember { mutableStateOf("") }
    var isGeneratingAiBookTags by remember { mutableStateOf(false) }
    var aiSuggestedBookTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var aiBookTagsLanguage by remember { mutableStateOf<String?>(null) }

    fun persistBookTags(updatedList: List<String>) {
        val normalized = NoteSuggestionEngine.parseTagsList(updatedList.joinToString(", "))
        activeBookTags = normalized
        onUpdateDocumentTags(NoteSuggestionEngine.joinTagsList(normalized))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black)
            .testTag("library_item_context_menu_dialog"),
        title = {
            DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val tagsSuffix = if (activeBookTags.isNotEmpty()) {
                        " • ${activeBookTags.joinToString(", ") { "#$it" }}"
                    } else ""
                    Text(
                        text = "${document.author} • ${document.fileType.uppercase()} • ${highlights.size} notes$tagsSuffix",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        color = Color(0xFF18181B),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                if (document.summary.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color(0xFFFAFAFA),
                        border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("context_menu_abstract_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Abstract / Summary",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.Black
                            )
                            Text(
                                text = document.summary.trim(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Serif
                                ),
                                color = Color(0xFF18181B),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("context_menu_abstract_text")
                            )
                        }
                    }
                }

                // Inline Book / Article Overview Tags Card (Separate from Highlight Note Tags)
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("context_menu_inline_book_tags_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Book / Article Overview Tags (${activeBookTags.size})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Exported to $mocFilename • Separate from highlight tags",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color(0xFF18181B),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            val isOnline = NoteAiAssistant.isInternetAvailable(context)
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = if (isOnline) KaleidoAmberFill else Color.White,
                                border = BorderStroke(1.5.dp, if (isOnline) Color.Black else Color(0xFFD4D4D8)),
                                modifier = Modifier
                                    .clickable(enabled = !isGeneratingAiBookTags) {
                                        if (!NoteAiAssistant.isInternetAvailable(context)) {
                                            Toast.makeText(
                                                context,
                                                "Internet connection required for AI tag suggestions",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@clickable
                                        }
                                        isGeneratingAiBookTags = true
                                        coroutineScope.launch {
                                            val result = NoteAiAssistant.suggestBookOverviewTags(context = context,
                                                documentTitle = document.title,
                                                author = document.author,
                                                category = document.category,
                                                summary = document.summary,
                                                sampleHighlights = highlights.map { it.highlightedText }.take(5),
                                                existingTags = activeBookTags
                                            )
                                            isGeneratingAiBookTags = false
                                            result.onSuccess { aiResp ->
                                                aiSuggestedBookTags = aiResp.tags.filterNot { candidate ->
                                                    activeBookTags.any { it.equals(candidate, ignoreCase = true) }
                                                }
                                                aiBookTagsLanguage = aiResp.detectedLanguage
                                            }.onFailure {
                                                Toast.makeText(
                                                    context,
                                                    it.message ?: "Could not generate AI tags",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    .testTag("context_menu_ai_spark_book_tags_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isGeneratingAiBookTags) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.8.dp,
                                            color = KaleidoAmberInk
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "AI Suggest Book Overview Tags",
                                            tint = if (isOnline) KaleidoAmberInk else Color(0xFF71717A),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Text(
                                        text = "AI Tags",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOnline) KaleidoAmberInk else Color(0xFF71717A)
                                    )
                                }
                            }
                        }

                        if (activeBookTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeBookTags.forEach { tag ->
                                    val swatch = kaleidoSwatchForTag(tag)
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = swatch.fill,
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .clickable {
                                                persistBookTags(activeBookTags.filterNot { it.equals(tag, ignoreCase = true) })
                                            }
                                            .testTag("context_menu_active_book_tag_$tag")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = swatch.ink,
                                                fontWeight = FontWeight.Normal
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove book tag $tag",
                                                tint = Color.Black,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No book/article tags yet. Add tags below or tap AI Tags to suggest overview tags.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                        }

                        val remainingAiBookTags = aiSuggestedBookTags.filterNot { candidate ->
                            activeBookTags.any { it.equals(candidate, ignoreCase = true) }
                        }
                        AnimatedVisibility(visible = remainingAiBookTags.isNotEmpty()) {
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
                                            tint = Color.Black,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "AI Overview Tags (${aiBookTagsLanguage ?: "Auto"}):",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                    Text(
                                        text = "+ Add All AI",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        modifier = Modifier
                                            .clickable {
                                                persistBookTags(activeBookTags + remainingAiBookTags)
                                            }
                                            .testTag("context_menu_add_all_ai_book_tags_btn")
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    remainingAiBookTags.forEachIndexed { idx, aiTag ->
                                        Surface(
                                            shape = RoundedCornerShape(0.dp),
                                            color = KaleidoAmberFill,
                                            border = BorderStroke(1.dp, Color.Black),
                                            modifier = Modifier
                                                .clickable {
                                                    persistBookTags(activeBookTags + aiTag)
                                                }
                                                .testTag("context_menu_ai_book_tag_chip_$idx")
                                        ) {
                                            Text(
                                                text = "+ #$aiTag",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = inlineTagInput,
                                onValueChange = { inlineTagInput = it },
                                placeholder = { Text("Add book/article tag...", fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("context_menu_inline_tag_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Black,
                                    unfocusedBorderColor = Color.Black,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    val newTags = NoteSuggestionEngine.parseTagsList(inlineTagInput)
                                    if (newTags.isNotEmpty()) {
                                        persistBookTags(activeBookTags + newTags)
                                        inlineTagInput = ""
                                    }
                                },
                                enabled = inlineTagInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.testTag("context_menu_inline_add_tag_btn")
                            ) {
                                Text("+ Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Option 1: Share (Source File, One/Multiple Notes as .md, or All Notes + Map of Content)
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShareClick() }
                        .testTag("context_menu_share_options")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = KaleidoPlumFill,
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = KaleidoPlumInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Share ${document.fileType.uppercase()} file, one/multiple notes (.md), or all notes + $mocFilename (without ${document.fileType.uppercase()} & cover)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF18181B)
                            )
                        }
                    }
                }

                // Option 2: Preview Notes on X.md (Interactive Preview)
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPreviewNotesMoc() }
                        .testTag("context_menu_preview_notes_md")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = KaleidoCobaltFill,
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = KaleidoCobaltInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Preview $mocFilename",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Interactive Map of Content — click any note to preview its full Markdown",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF18181B)
                            )
                        }
                    }
                }

                // Option 3: Export All Notes & Source File (ZIP or Regular Folder)
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExportAllNotesAndSource() }
                        .testTag("context_menu_export_all_bundle")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = KaleidoSageFill,
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    tint = KaleidoSageInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Export All Notes & Source File",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Exports all highlights, $mocFilename, ${document.fileType.uppercase()} source file, and cover image as .ZIP or regular folder",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF18181B)
                            )
                        }
                    }
                }

                // Option 4: Edit Info, Author(s), Tags & Cover Image
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditDocumentInfo() }
                        .testTag("context_menu_edit_info_and_cover")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = KaleidoAmberFill,
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = KaleidoAmberInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Edit Info, Tags & Cover Image",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Edit author(s), add tags for $mocFilename, or upload/replace cover image",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF18181B)
                            )
                        }
                    }
                }

                // Option 4a: shelf (book ↔ article) and bibliographic details / RIS
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isArticle = document.category.equals("ARTICLE", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(0.dp), color = Color.White, border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier.weight(1f).clickable { onChangeCategory(if (isArticle) "BOOK" else "ARTICLE") }
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(if (isArticle) "Move to Books" else "Move to Articles", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("Now on the ${if (isArticle) "Articles" else "Books"} shelf", style = MaterialTheme.typography.labelSmall, color = Color(0xFF18181B))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(0.dp), color = Color.White, border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier.weight(1f).clickable { onOpenBibliography() }
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("Bibliography & RIS", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("ISBN, DOI, publisher… · import/export RIS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF18181B))
                        }
                    }
                }

                // Option 4b: make this file a chapter of a folder book
                if (!com.example.reader.FolderBooks.isFolder(document)) {
                    val inFolder = remember(document.id) { com.example.reader.FolderBooks.folderOf(document.id) != null }
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier.fillMaxWidth().clickable { onAddToFolderBook() }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(shape = RoundedCornerShape(6.dp), color = Color.White, border = BorderStroke(1.dp, Color.Black), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(imageVector = Icons.Default.CreateNewFolder, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (inFolder) "Move to another folder book…" else "Add to a folder book…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                                Text("Make this file a chapter of a book made of several files (e.g. an edited volume)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF18181B))
                            }
                        }
                    }
                }

                // Option 5: Edited volume — chapters with their own authors
                if (!document.category.equals("ARTICLE", ignoreCase = true) && !com.example.reader.FolderBooks.isFolder(document)) {
                    val isVolume = remember(document.id) { com.example.reader.EditedVolumes.isEditedVolume(context, document) }
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChapters() }
                            .testTag("context_menu_edited_volume")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color.Black),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isVolume) "Chapters & Authors" else "Edited Volume: Chapters & Authors",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = if (isVolume) "See who wrote each chapter; cite, read or export one chapter at a time"
                                    else "Treat each chapter as its own article with its own author. Tapping the book then shows its chapters",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF18181B)
                                )
                            }
                        }
                    }
                }

                // Recordings made while reading this book
                run {
                    var showRecordings by remember { mutableStateOf(false) }
                    val recChanges by com.example.reader.Recordings.changes.collectAsState()
                    val recCount = remember(document.id, recChanges) {
                        runCatching { com.example.reader.Recordings.list(context, document, settings).size }.getOrDefault(0)
                    }
                    Surface(
                        shape = RoundedCornerShape(0.dp), color = Color.White, border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier.fillMaxWidth().clickable { showRecordings = true }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (recCount > 0) "Recordings ($recCount)" else "Recordings", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                                Text("Talk while you read; play, transcribe and link what you said to your highlights", style = MaterialTheme.typography.labelSmall, color = Color(0xFF18181B))
                            }
                        }
                    }
                    if (showRecordings) RecordingsDialog(document) { showRecordings = false }
                }

                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                // Quick secondary actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenDocument,
                        border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("context_menu_open_book")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onTogglePinToWidget,
                        border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("context_menu_pin_widget")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (document.isPinnedToWidget) "Unpin" else "Pin Widget",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = onDeleteDocument,
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("context_menu_delete_book")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = Color(0xFFB91C1C),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

/**
 * Interactive Preview Dialog for `Notes on [Title].md` (Map of Content).
 * Allows the user to view the MOC structure, click on any linked note (`[[Note Title]]` or `[Note Title](...)`)
 * to inspect a full interactive preview of that note, copy or share it, or jump to its page in the reader.
 */
@Composable
fun InteractiveNotesMocPreviewDialog(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onOpenDocumentAtPage: (Int, HighlightEntity?) -> Unit,
    onExportSingleHighlight: (HighlightEntity) -> Unit,
    onExportMoc: () -> Unit,
    onOpenCompleteExportDialog: () -> Unit
) {
    // Edited volumes: preview the whole book or one chapter (its notes, author, book, editors, citation).
    val scope = rememberChapterScope(document, highlights)
    InteractiveNotesMocPreviewDialogBody(
        document = scope.document, highlights = scope.highlights, settings = settings, onDismiss = onDismiss,
        onOpenDocumentAtPage = onOpenDocumentAtPage, onExportSingleHighlight = onExportSingleHighlight,
        onExportMoc = onExportMoc, onOpenCompleteExportDialog = onOpenCompleteExportDialog,
        header = { ChapterScopeBar(scope) }
    )
}

@Composable
private fun InteractiveNotesMocPreviewDialogBody(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onOpenDocumentAtPage: (Int, HighlightEntity?) -> Unit,
    onExportSingleHighlight: (HighlightEntity) -> Unit,
    onExportMoc: () -> Unit,
    onOpenCompleteExportDialog: () -> Unit,
    header: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedHighlight by remember { mutableStateOf<HighlightEntity?>(null) }
    var showRawMocMarkdown by remember { mutableStateOf(false) }
    var showRawNoteMarkdown by remember { mutableStateOf(false) }

    val mocTitle = remember(document.title, settings.mocTitleFormat) {
        MarkdownExporter.getMocTitle(document.title, settings)
    }
    val mocFilename = remember(mocTitle) {
        "${MarkdownExporter.sanitizeFilename(mocTitle)}.md"
    }
    val mocMarkdown = remember(document, highlights, settings) {
        MarkdownExporter.generateMocMarkdown(document, highlights, settings)
    }

    val isPdf = remember(document.fileType) {
        document.fileType.equals("PDF", ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
            .testTag("interactive_notes_moc_preview_dialog"),
        title = {
            DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (selectedHighlight != null) {
                        IconButton(
                            onClick = { selectedHighlight = null },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("back_to_moc_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to $mocFilename",
                                tint = Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (selectedHighlight == null) {
                                mocFilename
                            } else {
                                "${MarkdownExporter.sanitizeFilename(selectedHighlight!!.title)}.md"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedHighlight == null) {
                                "Interactive MOC Preview • Click any note below to inspect"
                            } else {
                                "Linked from [[$mocTitle]] • ${MarkdownExporter.formatHighlightPageDisplay(selectedHighlight!!, isPdf)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                HorizontalDivider(color = Color(0xFFE4E4E7))
                Spacer(modifier = Modifier.height(10.dp))

                if (selectedHighlight == null) {
                    header()
                    // ==================== MOC INTERACTIVE VIEW ====================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (!showRawMocMarkdown) Color.Black else Color(0xFFF4F4F5),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showRawMocMarkdown = !showRawMocMarkdown }
                                .testTag("toggle_raw_moc_markdown_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (showRawMocMarkdown) Icons.Default.Visibility else Icons.Default.Code,
                                    contentDescription = null,
                                    tint = if (!showRawMocMarkdown) Color.White else Color.Black,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (showRawMocMarkdown) "Switch to Interactive View" else "View Raw .md Source",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!showRawMocMarkdown) Color.White else Color.Black
                                )
                            }
                        }

                        TextButton(
                            onClick = {
                                copyTextToClipboard(context, mocMarkdown, "Copied $mocFilename to clipboard")
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy MOC", tint = Color.Black, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy .md", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (showRawMocMarkdown) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(8.dp))
                                .background(Color(0xFFFAFAFA))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = mocMarkdown,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                ),
                                color = Color.Black
                            )
                        }
                    } else {
                        val defaultTagsList = remember(settings.defaultTags) {
                            settings.defaultTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        }
                        val documentTagsList = remember(document.tags) {
                            NoteSuggestionEngine.parseTagsList(document.tags)
                        }
                        val overviewTags = remember(defaultTagsList, documentTagsList) {
                            LinkedHashSet<String>().apply {
                                if (defaultTagsList.isEmpty()) add("Literature") else defaultTagsList.forEach { add(it) }
                                if (none { it.equals("Overview", ignoreCase = true) }) add("Overview")
                                documentTagsList.forEach { tag ->
                                    if (none { it.equals(tag, ignoreCase = true) }) add(tag)
                                }
                            }.toList()
                        }
                        val authorsList = remember(document.author) {
                            MarkdownExporter.parseAuthorNamesList(document.author)
                        }
                        val exportedSourceFilename = remember(document) {
                            MarkdownExporter.formatExportedSourceFilename(document)
                        }
                        val exportedCoverFilename = remember(document) {
                            MarkdownExporter.formatExportedCoverFilename(document)
                        }
                        val groupedHighlights = remember(highlights) {
                            highlights.groupBy { h ->
                                h.chapterTitle.trim().ifBlank {
                                    h.paragraphCitation.substringBefore(", ¶").trim()
                                }
                            }.entries.toList()
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 12.dp)
                        ) {
                            // Obsidian Properties / Frontmatter Header Card matching image.png
                            item {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFFAFAFA),
                                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = mocTitle,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color.Black
                                        )
                                        Text(
                                            text = "Properties",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF52525B)
                                        )

                                        // tags property row
                                        OverviewPropertyChipsRow(
                                            label = "tags",
                                            values = overviewTags,
                                            isWikiLink = false
                                        )

                                        // Author(s) property row
                                        if (authorsList.isNotEmpty()) {
                                            OverviewPropertyChipsRow(
                                                label = "Author(s)",
                                                values = authorsList.map { "[[$it]]" },
                                                isWikiLink = true
                                            )
                                        }

                                        // From property row
                                        OverviewPropertyTextRow(
                                            label = "From",
                                            value = "[[$exportedSourceFilename]]"
                                        )

                                        // Photo property row
                                        OverviewPropertyTextRow(
                                            label = "Photo",
                                            value = "[[$exportedCoverFilename]]"
                                        )
                                    }
                                }
                            }

                            if (document.summary.isNotBlank()) {
                                item(key = "moc_abstract_summary_card") {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFFFAFAFA),
                                        border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("moc_preview_abstract_card")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Abstract / Summary",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                            Text(
                                                text = document.summary.trim(),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Serif
                                                ),
                                                color = Color(0xFF18181B),
                                                modifier = Modifier.testTag("moc_preview_abstract_text")
                                            )
                                        }
                                    }
                                }
                            }

                            if (highlights.isEmpty()) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFFAFAFA),
                                        border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FormatQuote,
                                                contentDescription = null,
                                                tint = Color(0xFF71717A),
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Text(
                                                text = "No highlights or notes recorded yet.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.Black
                                            )
                                            Text(
                                                text = "Open the book and highlight any passage to populate $mocFilename.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF71717A)
                                            )
                                        }
                                    }
                                }
                            } else {
                                var globalIndex = 0
                                groupedHighlights.forEach { (chapterKey, chapterHighlights) ->
                                    if (chapterKey.isNotBlank()) {
                                        val headingText = MarkdownExporter.formatOverviewChapterHeading(chapterKey)
                                            .removePrefix("# ")
                                        item(key = "chapter_header_$chapterKey") {
                                            Text(
                                                text = headingText,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = Color.Black,
                                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                            )
                                        }
                                    }

                                    itemsIndexed(
                                        items = chapterHighlights,
                                        key = { _, h -> h.id }
                                    ) { _, highlight ->
                                        val itemTagIndex = globalIndex++
                                        val noteTitle = highlight.title.trim().ifBlank { "Untitled Note" }
                                        val linkLabel = if (settings.wikiLinkStyle) {
                                            "[[$noteTitle]]"
                                        } else {
                                            "[$noteTitle](${MarkdownExporter.sanitizeFilename(noteTitle)}.md)"
                                        }
                                        val locus = MarkdownExporter.formatLocusText(highlight, document)
                                        val highlightLink = MarkdownExporter.formatSourceHighlightWikiLink(
                                            highlight = highlight,
                                            document = document,
                                            wikiLinkStyle = settings.wikiLinkStyle
                                        )
                                        val bulletLine = buildString {
                                            append("• ")
                                            append(linkLabel)
                                            if (locus.isNotBlank()) {
                                                append(" - ")
                                                append(locus)
                                            }
                                            append(" ")
                                            append(highlightLink)
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color.White,
                                            border = BorderStroke(1.dp, Color.Black),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { selectedHighlight = highlight }
                                                .testTag("moc_note_link_item_$itemTagIndex")
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = bulletLine,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = Color.Black
                                                )

                                                // Highlighted quote snippet
                                                Text(
                                                    text = "“${highlight.highlightedText.trim()}”",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontStyle = FontStyle.Italic
                                                    ),
                                                    color = Color(0xFF3F3F46),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                if (highlight.userNotes.isNotBlank()) {
                                                    Text(
                                                        text = "My Notes: ${highlight.userNotes.trim()}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF52525B),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = if (locus.isNotBlank()) "Locus: $locus" else "Tap to preview individual note .md",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        color = Color(0xFF71717A),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "Link to highlight",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF2563EB),
                                                            modifier = Modifier.clickable {
                                                                onOpenDocumentAtPage(highlight.pageNumber, highlight)
                                                            }
                                                        )
                                                        Text(
                                                            text = "Preview Note →",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Black
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ==================== INDIVIDUAL CLICKED NOTE PREVIEW ====================
                    val note = selectedHighlight!!
                    val fullNoteMarkdown = remember(note, document, settings) {
                        MarkdownExporter.generateHighlightMarkdown(note, document, settings)
                    }
                    val pageDisplay = remember(note, isPdf) {
                        MarkdownExporter.formatHighlightPageDisplay(note, isPdf)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { selectedHighlight = null },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Back to $mocFilename",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { showRawNoteMarkdown = !showRawNoteMarkdown }
                            ) {
                                Icon(
                                    imageVector = if (showRawNoteMarkdown) Icons.Default.Visibility else Icons.Default.Code,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (showRawNoteMarkdown) "Formatted" else "Raw .md",
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                            }
                            TextButton(
                                onClick = {
                                    copyTextToClipboard(context, fullNoteMarkdown, "Note Markdown copied")
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color.Black,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp, color = Color.Black)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!showRawNoteMarkdown) {
                            // Formatted Interactive Note Card
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFFAFAFA),
                                border = BorderStroke(1.dp, Color.Black),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("clicked_note_formatted_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = note.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.Black
                                    )

                                    val exportedSourceFilename = remember(document) {
                                        MarkdownExporter.formatExportedSourceFilename(document)
                                    }
                                    val noteAuthors = remember(document.author) {
                                        MarkdownExporter.parseAuthorNamesList(document.author)
                                    }
                                    val noteLocus = remember(note, document) {
                                        MarkdownExporter.formatLocusText(note, document)
                                    }
                                    val noteTags = remember(note.tags, settings.defaultTags) {
                                        val defaults = settings.defaultTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                        val specifics = NoteSuggestionEngine.parseTagsList(note.tags)
                                        LinkedHashSet<String>().apply {
                                            if (defaults.isEmpty()) add("Literature") else defaults.forEach { add(it) }
                                            if (none { it.equals("Book_note", ignoreCase = true) || it.equals("Book note", ignoreCase = true) }) add("Book_note")
                                            specifics.forEach { t -> if (none { it.equals(t, ignoreCase = true) }) add(t) }
                                        }.toList()
                                    }

                                    OverviewPropertyTextRow(
                                        label = "From Book",
                                        value = "[[$exportedSourceFilename]]"
                                    )
                                    if (noteAuthors.isNotEmpty()) {
                                        OverviewPropertyChipsRow(
                                            label = "Author(s)",
                                            values = noteAuthors.map { "[[$it]]" },
                                            isWikiLink = true
                                        )
                                    }
                                    if (noteLocus.isNotBlank()) {
                                        OverviewPropertyTextRow(
                                            label = "Locus",
                                            value = noteLocus
                                        )
                                    }
                                    OverviewPropertyChipsRow(
                                        label = "tags",
                                        values = noteTags,
                                        isWikiLink = false
                                    )

                                    // Blockquote Box
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White)
                                            .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(6.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = "\"${note.highlightedText.trim()}\"",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    lineHeight = 20.sp
                                                ),
                                                color = Color.Black
                                            )
                                            val footerLine = remember(note, document, settings) {
                                                MarkdownExporter.formatIndividualNoteQuoteFooterLine(note, document, settings)
                                                    .removePrefix(">")
                                            }
                                            Text(
                                                text = footerLine,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = Color(0xFF27272A)
                                            )
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "# My Notes:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                        if (note.userNotes.isNotBlank()) {
                                            Text(
                                                text = note.userNotes.trim(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF27272A)
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = Color(0xFFE4E4E7))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Part of [[$mocTitle]]",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = Color(0xFF52525B),
                                            modifier = Modifier.clickable { selectedHighlight = null }
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { onExportSingleHighlight(note) },
                                                border = BorderStroke(1.dp, Color.Black),
                                                shape = RoundedCornerShape(16.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Share .md", fontSize = 11.sp, color = Color.Black)
                                            }

                                            Button(
                                                onClick = { onOpenDocumentAtPage(note.pageNumber, note) },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color.Black,
                                                    contentColor = Color.White
                                                ),
                                                shape = RoundedCornerShape(16.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("open_note_in_reader_btn")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                val openBtnLabel = if (isPdf) {
                                                    "Open p. ${note.pageNumber}"
                                                } else if (note.pageEquivalent.isNotBlank()) {
                                                    "Open p. eq. ${note.pageEquivalent}"
                                                } else {
                                                    "Open Passage"
                                                }
                                                Text(openBtnLabel, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Always show or allow inspecting the generated Markdown file content
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Generated Markdown File (${MarkdownExporter.sanitizeFilename(note.title)}.md):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF52525B)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFAFAFA))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = fullNoteMarkdown,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportMoc,
                    border = BorderStroke(1.dp, Color.Black),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("moc_preview_export_moc_btn")
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share MOC", color = Color.Black, fontSize = 12.sp)
                }

                Button(
                    onClick = onOpenCompleteExportDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("moc_preview_open_bundle_export_btn")
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export All + Source", fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Black)
            }
        }
    )
}

/**
 * Dialog for exporting all notes (`Notes on X.md`, all individual highlight `.md` notes, `highlight.md`),
 * the original source file (`.pdf`, `.epub`, `.html`, `.txt`), and the book cover image (`cover.jpg`),
 * letting the user choose between:
 *  1. **ZIP Archive (`.zip`)**
 *  2. **Regular Folder** (saved to the chosen folder on disk)
 */
@Composable
fun CompleteBundleExportDialog(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onExportAsZip: ((MarkdownExporter.ExportBundleResult) -> Unit) -> Unit,
    onExportAsFolder: (File?, (MarkdownExporter.ExportBundleResult) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var selectedFormatIsZip by remember { mutableStateOf(true) }
    var lastExportResult by remember { mutableStateOf<MarkdownExporter.ExportBundleResult?>(null) }

    val defaultParentDir = remember(settings.customStoragePath, settings.storageLocationType) {
        File(
            StorageManager.getRootDirectory(context, settings.customStoragePath, settings.storageLocationType),
            "Exports"
        )
    }
    var customTargetFolderPath by remember(defaultParentDir) {
        mutableStateOf(defaultParentDir.absolutePath)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uri.path ?: uri.toString()
            val cleanPath = if (path.contains(":")) path.substringAfter(":") else path
            val resolved = if (!cleanPath.startsWith("/")) "/storage/emulated/0/$cleanPath" else cleanPath
            customTargetFolderPath = resolved
        }
    }

    val mocTitle = remember(document.title, settings.mocTitleFormat) {
        MarkdownExporter.getMocTitle(document.title, settings)
    }
    val mocFilename = remember(mocTitle) {
        "${MarkdownExporter.sanitizeFilename(mocTitle)}.md"
    }
    val sourceFileName = remember(document) {
        MarkdownExporter.formatExportedSourceFilename(document)
    }
    val coverFileName = remember(document) {
        MarkdownExporter.formatExportedCoverFilename(document)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
            .testTag("complete_bundle_export_dialog"),
        title = {
            DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export All Notes & Source File",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF71717A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black, modifier = Modifier.size(18.dp))
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
                // Included Items Manifest
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFAFAFA),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Included in Export Bundle:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        BundleManifestRow(
                            icon = Icons.Default.Description,
                            title = mocFilename,
                            subtitle = "Overview file (Notes on ${document.title})"
                        )
                        BundleManifestRow(
                            icon = Icons.Default.FormatQuote,
                            title = "${highlights.size} Individual Note Files (.md)",
                            subtitle = if (highlights.isEmpty()) "Ready for individual highlight notes"
                            else "All ${highlights.size} individual highlight notes"
                        )
                        BundleManifestRow(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            title = sourceFileName,
                            subtitle = "Original ${document.fileType.uppercase()} source document file"
                        )
                        BundleManifestRow(
                            icon = Icons.Default.Image,
                            title = coverFileName,
                            subtitle = "Book front cover image (WebP)"
                        )
                    }
                }

                // Format Choice: ZIP Archive vs Regular Folder
                Text(
                    text = "Choose Export Format:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Option A: ZIP Archive
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedFormatIsZip) Color.Black else Color.White,
                        border = BorderStroke(1.dp, Color.Black),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedFormatIsZip = true
                                lastExportResult = null
                            }
                            .testTag("export_format_zip_option")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    tint = if (selectedFormatIsZip) Color.White else Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "ZIP Archive",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedFormatIsZip) Color.White else Color.Black
                                )
                            }
                            Text(
                                text = "Single .zip file ready to share or save anywhere",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedFormatIsZip) Color(0xFFE4E4E7) else Color(0xFF71717A)
                            )
                        }
                    }

                    // Option B: Regular Folder
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (!selectedFormatIsZip) Color.Black else Color.White,
                        border = BorderStroke(1.dp, Color.Black),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedFormatIsZip = false
                                lastExportResult = null
                            }
                            .testTag("export_format_folder_option")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = if (!selectedFormatIsZip) Color.White else Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Regular Folder",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!selectedFormatIsZip) Color.White else Color.Black
                                )
                            }
                            Text(
                                text = "Uncompressed folder with all files on disk",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!selectedFormatIsZip) Color(0xFFE4E4E7) else Color(0xFF71717A)
                            )
                        }
                    }
                }

                // If Regular Folder is selected, let user view/edit/pick destination folder
                AnimatedVisibility(visible = !selectedFormatIsZip) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = customTargetFolderPath,
                            onValueChange = { customTargetFolderPath = it },
                            label = { Text("Destination Parent Folder") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export_folder_destination_input"),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                                    Icon(Icons.Default.Folder, contentDescription = "Pick Folder", tint = Color.Black)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color(0xFFD4D4D8),
                                focusedLabelColor = Color.Black
                            )
                        )
                        Text(
                            text = "Creates subfolder: $customTargetFolderPath/${MarkdownExporter.sanitizeFilename(document.title)}/",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = Color(0xFF52525B)
                        )
                    }
                }

                // Result Banner after export completes
                val result = lastExportResult
                if (result != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF0FDF4),
                        border = BorderStroke(1.dp, Color(0xFF16A34A)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_bundle_success_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF15803D),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (result.isZip) {
                                        "Exported ZIP (${result.includedFiles.size} files)"
                                    } else {
                                        "Exported Regular Folder (${result.includedFiles.size} files)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF14532D)
                                )
                            }
                            Text(
                                text = result.outputFileOrFolder.absolutePath,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = Color(0xFF166534)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        copyTextToClipboard(
                                            context,
                                            result.outputFileOrFolder.absolutePath,
                                            "Export path copied to clipboard"
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = Color(0xFF15803D),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Path", fontSize = 11.sp, color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedFormatIsZip) {
                        onExportAsZip { res ->
                            lastExportResult = res
                            Toast.makeText(
                                context,
                                "Exported ${res.includedFiles.size} files to ${res.outputFileOrFolder.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        val parentDir = customTargetFolderPath.trim().takeIf { it.isNotEmpty() }?.let { File(it) }
                        onExportAsFolder(parentDir) { res ->
                            lastExportResult = res
                            Toast.makeText(
                                context,
                                "Exported folder (${res.includedFiles.size} files) to ${res.outputFileOrFolder.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("confirm_complete_bundle_export_btn")
            ) {
                Icon(
                    imageVector = if (selectedFormatIsZip) Icons.Default.Archive else Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (selectedFormatIsZip) "Export as .ZIP" else "Export to Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Black)
            }
        }
    )
}

@Composable
private fun OverviewPropertyChipsRow(
    label: String,
    values: List<String>,
    isWikiLink: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF52525B),
            modifier = Modifier.width(68.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            values.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isWikiLink) Color(0xFFEFF6FF) else Color(0xFFF3E8FF),
                    border = BorderStroke(
                        0.5.dp,
                        if (isWikiLink) Color(0xFFBFDBFE) else Color(0xFFD8B4FE)
                    )
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFamily = if (isWikiLink) FontFamily.Monospace else FontFamily.Default
                        ),
                        fontWeight = FontWeight.Medium,
                        color = if (isWikiLink) Color(0xFF1D4ED8) else Color(0xFF6B21A8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewPropertyTextRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF52525B),
            modifier = Modifier.width(68.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = Color(0xFF18181B),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BundleManifestRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Color(0xFF71717A)
            )
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Marginalia Lectoris Note", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

/**
 * Dialog for editing a Book or Article's metadata (Title, Author(s), Tags) and uploading or replacing
 * its cover image. Author(s), Tags, and Cover are automatically included in `Notes on X.md` (Overview)
 * and bundle exports.
 */
@Composable
fun EditDocumentInfoDialog(
    document: DocumentEntity,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onSave: (newTitle: String, newAuthor: String, newTags: String, newCoverBitmap: Bitmap?, resetToDefaultCover: Boolean) -> Unit,
    onSaveWithSummary: ((newTitle: String, newAuthor: String, newSummary: String, newTags: String, newCoverBitmap: Bitmap?, resetToDefaultCover: Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    var titleInput by remember(document.id, document.title) { mutableStateOf(document.title) }
    var authorInput by remember(document.id, document.author) { mutableStateOf(document.author) }
    var summaryInput by remember(document.id, document.summary) { mutableStateOf(document.summary) }
    var activeTags by remember(document.id, document.tags) {
        mutableStateOf(NoteSuggestionEngine.parseTagsList(document.tags))
    }
    var customTagInput by remember { mutableStateOf("") }

    var currentCoverBitmap by remember(document.id) { mutableStateOf<Bitmap?>(null) }
    var uploadedCoverBitmap by remember(document.id) { mutableStateOf<Bitmap?>(null) }
    var resetToGeneratedCover by remember(document.id) { mutableStateOf(false) }

    // Load current cover preview on dialog open
    androidx.compose.runtime.LaunchedEffect(document.id, document.coverUri, document.filePath) {
        withContext(Dispatchers.IO) {
            try {
                if (!document.coverUri.isNullOrBlank()) {
                    val f = File(document.coverUri)
                    if (f.exists() && f.length() > 0L) {
                        currentCoverBitmap = BitmapFactory.decodeFile(f.absolutePath)
                        return@withContext
                    }
                }
                val rootDir = StorageManager.getRootDirectory(context, settings.customStoragePath, settings.storageLocationType)
                val coversDir = File(rootDir, ".covers")
                val cached = listOf(
                    File(coversDir, "pdf_${document.id}.jpg"),
                    File(coversDir, "epub_${document.id}.jpg"),
                    File(coversDir, "daisy_${document.id}.jpg"),
                    File(coversDir, "html_${document.id}.jpg"),
                    File(coversDir, "txt_${document.id}.jpg")
                ).firstOrNull { it.exists() && it.length() > 0L }
                if (cached != null) {
                    currentCoverBitmap = BitmapFactory.decodeFile(cached.absolutePath)
                    return@withContext
                }
                val resolvedCover = MarkdownExporter.resolveOrGenerateCoverFile(context, document, settings)
                if (resolvedCover != null && resolvedCover.exists()) {
                    currentCoverBitmap = BitmapFactory.decodeFile(resolvedCover.absolutePath)
                }
            } catch (_: Throwable) {}
        }
    }

    val decodeCoverUri: (Uri) -> Unit = { uri ->
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                if (decoded != null) {
                    // Scale down if extraordinarily large while keeping crisp aspect ratio
                    val maxDim = 1400
                    val finalBmp = if (decoded.width > maxDim || decoded.height > maxDim) {
                        val ratio = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height)
                        val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                        val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(decoded, w, h, true)
                    } else {
                        decoded
                    }
                    withContext(Dispatchers.Main) {
                        uploadedCoverBitmap = finalBmp
                        resetToGeneratedCover = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Could not decode image file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to load cover image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) decodeCoverUri(uri)
    }

    val imageDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) decodeCoverUri(uri)
    }

    val suggestedTags = remember(titleInput, authorInput, activeTags, document.summary) {
        NoteSuggestionEngine.suggestTags(
            highlightedText = "${titleInput} ${document.summary}",
            userNotes = authorInput,
            chapterTitle = document.category,
            documentTitle = titleInput,
            existingTags = activeTags
        )
    }

    val mocTitle = remember(titleInput, settings.mocTitleFormat) {
        MarkdownExporter.getMocTitle(titleInput.ifBlank { document.title }, settings)
    }
    val mocFilename = remember(mocTitle) {
        "${MarkdownExporter.sanitizeFilename(mocTitle)}.md"
    }

    val previewCoverBitmap = remember(uploadedCoverBitmap, currentCoverBitmap, resetToGeneratedCover, titleInput, authorInput) {
        when {
            uploadedCoverBitmap != null -> uploadedCoverBitmap
            resetToGeneratedCover -> try {
                EpubParser.generateBookCoverBitmap(
                    titleInput.ifBlank { document.title },
                    authorInput.ifBlank { "Unknown Author" }
                )
            } catch (_: Throwable) { currentCoverBitmap }
            else -> currentCoverBitmap
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
            .testTag("edit_document_info_dialog"),
        title = {
            DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Edit Book / Article Info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "Synced with $mocFilename on export",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF71717A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Cover Image Section (Upload or Replace)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFAFAFA),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(76.dp)
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFFD4D4D8), RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .clickable {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .testTag("edit_doc_cover_preview_box"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (previewCoverBitmap != null) {
                                Image(
                                    bitmap = previewCoverBitmap.asImageBitmap(),
                                    contentDescription = "Book Cover Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = "No Cover",
                                    tint = Color(0xFF71717A),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Front Cover Image",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = if (uploadedCoverBitmap != null) {
                                    "New custom cover selected • Ready to save"
                                } else {
                                    "Upload a custom cover image or replace the existing cover"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uploadedCoverBitmap != null) Color(0xFF15803D) else Color(0xFF52525B)
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        } catch (_: Throwable) {
                                            imageDocumentLauncher.launch(arrayOf("image/*"))
                                        }
                                    },
                                    border = BorderStroke(1.dp, Color.Black),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("upload_cover_image_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileUpload,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (previewCoverBitmap != null) "Replace Cover" else "Upload Cover",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Black
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        uploadedCoverBitmap = null
                                        resetToGeneratedCover = true
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("reset_default_cover_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset to auto-generated cover",
                                        tint = Color(0xFF52525B),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 1b. Online lookup (DOI, ISBN, ISSN or title) with a field-by-field comparison.
                var showLookup by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showLookup = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.Black),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                ) {
                    Text("Look up details online (DOI, ISBN, ISSN or title)…", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                if (showLookup) {
                    val before = remember { com.example.reader.BibStore.init(context); com.example.reader.BibStore.get(document.id) }
                    BibliographyEditorDialog(document.copy(title = titleInput, author = authorInput, summary = summaryInput)) {
                        showLookup = false
                        // A title chosen from the lookup also becomes the book's title here.
                        val after = com.example.reader.BibStore.get(document.id)
                        val t = after[com.example.reader.BibField.TITLE]; val st = after[com.example.reader.BibField.SUBTITLE]
                        if (t.isNotBlank() && (t != before[com.example.reader.BibField.TITLE] || st != before[com.example.reader.BibField.SUBTITLE])) {
                            titleInput = if (st.isNotBlank() && !t.endsWith(st, true)) "$t: $st" else t
                        }
                    }
                }

                // 2. Title Field
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Title") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_doc_title_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color(0xFFD4D4D8),
                        focusedLabelColor = Color.Black
                    )
                )

                // 3. Author(s) Field
                OutlinedTextField(
                    value = authorInput,
                    onValueChange = { authorInput = it },
                    label = { Text("Author(s)") },
                    placeholder = { Text("e.g. Albert Camus, Jean-Paul Sartre") },
                    supportingText = {
                        val parsedAuthors = MarkdownExporter.parseAuthorNamesList(authorInput)
                        val previewText = if (parsedAuthors.isNotEmpty()) {
                            "Exported as: ${parsedAuthors.joinToString(", ") { "\"[[$it]]\"" }}"
                        } else {
                            "Separate multiple authors with commas (,)"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = previewText,
                                color = Color(0xFF71717A),
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Normalise Capitalisation",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        val list = DocumentMetadataHelper.parseAuthors(authorInput)
                                        authorInput = if (list.isNotEmpty()) {
                                            list.joinToString(", ") { DocumentMetadataHelper.normalizeAuthorName(it) }
                                        } else {
                                            DocumentMetadataHelper.normalizeAuthorName(authorInput)
                                        }
                                    }
                                    .padding(start = 8.dp)
                                    .testTag("edit_doc_normalise_author_btn")
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_doc_author_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color(0xFFD4D4D8),
                        focusedLabelColor = Color.Black
                    )
                )

                // 3b. Abstract / Summary Field
                OutlinedTextField(
                    value = summaryInput,
                    onValueChange = { summaryInput = it },
                    label = { Text("Abstract / Summary") },
                    placeholder = { Text("Document abstract or summary…") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_doc_summary_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color(0xFFD4D4D8),
                        focusedLabelColor = Color.Black
                    )
                )

                // 4. Tags Section (Included in Notes on X.md overview frontmatter)
                var isGeneratingDialogAiTags by remember { mutableStateOf(false) }
                var dialogAiSuggestedTags by remember { mutableStateOf<List<String>>(emptyList()) }
                var dialogAiTagsLang by remember { mutableStateOf<String?>(null) }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFAFAFA),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Book / Article Overview Tags (${activeTags.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Added to $mocFilename (separate from highlight tags)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color(0xFF71717A)
                                )
                            }

                            val isOnline = NoteAiAssistant.isInternetAvailable(context)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isOnline) Color.Black else Color(0xFFF4F4F5),
                                border = BorderStroke(1.dp, if (isOnline) Color.Black else Color(0xFFD4D4D8)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = !isGeneratingDialogAiTags) {
                                        if (!NoteAiAssistant.isInternetAvailable(context)) {
                                            Toast.makeText(
                                                context,
                                                "Internet connection required for AI tag suggestions",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@clickable
                                        }
                                        isGeneratingDialogAiTags = true
                                        coroutineScope.launch {
                                            val result = NoteAiAssistant.suggestBookOverviewTags(context = context,
                                                documentTitle = titleInput.ifBlank { document.title },
                                                author = authorInput.ifBlank { document.author },
                                                category = document.category,
                                                summary = document.summary,
                                                sampleHighlights = emptyList(),
                                                existingTags = activeTags
                                            )
                                            isGeneratingDialogAiTags = false
                                            result.onSuccess { aiResp ->
                                                dialogAiSuggestedTags = aiResp.tags.filterNot { candidate ->
                                                    activeTags.any { it.equals(candidate, ignoreCase = true) }
                                                }
                                                dialogAiTagsLang = aiResp.detectedLanguage
                                            }.onFailure {
                                                Toast.makeText(
                                                    context,
                                                    it.message ?: "Could not generate AI tags",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    .testTag("edit_doc_ai_spark_tags_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isGeneratingDialogAiTags) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.8.dp,
                                            color = if (isOnline) Color.White else Color.Black
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "AI Suggest Overview Tags",
                                            tint = if (isOnline) Color.White else Color(0xFF71717A),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Text(
                                        text = "AI Tags",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOnline) Color.White else Color(0xFF71717A)
                                    )
                                }
                            }
                        }

                        if (activeTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeTags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Black,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { activeTags = activeTags.filterNot { it == tag } }
                                            .testTag("edit_doc_active_tag_$tag")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove tag $tag",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No custom tags added yet. Add tags below to include them in the YAML frontmatter of $mocFilename.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                        }

                        val remainingDialogAiTags = dialogAiSuggestedTags.filterNot { candidate ->
                            activeTags.any { it.equals(candidate, ignoreCase = true) }
                        }
                        AnimatedVisibility(visible = remainingDialogAiTags.isNotEmpty()) {
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
                                            text = "AI Suggested Overview Tags (${dialogAiTagsLang ?: "Auto"}):",
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
                                                activeTags = NoteSuggestionEngine.parseTagsList(
                                                    (activeTags + remainingDialogAiTags).joinToString(", ")
                                                )
                                            }
                                            .testTag("edit_doc_add_all_ai_tags_btn")
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    remainingDialogAiTags.forEachIndexed { idx, aiTag ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFFF3E8FF),
                                            border = BorderStroke(1.dp, Color(0xFFC084FC)),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    activeTags = NoteSuggestionEngine.parseTagsList(
                                                        (activeTags + aiTag).joinToString(", ")
                                                    )
                                                }
                                                .testTag("edit_doc_ai_tag_chip_$idx")
                                        ) {
                                            Text(
                                                text = "+ #$aiTag",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF581C87),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customTagInput,
                                onValueChange = { customTagInput = it },
                                label = { Text("Add Tag(s) (comma-separated)") },
                                placeholder = { Text("e.g. philosophy, existentialism") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_doc_tag_input"),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Black,
                                    unfocusedBorderColor = Color(0xFFD4D4D8),
                                    focusedLabelColor = Color.Black
                                )
                            )
                            Button(
                                onClick = {
                                    val newParsed = NoteSuggestionEngine.parseTagsList(customTagInput)
                                    if (newParsed.isNotEmpty()) {
                                        activeTags = NoteSuggestionEngine.parseTagsList(
                                            (activeTags + newParsed).joinToString(", ")
                                        )
                                        customTagInput = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("edit_doc_add_tag_btn")
                            ) {
                                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        PreviousTagsPicker(selected = activeTags, typed = customTagInput, forNotes = false) { picked ->
                            activeTags = NoteSuggestionEngine.parseTagsList((activeTags + picked).joinToString(", "))
                            if (customTagInput.isNotBlank() && !customTagInput.contains(',')) customTagInput = ""
                        }

                        if (suggestedTags.isNotEmpty()) {
                            Text(
                                text = "Suggested Tags (tap to add):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF52525B)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                suggestedTags.forEach { suggestion ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                activeTags = NoteSuggestionEngine.parseTagsList(
                                                    (activeTags + suggestion).joinToString(", ")
                                                )
                                            }
                                            .testTag("edit_doc_suggested_tag_$suggestion")
                                    ) {
                                        Text(
                                            text = "+ #$suggestion",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Black,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pendingInputTags = NoteSuggestionEngine.parseTagsList(customTagInput)
                    val combinedTags = NoteSuggestionEngine.joinTagsList(activeTags + pendingInputTags)
                    val finalTitle = titleInput.trim().ifBlank { document.title }
                    val finalAuthor = authorInput.trim().ifBlank { "Unknown Author" }
                    val finalSummary = DocumentMetadataHelper.cleanSelectedAbstractText(summaryInput)
                    if (onSaveWithSummary != null) {
                        onSaveWithSummary(
                            finalTitle,
                            finalAuthor,
                            finalSummary,
                            combinedTags,
                            uploadedCoverBitmap,
                            resetToGeneratedCover
                        )
                    } else {
                        onSave(
                            finalTitle,
                            finalAuthor,
                            combinedTags,
                            uploadedCoverBitmap,
                            resetToGeneratedCover
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("save_document_info_btn")
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

/**
 * Dialog shown when the user selects "Share" from the long-press context menu on a book or article.
 * Provides three distinct sharing workflows:
 *  1. Share the source book/article file (`.epub`, `.pdf`, `.html`, `.daisy`, `.md`, `.txt`).
 *  2. Share one note as `.md` OR select multiple notes and share them together.
 *  3. Share all notes and the Map of Content (`Notes on [Title].md`) file, explicitly leaving out
 *     the source book/article file and the cover image file.
 */
@Composable
fun ShareDocumentOptionsDialog(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onShareSourceFile: () -> Unit,
    onShareSelectedNotes: (List<HighlightEntity>) -> Unit,
    onShareAllNotesAndMocOnly: (asZip: Boolean) -> Unit
) {
    val mocTitle = remember(document.title, settings.mocTitleFormat) {
        MarkdownExporter.getMocTitle(document.title, settings)
    }
    val mocFilename = remember(mocTitle) {
        "${MarkdownExporter.sanitizeFilename(mocTitle)}.md"
    }
    val exportedSourceFilename = remember(document) {
        MarkdownExporter.formatExportedSourceFilename(document)
    }
    val exportedCoverFilename = remember(document) {
        MarkdownExporter.formatExportedCoverFilename(document)
    }
    val fileTypeLabel = remember(document.fileType) {
        document.fileType.uppercase().ifBlank { "DOCUMENT" }
    }

    var isNoteSelectionStep by remember { mutableStateOf(false) }
    var selectedNoteIds by remember(document.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var shareAllNotesAsZip by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black)
            .testTag("share_document_options_dialog"),
        title = {
            DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isNoteSelectionStep) {
                        IconButton(
                            onClick = { isNoteSelectionStep = false },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("share_dialog_back_to_options_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Share Options",
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isNoteSelectionStep) {
                                "Select Note(s) to Share (.md)"
                            } else {
                                "Share Book / Article & Notes"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${document.title} • $fileTypeLabel • ${highlights.size} notes",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = Color(0xFF18181B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                if (!isNoteSelectionStep) {
                    // ==================== STEP 1: SHARE OPTIONS MENU ====================

                    // Option A: Share the EPUB / HTML / PDF / etc. source file
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onShareSourceFile()
                                onDismiss()
                            }
                            .testTag("share_option_source_file")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = KaleidoTerracottaFill,
                                border = BorderStroke(1.dp, Color.Black),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = KaleidoTerracottaInk,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Share $fileTypeLabel File",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Shares only the source file: $exportedSourceFilename",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF18181B)
                                )
                            }
                        }
                    }

                    // Option B: Share One Note as .md or Select Multiple and Share Them
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isNoteSelectionStep = true
                            }
                            .testTag("share_option_select_notes_md")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = KaleidoAmberFill,
                                border = BorderStroke(1.dp, Color.Black),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.FormatQuote,
                                        contentDescription = null,
                                        tint = KaleidoAmberInk,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Share One Note (.md) or Select Multiple",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = if (highlights.isEmpty()) {
                                        "No notes recorded yet — open the book to highlight and create notes"
                                    } else {
                                        "Choose 1 note or check multiple notes (${highlights.size} available) to share as .md"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF18181B)
                                )
                            }
                        }
                    }

                    // Option C: Share All Notes & Map of Content (Leaving Out Source File & Cover Image)
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_option_all_notes_and_moc_only")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onShareAllNotesAndMocOnly(shareAllNotesAsZip)
                                        onDismiss()
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = KaleidoSageFill,
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = KaleidoSageInk,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Share All Notes & Map of Content",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "Shares all ${highlights.size} note .md files + $mocFilename — leaves out $exportedSourceFilename and $exportedCoverFilename",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF18181B)
                                    )
                                }
                            }

                            // Format selector + immediate Share button for Option C
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (!shareAllNotesAsZip) KaleidoSageFill else Color.White,
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { shareAllNotesAsZip = false }
                                        .testTag("share_all_notes_moc_mode_md_files")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (!shareAllNotesAsZip) {
                                            Text(
                                                text = "✓ ",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }
                                        Text(
                                            text = "Individual .md Files",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                            fontWeight = if (!shareAllNotesAsZip) FontWeight.Bold else FontWeight.Medium,
                                            color = Color.Black
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (shareAllNotesAsZip) KaleidoSageFill else Color.White,
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { shareAllNotesAsZip = true }
                                        .testTag("share_all_notes_moc_mode_zip")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (shareAllNotesAsZip) {
                                            Text(
                                                text = "✓ ",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }
                                        Text(
                                            text = ".ZIP Archive (.md only)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                            fontWeight = if (shareAllNotesAsZip) FontWeight.Bold else FontWeight.Medium,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    onShareAllNotesAndMocOnly(shareAllNotesAsZip)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("share_all_notes_and_moc_confirm_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (shareAllNotesAsZip) {
                                        "Share Notes + MOC as .ZIP (No $fileTypeLabel / Image)"
                                    } else {
                                        "Share ${highlights.size + 1} .md Files (Notes + MOC Only)"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    // ==================== STEP 2: SELECT ONE OR MULTIPLE NOTES ====================
                    if (highlights.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = Color(0xFFFAFAFA),
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FormatQuote,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "No notes recorded for this ${document.category.lowercase()} yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Open the document and highlight any text passage to create Markdown notes.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF52525B)
                                )
                            }
                        }
                    } else {
                        // Top Selection Toolbar: Select All / Clear + Selected Count
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedNoteIds.size} of ${highlights.size} selected",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.Black
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (selectedNoteIds.size == highlights.size) KaleidoSageFill else Color.White,
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier
                                        .clickable {
                                            selectedNoteIds = highlights.map { it.id }.toSet()
                                        }
                                        .testTag("share_notes_select_all_btn")
                                ) {
                                    Text(
                                        text = "Select All",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (selectedNoteIds.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .clickable { selectedNoteIds = emptySet() }
                                            .testTag("share_notes_clear_selection_btn")
                                    ) {
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // List of notes with multi-select checkboxes and quick 1-tap "Share 1 .md" button
                        highlights.forEachIndexed { idx, highlight ->
                            val isSelected = highlight.id in selectedNoteIds
                            val noteTitle = highlight.title.trim().ifBlank { "Note #${idx + 1}" }
                            val noteFilename = "${MarkdownExporter.sanitizeFilename(noteTitle)}.md"
                            val locus = MarkdownExporter.formatLocusText(highlight, document)

                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = if (isSelected) KaleidoSageFill else Color.White,
                                border = BorderStroke(if (isSelected) 2.dp else 1.5.dp, Color.Black),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedNoteIds = if (isSelected) {
                                            selectedNoteIds - highlight.id
                                        } else {
                                            selectedNoteIds + highlight.id
                                        }
                                    }
                                    .testTag("share_note_selectable_item_$idx")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // E-Ink Square Checkbox with Check Mark
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .border(1.5.dp, Color.Black)
                                            .background(if (isSelected) Color.Black else Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Text(
                                                text = "✓",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = noteFilename,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "“${highlight.highlightedText.trim()}”",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontStyle = FontStyle.Italic
                                            ),
                                            color = Color(0xFF27272A),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (locus.isNotBlank()) {
                                            Text(
                                                text = locus,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 9.sp
                                                ),
                                                color = Color(0xFF52525B)
                                            )
                                        }
                                    }

                                    // Direct 1-tap share for a single note as .md
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .clickable {
                                                onShareSelectedNotes(listOf(highlight))
                                                onDismiss()
                                            }
                                            .testTag("share_single_note_quick_btn_$idx")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share $noteFilename",
                                                tint = Color.Black,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = ".md",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isNoteSelectionStep && highlights.isNotEmpty()) {
                val selectedHighlights = highlights.filter { it.id in selectedNoteIds }
                Button(
                    onClick = {
                        if (selectedHighlights.isNotEmpty()) {
                            onShareSelectedNotes(selectedHighlights)
                            onDismiss()
                        }
                    },
                    enabled = selectedHighlights.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE4E4E7),
                        disabledContentColor = Color(0xFF71717A)
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.testTag("share_selected_notes_confirm_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (selectedHighlights.size) {
                            0 -> "Select Note(s) to Share"
                            1 -> "Share 1 Note (.md)"
                            else -> "Share ${selectedHighlights.size} Notes (.md)"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        dismissButton = {
            if (isNoteSelectionStep) {
                OutlinedButton(
                    onClick = { isNoteSelectionStep = false },
                    border = BorderStroke(1.5.dp, Color.Black),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.testTag("share_notes_step_back_btn")
                ) {
                    Text("Back", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.5.dp, Color.Black),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.testTag("share_dialog_cancel_btn")
                ) {
                    Text("Cancel", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    )
}


