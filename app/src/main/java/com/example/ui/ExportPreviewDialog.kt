package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.reader.MarkdownExporter
import com.example.ui.theme.DisableDialogDimming

@Composable
fun ExportPreviewDialog(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onExportVaultZip: () -> Unit,
    onExportMoc: () -> Unit,
    onExportSingleHighlight: (HighlightEntity) -> Unit,
    currentPage: Int? = null
) {
    // Edited volumes: start on the chapter being read; "Whole book" switches back.
    val scope = rememberChapterScope(document, highlights, startAtPage = currentPage)
    ExportPreviewDialogBody(scope.document, scope.highlights, settings, onDismiss, onExportVaultZip, onExportMoc, onExportSingleHighlight,
        header = { ChapterScopeBar(scope) })
}

@Composable
private fun ExportPreviewDialogBody(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onExportVaultZip: () -> Unit,
    onExportMoc: () -> Unit,
    onExportSingleHighlight: (HighlightEntity) -> Unit,
    header: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedHighlightIndex by remember { mutableIntStateOf(0) }

    val mocContent = remember(document, highlights, settings) {
        MarkdownExporter.generateMocMarkdown(document, highlights, settings)
    }

    val mocTitle = remember(document, settings) {
        MarkdownExporter.getMocTitle(document.title, settings)
    }

    val currentHighlight = highlights.getOrNull(selectedHighlightIndex)
    val highlightContent = remember(currentHighlight, document, settings) {
        if (currentHighlight != null) {
            MarkdownExporter.generateHighlightMarkdown(currentHighlight, document, settings)
        } else {
            ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .border(2.dp, Color.Black, RoundedCornerShape(6.dp)),
        title = {
            DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Markdown Export",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "${highlights.size} highlight notes • 1 MOC file",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF71717A)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                header()
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color.Black
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Map of Content", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal, color = Color.Black) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Notes (${highlights.size})", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal, color = Color.Black) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    // MOC Preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${MarkdownExporter.sanitizeFilename(mocTitle)}.md",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        TextButton(
                            onClick = {
                                copyToClipboard(context, mocContent, "MOC Markdown copied")
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Black, modifier = Modifier.height(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp, color = Color.Black)
                        }
                    }

                    if (highlights.isNotEmpty()) {
                        Text(
                            text = "Click a note below to preview it:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            highlights.forEachIndexed { index, h ->
                                OutlinedButton(
                                    onClick = {
                                        selectedHighlightIndex = index
                                        selectedTab = 1
                                    },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "[[${h.title.take(20)}${if (h.title.length > 20) "…" else ""}]]",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = Color.Black,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(4.dp))
                            .background(Color(0xFFFAFAFA))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = mocContent,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            ),
                            color = Color.Black
                        )
                    }
                } else {
                    // Individual Highlight Note Preview
                    if (highlights.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No highlights created yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF71717A)
                            )
                        }
                    } else {
                        // Selector for individual highlights
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            highlights.forEachIndexed { index, h ->
                                OutlinedButton(
                                    onClick = { selectedHighlightIndex = index },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selectedHighlightIndex == index) Color.Black else Color.White
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = h.title.take(18) + if (h.title.length > 18) "…" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedHighlightIndex == index) Color.White else Color.Black,
                                        fontWeight = if (selectedHighlightIndex == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        if (currentHighlight != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${MarkdownExporter.sanitizeFilename(currentHighlight.title)}.md",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Row {
                                    TextButton(
                                        onClick = {
                                            copyToClipboard(context, highlightContent, "Note Markdown copied")
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Black, modifier = Modifier.height(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Copy", fontSize = 12.sp, color = Color.Black)
                                    }
                                    TextButton(
                                        onClick = { onExportSingleHighlight(currentHighlight) }
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Black, modifier = Modifier.height(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Share", fontSize = 12.sp, color = Color.Black)
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFAFAFA))
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = highlightContent,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("export_moc_button")
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Color.Black, modifier = Modifier.height(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export MOC", color = Color.Black)
                }

                Button(
                    onClick = onExportVaultZip,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("export_vault_zip_button")
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export Vault (.ZIP)")
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

private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Markdown Note", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}
