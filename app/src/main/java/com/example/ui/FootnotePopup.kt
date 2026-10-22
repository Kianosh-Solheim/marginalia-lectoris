package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.BookChapter
import com.example.reader.EpubFootnoteEntry
import com.example.ui.theme.DisableDialogDimming
import com.example.ui.theme.KaleidoAmberBorder
import com.example.ui.theme.KaleidoAmberFill
import com.example.ui.theme.KaleidoAmberInk

@Composable
fun FootnotePopup(
    footnoteNumber: String,
    footnoteText: String,
    footnoteEntry: EpubFootnoteEntry? = null,
    onJumpToReference: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
            .testTag("footnote_popup_dialog"),
        title = {
            DisableDialogDimming()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = KaleidoAmberFill,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, KaleidoAmberBorder)
                ) {
                    Text(
                        text = "Note [$footnoteNumber]",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = KaleidoAmberInk
                    )
                }
                Text(
                    text = "In-File Footnote",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = footnoteText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF18181B),
                    lineHeight = 22.sp,
                    modifier = Modifier.testTag("footnote_popup_body_text")
                )

                val sourceSnippet = footnoteEntry?.sourceContextSnippet?.trim().orEmpty()
                val sourceChapter = footnoteEntry?.sourceChapterTitle?.trim().orEmpty()
                val sourcePara = footnoteEntry?.sourceParagraphNumber ?: 0
                if (sourceSnippet.isNotEmpty() || sourceChapter.isNotEmpty()) {
                    HorizontalDivider(color = Color(0xFFE4E4E7))
                    Surface(
                        color = Color(0xFFF4F4F5),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("footnote_popup_source_context")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val refHeader = buildString {
                                append("Referenced in Main Text")
                                if (sourceChapter.isNotEmpty()) {
                                    append(" • ")
                                    append(sourceChapter)
                                }
                                if (sourcePara > 0) {
                                    append(", ¶ ")
                                    append(sourcePara)
                                }
                            }
                            Text(
                                text = refHeader,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF52525B)
                            )
                            if (sourceSnippet.isNotEmpty()) {
                                Text(
                                    text = "“$sourceSnippet”",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = Color(0xFF27272A),
                                    lineHeight = 18.sp
                                )
                            }
                            if (onJumpToReference != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                OutlinedButton(
                                    onClick = {
                                        onJumpToReference()
                                        onDismiss()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("footnote_jump_to_reference_button")
                                ) {
                                    Text(
                                        text = "↩ Tilbake til referanse i hovedtekst",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("footnote_close_button")
            ) {
                Text("Close Note")
            }
        }
    )
}

@Composable
fun AddChapterMarkerDialog(
    nextChapterIndex: Int,
    currentActivePage: Int,
    onDismiss: () -> Unit,
    onAddChapter: (BookChapter) -> Unit
) {
    var chapterTitleInput by remember { mutableStateOf("Chapter $nextChapterIndex") }
    var chapterPageInput by remember { mutableIntStateOf(currentActivePage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(6.dp)),
        title = {
            DisableDialogDimming()
            Text(
                text = "Add Chapter Marker",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = chapterTitleInput,
                    onValueChange = { chapterTitleInput = it },
                    label = { Text("Chapter Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedLabelColor = Color.Black,
                        cursorColor = Color.Black
                    )
                )
                OutlinedTextField(
                    value = chapterPageInput.toString(),
                    onValueChange = { chapterPageInput = it.toIntOrNull() ?: chapterPageInput },
                    label = { Text("Starting Page") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedLabelColor = Color.Black,
                        cursorColor = Color.Black
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newChapter = BookChapter(title = chapterTitleInput.trim(), pageNumber = chapterPageInput)
                    onAddChapter(newChapter)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Add Chapter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Black)
            }
        }
    )
}
