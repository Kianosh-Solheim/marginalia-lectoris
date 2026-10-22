package com.example.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.DocumentEntity
import com.example.ui.theme.DisableDialogDimming
import com.example.ui.theme.KaleidoAmberFill
import com.example.ui.theme.KaleidoCobaltFill
import com.example.ui.theme.KaleidoPlumFill
import com.example.ui.theme.KaleidoPlumInk
import com.example.ui.theme.KaleidoSageFill
import com.example.widget.ContinueFilter
import com.example.widget.ContinueFormatter
import com.example.widget.ContinueReadingSelector
import com.example.widget.ContinueReadingWidgetProvider
import com.example.widget.ContinueWidgetSizeTier
import com.example.widget.PinnedBooksWidgetProvider
import com.example.widget.ReadingPosition
import com.example.widget.ResumeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interactive Home Screen & Reading Tablet Widgets Manager Dialog:
 * - Tab 0: "Continue Reading Widget" — interactive 5-tier preview (`COMPACT`, `MEDIUM`, `EXPANDED`,
 *   `TABLET_WIDE`, `TABLET_LARGE` dual Book + Article), filter selector (`ALL`, `BOOKS`, `ARTICLES`),
 *   1-tap resume at exact citable locus (`epubcfi`, `¶`, `loc.`), and "Pin Continue Reading Widget".
 * - Tab 1: "Pinned Books Shelf Widget" — lets the user choose how many books can be pinned (1 to 12),
 *   toggle which books are pinned, resize the live grid preview, and pin the shelf widget.
 */
@Composable
fun WidgetPinnedBooksManagerDialog(
    documents: List<DocumentEntity>,
    maxPinnedBooks: Int,
    configuringAppWidgetId: Int? = null,
    onToggleBookPin: (DocumentEntity) -> Unit,
    onUpdateMaxPinnedBooks: (Int) -> Unit,
    onOpenBookFromWidget: (DocumentEntity) -> Unit,
    onOpenDocumentWithResume: ((DocumentEntity, ResumeTarget?) -> Unit)? = null,
    onDismiss: () -> Unit,
    onFinishWidgetConfiguration: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    var activeWidgetModeTab by remember { mutableIntStateOf(0) } // 0 = Continue Reading, 1 = Pinned Books Shelf

    var continueFilter by remember(configuringAppWidgetId) {
        val initial = if (configuringAppWidgetId != null && configuringAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            ContinueReadingWidgetProvider.getFilterForWidget(context, configuringAppWidgetId)
        } else {
            ContinueReadingWidgetProvider.getFilterForWidget(context, 0)
        }
        mutableStateOf(initial)
    }

    var continuePreviewTier by remember { mutableStateOf(ContinueWidgetSizeTier.MEDIUM) }

    var sliderMaxBooks by remember(maxPinnedBooks) {
        mutableIntStateOf(maxPinnedBooks.coerceIn(1, 12))
    }

    var previewWidthDp by remember { mutableFloatStateOf(320f) }
    var previewHeightDp by remember { mutableFloatStateOf(210f) }

    val explicitlyPinned = remember(documents) {
        documents.filter { it.isPinnedToWidget }.sortedBy { it.widgetPinOrder }
    }
    val effectiveWidgetBooks = remember(documents, explicitlyPinned, sliderMaxBooks) {
        if (explicitlyPinned.isNotEmpty()) {
            explicitlyPinned.take(sliderMaxBooks)
        } else {
            documents.sortedByDescending { it.lastReadTimestamp }.take(sliderMaxBooks)
        }
    }

    val previewColumns = when {
        previewWidthDp < 175f -> 1
        previewWidthDp < 265f -> 2
        previewWidthDp < 355f -> 3
        else -> 4
    }

    fun triggerResume(doc: DocumentEntity) {
        if (onOpenDocumentWithResume != null) {
            val hasSavedPos = doc.lastPositionCfi.isNotBlank() ||
                doc.lastPositionParagraph > 0 ||
                doc.lastPositionLoc > 0
            val resume = if (hasSavedPos) {
                ResumeTarget(
                    position = ReadingPosition(
                        cfi = doc.lastPositionCfi,
                        paragraph = doc.lastPositionParagraph,
                        offset = doc.lastPositionOffset,
                        loc = doc.lastPositionLoc,
                        label = doc.lastPositionLabel,
                        snippet = doc.lastPositionSnippet,
                        chapter = doc.lastPositionChapter
                    ),
                    page = doc.currentPage.coerceAtLeast(1)
                )
            } else {
                null
            }
            onOpenDocumentWithResume(doc, resume)
        } else {
            onOpenBookFromWidget(doc)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DisableDialogDimming()
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .border(2.dp, Color.Black, RoundedCornerShape(0.dp))
                .testTag("widget_pinned_books_dialog"),
            shape = RoundedCornerShape(0.dp),
            color = Color.White,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = KaleidoPlumFill,
                            border = BorderStroke(1.5.dp, Color.Black),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Widgets,
                                    contentDescription = null,
                                    tint = KaleidoPlumInk,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Home Screen & Tablet Reading Widgets",
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Configure Continue Reading (5 adaptive tiers) & Pinned Books Shelf widgets",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF52525B)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
                    }
                }

                // Widget Mode Selector Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "Continue Reading (Locus & CFI)",
                        1 to "Pinned Books Shelf (${explicitlyPinned.size})"
                    ).forEach { (tabIdx, label) ->
                        val isSelected = activeWidgetModeTab == tabIdx
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = if (isSelected) Color.Black else Color.White,
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activeWidgetModeTab = tabIdx }
                                .testTag("widget_manager_tab_$tabIdx")
                        ) {
                            Text(
                                text = label,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Color.Black,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                if (activeWidgetModeTab == 0) {
                    // TAB 0: CONTINUE READING WIDGET
                    val continueSelection = remember(documents, continueFilter, continuePreviewTier) {
                        when (continuePreviewTier) {
                            ContinueWidgetSizeTier.COMPACT -> ContinueReadingSelector.select(documents, continueFilter, 0)
                            ContinueWidgetSizeTier.MEDIUM -> ContinueReadingSelector.select(documents, continueFilter, 2)
                            ContinueWidgetSizeTier.EXPANDED -> ContinueReadingSelector.select(documents, continueFilter, 3)
                            ContinueWidgetSizeTier.TABLET_WIDE -> ContinueReadingSelector.select(documents, continueFilter, 3)
                            ContinueWidgetSizeTier.TABLET_LARGE -> ContinueReadingSelector.selectDual(documents, continueFilter, 3)
                        }
                    }

                    // Filter & Size Tier Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Document Filter:",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ContinueFilter.values().forEach { f ->
                                val isSel = continueFilter == f
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (isSel) KaleidoCobaltFill else Color.White,
                                    border = BorderStroke(if (isSel) 1.5.dp else 1.dp, Color.Black),
                                    modifier = Modifier
                                        .clickable {
                                            continueFilter = f
                                            val targetId = configuringAppWidgetId ?: 0
                                            ContinueReadingWidgetProvider.setFilterForWidget(context, targetId, f)
                                            ContinueReadingWidgetProvider.refreshAll(context)
                                        }
                                        .testTag("continue_widget_filter_${f.name.lowercase()}")
                                ) {
                                    Text(
                                        text = f.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 5 Size Tier Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ContinueWidgetSizeTier.values().forEach { tier ->
                            val isSel = continuePreviewTier == tier
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = if (isSel) Color.Black else Color.White,
                                border = BorderStroke(1.dp, Color.Black),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { continuePreviewTier = tier }
                                    .testTag("continue_widget_tier_${tier.name.lowercase()}")
                            ) {
                                Text(
                                    text = tier.label.substringBefore(" "),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) Color.White else Color.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // Live E-Paper Continue Reading Widget Preview
                    ContinueReadingLivePreviewBox(
                        selection = continueSelection,
                        filter = continueFilter,
                        tier = continuePreviewTier,
                        onCycleFilter = {
                            val next = when (continueFilter) {
                                ContinueFilter.ALL -> ContinueFilter.BOOKS
                                ContinueFilter.BOOKS -> ContinueFilter.ARTICLES
                                ContinueFilter.ARTICLES -> ContinueFilter.ALL
                            }
                            continueFilter = next
                        },
                        onResumeDocument = { doc -> triggerResume(doc) }
                    )

                    Text(
                        text = "In-Progress Documents (sorted by most recently read • tap any item to resume at exact citable locus):",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    val startedDocs = remember(documents) {
                        documents.filter { ContinueReadingSelector.isStarted(it) }
                            .sortedByDescending { it.lastReadTimestamp }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val listToShow = if (startedDocs.isNotEmpty()) startedDocs else documents
                        items(listToShow, key = { it.id }) { doc ->
                            val locus = ContinueFormatter.locus(doc)
                            val pct = ContinueFormatter.percent(doc)
                            val timeLeft = ContinueFormatter.timeLeft(doc)
                            val snippet = ContinueFormatter.snippet(doc)
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = Color.White,
                                border = BorderStroke(1.5.dp, Color.Black),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { triggerResume(doc) }
                                    .testTag("continue_widget_doc_row_${doc.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = doc.title,
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$locus · $pct%" + (if (timeLeft.isNotEmpty()) " · $timeLeft" else ""),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF27272A),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (snippet.isNotEmpty()) {
                                            Text(
                                                text = snippet,
                                                fontFamily = FontFamily.Serif,
                                                fontStyle = FontStyle.Italic,
                                                fontSize = 10.5.sp,
                                                color = Color(0xFF52525B),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { triggerResume(doc) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(0.dp),
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .testTag("continue_resume_btn_${doc.id}")
                                    ) {
                                        Text(
                                            text = "Resume →",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Action Bar for Continue Reading Widget
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                requestPinContinueReadingWidgetToLauncher(context)
                            },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier.testTag("pin_continue_widget_to_launcher_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddToHomeScreen,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Pin Continue Widget",
                                fontFamily = FontFamily.Monospace,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (configuringAppWidgetId != null &&
                                    configuringAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                                ) {
                                    ContinueReadingWidgetProvider.setFilterForWidget(
                                        context,
                                        configuringAppWidgetId,
                                        continueFilter
                                    )
                                }
                                ContinueReadingWidgetProvider.refreshAll(context)
                                PinnedBooksWidgetProvider.refreshAllWidgets(context)
                                if (configuringAppWidgetId != null &&
                                    configuringAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                                    onFinishWidgetConfiguration != null
                                ) {
                                    onFinishWidgetConfiguration(configuringAppWidgetId)
                                } else {
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.testTag("save_widget_pins_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save & Update Widgets", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                } else {
                    // TAB 1: PINNED BOOKS SHELF WIDGET
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Interactive Resizable Shelf Preview (${previewColumns} cols • ${previewWidthDp.toInt()}×${previewHeightDp.toInt()}dp)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF18181B)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                "1×2" to Pair(155f, 185f),
                                "2×2" to Pair(235f, 195f),
                                "3×2" to Pair(320f, 210f),
                                "4×3" to Pair(410f, 250f)
                            ).forEach { (label, dims) ->
                                val isSelected = kotlin.math.abs(previewWidthDp - dims.first) < 25f
                                Surface(
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (isSelected) Color.Black else Color(0xFFF4F4F5),
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier
                                        .clickable {
                                            previewWidthDp = dims.first
                                            previewHeightDp = dims.second
                                        }
                                        .testTag("widget_size_preset_$label")
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else Color.Black,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeightDp.coerceIn(165f, 255f).dp)
                            .background(Color(0xFFF4F4F5))
                            .border(1.dp, Color.Black)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(previewWidthDp.dp)
                                .fillMaxHeight()
                                .border(1.5.dp, Color.Black)
                                .testTag("live_resizable_widget_preview"),
                            shape = RoundedCornerShape(0.dp),
                            color = Color.White,
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp)
                                ) {
                                    if (effectiveWidgetBooks.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Pin books below to show their covers here",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF71717A)
                                            )
                                        }
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(previewColumns),
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(effectiveWidgetBooks, key = { it.id }) { book ->
                                                WidgetPreviewBookTile(
                                                    document = book,
                                                    onClick = {
                                                        triggerResume(book)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Surface(
                                    color = Color(0xFF18181B),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                previewWidthDp = (previewWidthDp + dragAmount.x / density).coerceIn(145f, 440f)
                                                previewHeightDp = (previewHeightDp + dragAmount.y / density).coerceIn(165f, 255f)
                                            }
                                        }
                                        .testTag("widget_resize_drag_handle")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInFull,
                                            contentDescription = "Drag to Resize Widget",
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = Color(0xFFFAFAFA),
                        border = BorderStroke(1.dp, Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Maximum Books Pinned in Shelf Widget:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Surface(
                                    color = Color.Black,
                                    shape = RoundedCornerShape(0.dp)
                                ) {
                                    Text(
                                        text = "$sliderMaxBooks Books",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Slider(
                                value = sliderMaxBooks.toFloat(),
                                onValueChange = {
                                    val newVal = it.toInt().coerceIn(1, 12)
                                    sliderMaxBooks = newVal
                                    onUpdateMaxPinnedBooks(newVal)
                                },
                                valueRange = 1f..12f,
                                steps = 10,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Black,
                                    activeTrackColor = Color.Black,
                                    inactiveTrackColor = Color(0xFFD4D4D8)
                                ),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("widget_max_books_slider")
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            val isPinned = doc.isPinnedToWidget
                            val pct = (doc.readingProgress * 100).toInt().coerceIn(0, 100)
                            val locus = ContinueFormatter.locus(doc)
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = if (isPinned) Color(0xFFF4F4F5) else Color.White,
                                border = BorderStroke(
                                    width = if (isPinned) 1.5.dp else 1.dp,
                                    color = Color.Black
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleBookPin(doc) }
                                    .testTag("widget_pin_book_row_${doc.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Checkbox(
                                        checked = isPinned,
                                        onCheckedChange = { onToggleBookPin(doc) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color.Black,
                                            checkmarkColor = Color.White
                                        ),
                                        modifier = Modifier.size(20.dp)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = doc.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${doc.author} • $locus ($pct%)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF71717A),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { triggerResume(doc) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(0.dp),
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .testTag("widget_resume_book_btn_${doc.id}")
                                    ) {
                                        Text(
                                            text = "Resume p.${doc.currentPage}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                requestPinWidgetToLauncher(context)
                            },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier.testTag("pin_widget_to_launcher_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddToHomeScreen,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pin Shelf Widget", fontFamily = FontFamily.Monospace, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                onUpdateMaxPinnedBooks(sliderMaxBooks)
                                PinnedBooksWidgetProvider.refreshAllWidgets(context)
                                ContinueReadingWidgetProvider.refreshAll(context)
                                if (configuringAppWidgetId != null &&
                                    configuringAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                                    onFinishWidgetConfiguration != null
                                ) {
                                    onFinishWidgetConfiguration(configuringAppWidgetId)
                                } else {
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.testTag("save_widget_pins_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save & Update Widgets", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingLivePreviewBox(
    selection: com.example.widget.Selection,
    filter: ContinueFilter,
    tier: ContinueWidgetSizeTier,
    onCycleFilter: () -> Unit,
    onResumeDocument: (DocumentEntity) -> Unit
) {
    val isWideTier = tier == ContinueWidgetSizeTier.TABLET_WIDE || tier == ContinueWidgetSizeTier.TABLET_LARGE
    Surface(
        shape = RoundedCornerShape(0.dp),
        color = Color.White,
        border = BorderStroke(2.dp, Color.Black),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("continue_reading_live_preview_box")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONTINUE READING",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color.Black),
                    modifier = Modifier.clickable { onCycleFilter() }
                ) {
                    Text(
                        text = if (tier == ContinueWidgetSizeTier.COMPACT) filter.name else "FILTER: ${filter.name}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

            val primary = selection.primary
            if (primary == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No started documents matching ${filter.name} yet — open any book or article to populate this widget.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = Color(0xFF52525B)
                    )
                }
            } else {
                val secondary = selection.secondary ?: selection.recent.firstOrNull()
                if (tier == ContinueWidgetSizeTier.COMPACT) {
                    ContinuePreviewFeaturedCard(
                        doc = primary,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onResumeDocument(primary) }
                    )
                } else if (isWideTier) {
                    // Wide screen: two featured items side-by-side with equal height
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ContinuePreviewFeaturedCard(
                            doc = primary,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onClick = { onResumeDocument(primary) }
                        )
                        if (secondary != null) {
                            ContinuePreviewFeaturedCard(
                                doc = secondary,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = { onResumeDocument(secondary) }
                            )
                        }
                    }
                } else {
                    // Narrower screen: reflow featured items vertically
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ContinuePreviewFeaturedCard(
                            doc = primary,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onResumeDocument(primary) }
                        )
                        if (secondary != null && tier == ContinueWidgetSizeTier.EXPANDED) {
                            ContinuePreviewFeaturedCard(
                                doc = secondary,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onResumeDocument(secondary) }
                            )
                        }
                    }
                }

                // Two Horizontally Scrollable Shelves Underneath (left-aligned)
                if (tier != ContinueWidgetSizeTier.COMPACT) {
                    if (selection.recentBooks.isNotEmpty()) {
                        ContinuePreviewHorizontalShelf(
                            title = "RECENT BOOKS",
                            items = selection.recentBooks,
                            onItemClick = onResumeDocument
                        )
                    }
                    if (selection.recentArticles.isNotEmpty()) {
                        ContinuePreviewHorizontalShelf(
                            title = "RECENT ARTICLES",
                            items = selection.recentArticles,
                            onItemClick = onResumeDocument
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinuePreviewFeaturedCard(
    doc: DocumentEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isArticle = ContinueReadingSelector.isArticle(doc)
    val pct = ContinueFormatter.percent(doc)
    val timeLeft = ContinueFormatter.timeLeft(doc)
    val snippet = ContinueFormatter.snippet(doc)
    val authorText = doc.author.trim().ifBlank { if (isArticle) "Saved Article" else "Unknown Author" }

    var coverBitmap by remember(doc.id, doc.filePath, doc.coverUri) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(doc.id, doc.filePath, doc.coverUri) {
        withContext(Dispatchers.IO) {
            coverBitmap = ContinueReadingWidgetProvider.loadOrCreateSharedCoverBitmap(
                context,
                doc,
                com.example.widget.ContinueLayout.TABLET_LARGE
            )
        }
    }

    val coverAspect = remember(coverBitmap, doc.coverAspectRatio) {
        val bmp = coverBitmap
        when {
            bmp != null && bmp.width > 0 && bmp.height > 0 ->
                (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.45f, 1.45f)
            doc.coverAspectRatio > 0.2f -> doc.coverAspectRatio.coerceIn(0.45f, 1.45f)
            else -> 0.71f
        }
    }

    Surface(
        shape = RoundedCornerShape(0.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color.Black),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: Cover positioned towards the top + Title, Author, and Reading Excerpt beside it
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(74.dp)
                        .aspectRatio(coverAspect)
                        .border(1.dp, Color.Black)
                        .background(Color.White)
                        .padding(1.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap!!.asImageBitmap(),
                            contentDescription = doc.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = doc.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        color = Color.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = authorText,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 10.sp,
                        color = Color(0xFF333333),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (snippet.isNotBlank()) {
                        Text(
                            text = snippet,
                            fontFamily = FontFamily.Serif,
                            fontSize = 9.5.sp,
                            lineHeight = 11.5.sp,
                            color = Color(0xFF27272A),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Bottom section: Progress bar anchored at the bottom of the card with % and time remaining alongside it
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val progressFraction = doc.readingProgress.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.5.dp)
                            .align(Alignment.TopStart)
                            .background(Color.Black)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .height(5.dp)
                            .align(Alignment.TopStart)
                            .background(Color.Black)
                    )
                    if (progressFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction.coerceAtLeast(0.05f))
                                .height(5.dp)
                                .align(Alignment.TopStart)
                                .background(Color.Black)
                        )
                    }
                }

                Text(
                    text = "$pct%",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                if (timeLeft.isNotBlank()) {
                    Text(
                        text = timeLeft,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF222222),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinuePreviewHorizontalShelf(
    title: String,
    items: List<DocumentEntity>,
    onItemClick: (DocumentEntity) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(items, key = { it.id }) { doc ->
                var coverBmp by remember(doc.id, doc.filePath, doc.coverUri) {
                    mutableStateOf<Bitmap?>(null)
                }
                LaunchedEffect(doc.id, doc.filePath, doc.coverUri) {
                    withContext(Dispatchers.IO) {
                        coverBmp = ContinueReadingWidgetProvider.loadOrCreateSharedCoverBitmap(
                            context,
                            doc,
                            com.example.widget.ContinueLayout.TABLET_LARGE
                        )
                    }
                }
                val itemAspect = remember(coverBmp, doc.coverAspectRatio) {
                    val bmp = coverBmp
                    when {
                        bmp != null && bmp.width > 0 && bmp.height > 0 ->
                            (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.45f, 1.45f)
                        doc.coverAspectRatio > 0.2f -> doc.coverAspectRatio.coerceIn(0.45f, 1.45f)
                        else -> 0.71f
                    }
                }
                Column(
                    modifier = Modifier
                        .height(78.dp)
                        .width(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        .border(1.dp, Color.Black)
                        .background(Color.White)
                        .clickable { onItemClick(doc) }
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(itemAspect),
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverBmp != null) {
                            Image(
                                bitmap = coverBmp!!.asImageBitmap(),
                                contentDescription = doc.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val progressFraction = doc.readingProgress.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0xFFE4E4E7))
                    ) {
                        if (progressFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction.coerceAtLeast(0.06f))
                                    .height(3.dp)
                                    .background(Color.Black)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPreviewBookTile(
    document: DocumentEntity,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var coverBitmap by remember(document.id, document.filePath, document.coverUri) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(document.id, document.filePath, document.coverUri) {
        withContext(Dispatchers.IO) {
            coverBitmap = PinnedBooksWidgetProvider.loadOrGenerateWidgetCoverBitmap(context, document)
        }
    }

    val coverAspectRatio = remember(coverBitmap) {
        val bmp = coverBitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.45f, 1.6f)
        } else {
            0.72f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(2.dp)
            .testTag("widget_preview_book_tile_${document.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspectRatio)
                .border(1.dp, Color.Black)
                .background(Color.White)
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap!!.asImageBitmap(),
                    contentDescription = document.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
            ) {
                Text(
                    text = "p.${document.currentPage}",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = document.title,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LinearProgressIndicator(
            progress = { document.readingProgress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = Color.Black,
            trackColor = Color(0xFFE4E4E7)
        )
    }
}

private fun requestPinContinueReadingWidgetToLauncher(context: Context) {
    try {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, ContinueReadingWidgetProvider::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
            val pinned = appWidgetManager.requestPinAppWidget(provider, null, null)
            if (pinned) {
                Toast.makeText(context, "Pinning 'Continue Reading' widget to your home screen!", Toast.LENGTH_SHORT).show()
                return
            }
        }
        ContinueReadingWidgetProvider.refreshAll(context)
        Toast.makeText(
            context,
            "Continue Reading widget updated! Long-press your home screen → Widgets → Marginalia Lectoris to place & resize it.",
            Toast.LENGTH_LONG
        ).show()
    } catch (_: Throwable) {
        Toast.makeText(
            context,
            "Continue Reading widget synced!",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun requestPinWidgetToLauncher(context: Context) {
    try {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, PinnedBooksWidgetProvider::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
            val pinned = appWidgetManager.requestPinAppWidget(provider, null, null)
            if (pinned) {
                Toast.makeText(context, "Pinning 'Pinned Books Shelf' widget to your home screen!", Toast.LENGTH_SHORT).show()
                return
            }
        }
        PinnedBooksWidgetProvider.refreshAllWidgets(context)
        Toast.makeText(
            context,
            "Widget updated! You can also long-press your Android home screen → Widgets → Marginalia Lectoris to place & resize it.",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Throwable) {
        Toast.makeText(
            context,
            "Widget synced! Long-press your Android home screen → Widgets → Marginalia Lectoris to place & resize it.",
            Toast.LENGTH_LONG
        ).show()
    }
}
