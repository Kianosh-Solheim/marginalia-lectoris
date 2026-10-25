package com.example.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.model.parseHighlightColor
import com.example.reader.BookChapter
import com.example.reader.MarkdownExporter
import com.example.reader.NoteSuggestionEngine
import com.example.reader.PdfTextExtractionMode

@Composable
internal fun ReaderChapterDrawerSheet(
    documentTitle: String,
    chaptersList: List<BookChapter>,
    activeChapter: BookChapter?,
    currentActivePage: Int,
    activeTotalPages: Int,
    highlightsCount: Int,
    daisyBookIndex: com.example.reader.DaisyBookIndex? = null,
    activeDaisyClipIndex: Int = -1,
    onAddChapterClick: () -> Unit,
    onChapterSelected: (BookChapter) -> Unit,
    onDaisyNavItemSelected: ((com.example.reader.DaisyNavItem) -> Unit)? = null,
    onDaisySmilClipSelected: ((com.example.reader.DaisySmilClip) -> Unit)? = null,
    onViewNotesClick: () -> Unit,
    landmarksSection: (@Composable () -> Unit)? = null,
    chapterAuthor: (BookChapter) -> String? = { null }
) {
    var selectedDaisyTab by androidx.compose.runtime.remember(daisyBookIndex != null) {
        androidx.compose.runtime.mutableIntStateOf(0) // 0 = NCC Chapters, 1 = NCC Pages, 2 = SMIL Audio Clips
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(332.dp)
            .border(width = 2.dp, color = Color.Black)
            .testTag("reader_toc_drawer_sheet"),
        drawerShape = RoundedCornerShape(0.dp),
        drawerContainerColor = Color.White
    ) {
        // Table of Contents Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (daisyBookIndex != null) "NCC Table of Contents" else "Chapters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (daisyBookIndex != null) {
                        Surface(
                            color = Color(0xFF7C3AED),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = daisyBookIndex.formatVersion,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onAddChapterClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "Add Chapter Marker",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = documentTitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF71717A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subSummary = if (daisyBookIndex != null) {
                val durStr = com.example.reader.DaisyParserEngine.formatTimeMs(daisyBookIndex.totalDurationMs)
                "${daisyBookIndex.nccNavItems.size} NCC Sections • ${daisyBookIndex.pageTargets.size} Pages • $durStr"
            } else {
                "${chaptersList.size} Chapters • Page $currentActivePage of ${activeTotalPages.coerceAtLeast(1)}"
            }
            Text(
                text = subSummary,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF71717A)
            )

            if (daisyBookIndex != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedDaisyTab == 0,
                        onClick = { selectedDaisyTab = 0 },
                        label = { Text("Chapters (${daisyBookIndex.nccNavItems.size})", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.Black,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("daisy_toc_tab_chapters")
                    )
                    if (daisyBookIndex.pageTargets.isNotEmpty()) {
                        FilterChip(
                            selected = selectedDaisyTab == 1,
                            onClick = { selectedDaisyTab = 1 },
                            label = { Text("Pages (${daisyBookIndex.pageTargets.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.Black,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("daisy_toc_tab_pages")
                        )
                    }
                    FilterChip(
                        selected = selectedDaisyTab == 2,
                        onClick = { selectedDaisyTab = 2 },
                        label = { Text("SMIL (${daisyBookIndex.smilClips.size})", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF7C3AED),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("daisy_toc_tab_smil")
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFFE4E4E7))

        landmarksSection?.invoke()

        if (daisyBookIndex != null && selectedDaisyTab == 0 && daisyBookIndex.nccNavItems.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("daisy_ncc_chapters_list"),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(daisyBookIndex.nccNavItems) { idx, navItem ->
                    val isSelected = navItem.pageNumber == currentActivePage ||
                        activeChapter?.title.equals(navItem.title, ignoreCase = true)
                    val clampedLevel = (navItem.level - 1).coerceIn(0, 4)
                    val levelIndentDp = (clampedLevel * 12).dp

                    NavigationDrawerItem(
                        label = {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = levelIndentDp)
                            ) {
                                Text(
                                    text = if (clampedLevel > 0) "↳ ${navItem.title}" else navItem.title,
                                    style = if (clampedLevel == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                    fontWeight = when {
                                        isSelected -> FontWeight.Bold
                                        clampedLevel == 0 -> FontWeight.SemiBold
                                        else -> FontWeight.Normal
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "h${navItem.level.coerceIn(1, 6)} • Page ${navItem.pageNumber} • ${navItem.formatTimecode()}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (isSelected) Color(0xFFD4D4D8) else Color(0xFF71717A)
                                )
                            }
                        },
                        badge = {
                            Text(
                                text = navItem.formatTimecode(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (isSelected) Color.White else Color(0xFF7C3AED)
                            )
                        },
                        selected = isSelected,
                        onClick = {
                            if (onDaisyNavItemSelected != null) {
                                onDaisyNavItemSelected(navItem)
                            } else {
                                onChapterSelected(
                                    BookChapter(
                                        title = navItem.title,
                                        pageNumber = navItem.pageNumber,
                                        level = clampedLevel,
                                        anchorId = navItem.textAnchorId
                                    )
                                )
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color.Black,
                            selectedTextColor = Color.White,
                            selectedBadgeColor = Color.White,
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = Color.Black
                        ),
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("daisy_ncc_item_$idx")
                    )
                }
            }
        } else if (daisyBookIndex != null && selectedDaisyTab == 1 && daisyBookIndex.pageTargets.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("daisy_ncc_pages_list"),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(daisyBookIndex.pageTargets) { idx, pageTarget ->
                    val isSelected = pageTarget.pageNumber == currentActivePage
                    NavigationDrawerItem(
                        label = {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Printed Page ${pageTarget.pageLabel.ifBlank { pageTarget.title }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                )
                                Text(
                                    text = "Ebook p. ${pageTarget.pageNumber} • Audio ${pageTarget.formatTimecode()}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (isSelected) Color(0xFFD4D4D8) else Color(0xFF71717A)
                                )
                            }
                        },
                        badge = {
                            Text(
                                text = "p. ${pageTarget.pageLabel.ifBlank { pageTarget.pageNumber.toString() }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Color.White else Color(0xFF2563EB)
                            )
                        },
                        selected = isSelected,
                        onClick = {
                            if (onDaisyNavItemSelected != null) {
                                onDaisyNavItemSelected(pageTarget)
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color.Black,
                            selectedTextColor = Color.White,
                            selectedBadgeColor = Color.White,
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = Color.Black
                        ),
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("daisy_page_target_$idx")
                    )
                }
            }
        } else if (daisyBookIndex != null && selectedDaisyTab == 2 && daisyBookIndex.smilClips.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("daisy_smil_clips_list"),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(daisyBookIndex.smilClips) { idx, clip ->
                    val isSelected = idx == activeDaisyClipIndex
                    NavigationDrawerItem(
                        label = {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = clip.textSnippet.ifBlank { "Phrase ${idx + 1}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${clip.chapterTitle} • p. ${clip.pageNumber} • ${com.example.reader.DaisyParserEngine.formatTimeMs(clip.globalStartMs)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = if (isSelected) Color(0xFFFEF3C7) else Color(0xFF71717A)
                                )
                            }
                        },
                        badge = {
                            Text(
                                text = com.example.reader.DaisyParserEngine.formatTimeMs(clip.globalStartMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (isSelected) Color.White else Color(0xFF7C3AED)
                            )
                        },
                        selected = isSelected,
                        onClick = {
                            onDaisySmilClipSelected?.invoke(clip)
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color(0xFF7C3AED),
                            selectedTextColor = Color.White,
                            selectedBadgeColor = Color.White,
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = Color.Black
                        ),
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("daisy_smil_clip_item_$idx")
                    )
                }
            }
        } else {
            // Standard Chapter items list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(chaptersList) { _, chapter ->
                    val isSelected = activeChapter?.title == chapter.title && activeChapter?.pageNumber == chapter.pageNumber
                    val clampedLevel = chapter.level.coerceIn(0, 4)
                    val levelIndentDp = (clampedLevel * 14).dp

                    NavigationDrawerItem(
                        label = {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = levelIndentDp)
                            ) {
                                Text(
                                    text = if (clampedLevel > 0) "↳ ${chapter.title}" else chapter.title,
                                    style = if (clampedLevel == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                    fontWeight = when {
                                        isSelected -> FontWeight.Bold
                                        clampedLevel == 0 -> FontWeight.SemiBold
                                        else -> FontWeight.Normal
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                chapterAuthor(chapter)?.let { a ->
                                    Text(
                                        text = a.ifBlank { "Unknown author" },
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) Color(0xFFE4E4E7) else if (a.isBlank()) Color(0xFF71717A) else Color(0xFF27272A)
                                    )
                                }
                                val anchorSuffix = if (chapter.anchorId.isNotBlank()) " • #${chapter.anchorId}" else ""
                                val printedLabel = chapter.pageLabel.trim().takeIf { it.isNotBlank() && it != chapter.pageNumber.toString() }
                                Text(
                                    text = (if (printedLabel != null) "p. $printedLabel · PDF page ${chapter.pageNumber}" else "Page ${chapter.pageNumber}") + anchorSuffix,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (isSelected) Color(0xFFD4D4D8) else Color(0xFF71717A)
                                )
                            }
                        },
                        badge = {
                            Text(
                                text = "p. ${chapter.pageLabel.trim().ifBlank { chapter.pageNumber.toString() }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Color.White else Color(0xFF71717A)
                            )
                        },
                        selected = isSelected,
                        onClick = { onChapterSelected(chapter) },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color.Black,
                            selectedTextColor = Color.White,
                            selectedIconColor = Color.White,
                            selectedBadgeColor = Color.White,
                            unselectedContainerColor = Color.Transparent,
                            unselectedTextColor = Color.Black,
                            unselectedIconColor = Color.Black
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFFE4E4E7))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$highlightsCount notes taken",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF71717A)
            )
            TextButton(onClick = onViewNotesClick) {
                Text("View Notes", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderFormattingBottomSheet(
    document: DocumentEntity,
    isPdfDocument: Boolean,
    isPdfEbookMode: Boolean,
    pdfExtractionMode: PdfTextExtractionMode,
    activeEpubHasPageListMetadata: Boolean,
    showEpubPageEquivalentsSetting: Boolean,
    paginateByEpubPageEquivalentsSetting: Boolean,
    readerViewModeSetting: String,
    pageTurnAnimationSetting: String,
    volumeKeyAnimationSetting: Boolean,
    readerThemeSetting: String,
    fontSizeSp: Float,
    marginDp: Int,
    textAlignSetting: String,
    lineSpacingMultiplier: Float,
    fontFamilySetting: String,
    onDismiss: () -> Unit,
    onSelectOriginalPdfMode: () -> Unit,
    onSelectPdfEbookMode: () -> Unit,
    onOpenPdfCropFromSheet: () -> Unit,
    onUpdatePdfCrop: (Float, Float, Float, Float) -> Unit,
    onSelectPdfExtractionMode: (PdfTextExtractionMode) -> Unit,
    onOpenPdfTextToolsTab: (Int) -> Unit,
    onShowEpubPageEquivalentsChange: (Boolean) -> Unit,
    onPaginateByEpubPageEquivalentsChange: (Boolean) -> Unit,
    onReaderViewModeChange: (String) -> Unit,
    onPageTurnAnimationChange: (String) -> Unit,
    onVolumeKeyAnimationChange: (Boolean) -> Unit,
    onReaderThemeChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onMarginDpChange: (Int) -> Unit,
    onTextAlignChange: (String) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    publisherStylesEnabled: Boolean = true,
    onPublisherStylesEnabledChange: (Boolean) -> Unit = {},
    isFixedLayoutBook: Boolean = false,
    useReadiumEngine: Boolean = true,
    onUseReadiumEngineChange: (Boolean) -> Unit = {},
    columnCount: Int = 1,
    onColumnCountChange: (Int) -> Unit = {},
    topMarginDp: Int = 16,
    onTopMarginDpChange: (Int) -> Unit = {},
    bottomMarginDp: Int = 16,
    onBottomMarginDpChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val isVerticalScroll = !isFixedLayoutBook && readerViewModeSetting == "VERTICAL_SCROLL"
    val effectiveColumnCount = if (isVerticalScroll) 1 else columnCount.coerceIn(1, 2)
    val isPublisherOverrideActive = !isPdfDocument && publisherStylesEnabled
    val isScalingSupported = !isFixedLayoutBook

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        scrimColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
    ) {
        com.example.ui.theme.DisableDialogDimming()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Page Formatting & Typography",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = if (isPdfDocument) {
                            if (isPdfEbookMode) "PDF Reflow Mode • ${if (effectiveColumnCount == 2) "2 Columns" else "1 Column"}" else "Original PDF Layout"
                        } else {
                            val layoutModeLabel = when {
                                isVerticalScroll -> "Vertical Scroll (1 Column)"
                                effectiveColumnCount == 2 -> "Paginated • 2 Columns"
                                else -> "Paginated • 1 Column"
                            }
                            val styleModeLabel = if (publisherStylesEnabled) "Publisher Styling" else "Custom Typography"
                            "$layoutModeLabel • $styleModeLabel"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF52525B)
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = Color(0xFFE4E4E7))

            // PDF Reading Area & Margin Zoom + PDF-to-Ebook Reflow Mode Toggle
            if (isPdfDocument) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isPdfEbookMode) Color(0xFFEDE9FE) else Color(0xFFF4F4F5),
                    border = BorderStroke(1.5.dp, if (isPdfEbookMode) Color(0xFF7C3AED) else Color(0xFFD4D4D8)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PDF Reading Mode (Synchronised)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Surface(
                                color = if (isPdfEbookMode) Color(0xFF7C3AED) else Color(0xFF27272A),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isPdfEbookMode) "Ebook Reflow" else "Original PDF",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Switch between the original PDF layout and a clean, reflowable ebook view (removes repeated headers/footers while keeping highlights and printed page numbers synchronised).",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF52525B)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !isPdfEbookMode,
                                onClick = onSelectOriginalPdfMode,
                                label = { Text("Original PDF View", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.Black,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sheet_pdf_original_mode_chip")
                            )
                            FilterChip(
                                selected = isPdfEbookMode,
                                onClick = onSelectPdfEbookMode,
                                label = { Text("PDF-to-Ebook Reflow", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF7C3AED),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sheet_pdf_ebook_mode_chip")
                            )
                        }
                    }
                }
            }

            // PDF Reading Area & Margin Zoom (Global Per-Book Non-Destructive View Crop)
            if (document.fileType.equals("PDF", ignoreCase = true) && !isPdfEbookMode) {
                val isCropped = document.pdfCropLeft > 0.005f ||
                    document.pdfCropTop > 0.005f ||
                    document.pdfCropRight < 0.995f ||
                    document.pdfCropBottom < 0.995f
                val zoomPercent = ((1f / (document.pdfCropRight - document.pdfCropLeft).coerceIn(0.15f, 1f)) * 100).toInt()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF5F3FF),
                    border = BorderStroke(1.5.dp, Color(0xFF7C3AED)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Crop,
                                    contentDescription = null,
                                    tint = Color(0xFF7C3AED),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "PDF Reading Region & Margin Zoom",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF18181B)
                                )
                            }
                            Surface(
                                color = Color(0xFF7C3AED),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isCropped) "${zoomPercent}% Zoom" else "Full Page (100%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Select which part of the PDF page you want to read to trim outer margins and make the text larger across the entire book (non-destructive view crop).",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF52525B)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenPdfCropFromSheet,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF7C3AED),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("open_pdf_crop_from_sheet_btn")
                            ) {
                                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Select Reading Area / Trim Margins", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            if (isCropped) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdatePdfCrop(0f, 0f, 1f, 1f)
                                        Toast.makeText(context, "Reset PDF view to Full Page", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color(0xFF7C3AED)),
                                    modifier = Modifier.testTag("reset_pdf_crop_from_sheet_btn")
                                ) {
                                    Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED))
                                }
                            }
                        }
                    }
                }
            }

            // PDF Text Tools, Extraction Mode, Diagnostics & OCR Controls (Available in both Original PDF View & Ebook Reflow)
            if (isPdfDocument) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFEFCE8),
                    border = BorderStroke(1.5.dp, Color(0xFFD97706)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PDF Text Tools & OCR Diagnostics",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Surface(
                                color = Color(0xFFD97706),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = pdfExtractionMode.shortBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Fix repeated letters ('TThhee'), garbled symbols, missing ToUnicode font maps, scanned image pages, or scrambled column order with Auto/Original/OCR/Hybrid modes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF52525B)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PdfTextExtractionMode.entries.forEach { modeOpt ->
                                FilterChip(
                                    selected = pdfExtractionMode == modeOpt,
                                    onClick = { onSelectPdfExtractionMode(modeOpt) },
                                    label = { Text(modeOpt.shortBadge, fontSize = 10.sp) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sheet_extraction_mode_${modeOpt.id.lowercase()}")
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onOpenPdfTextToolsTab(1) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF18181B),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sheet_test_text_extraction_btn")
                            ) {
                                Text("Test Text Extraction", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onOpenPdfTextToolsTab(2) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sheet_compare_text_results_btn")
                            ) {
                                Text("Compare & Fix / OCR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // SECTION 1: PAGE LAYOUT & READING MODE (Columns, Pagination & Page Turns)
            // =========================================================================
            if (!isPdfDocument || isPdfEbookMode) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFAFAFA),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1. Page Layout & Columns",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Surface(
                                color = Color(0xFF18181B),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = when {
                                        isVerticalScroll -> "1 Column (Scroll)"
                                        effectiveColumnCount == 2 -> "2 Columns"
                                        else -> "1 Column"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Single-Column vs Two-Column Reading Mode Toggle
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Column Layout",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                            Text(
                                text = if (isVerticalScroll) {
                                    "Continuous Vertical Scroll displays text in a single column. Selecting Two Columns will switch to Paginated view."
                                } else if (effectiveColumnCount == 2) {
                                    "Displaying text side-by-side in two columns. This setting persists across books."
                                } else {
                                    "Displaying text in one continuous column. This setting persists across books."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF52525B)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = effectiveColumnCount == 1,
                                    onClick = { onColumnCountChange(1) },
                                    label = { Text("Single Column", fontWeight = FontWeight.Medium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("column_mode_single_chip")
                                )
                                FilterChip(
                                    selected = effectiveColumnCount == 2,
                                    onClick = {
                                        if (isVerticalScroll) {
                                            onReaderViewModeChange("PAGINATED")
                                        }
                                        onColumnCountChange(2)
                                    },
                                    label = { Text("Two Columns", fontWeight = FontWeight.Medium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("column_mode_two_chip")
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFFE4E4E7))

                        // Reading View Mode Selector (Paginated vs Continuous Vertical Scroll)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Reading View Mode",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                if (isFixedLayoutBook) {
                                    Surface(
                                        color = Color(0xFFE4E4E7),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "Pre-Paginated Book",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF3F3F46),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (isFixedLayoutBook) {
                                    "Fixed-layout EPUBs use pre-paginated pages and cannot be switched to vertical scroll."
                                } else {
                                    "Switch between paginated pages (supports 1 or 2 columns) or continuous vertical scrolling (single column)."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF52525B)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !isVerticalScroll,
                                    onClick = { onReaderViewModeChange("PAGINATED") },
                                    label = { Text("Paginated Pages") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("view_mode_paginated_chip")
                                )
                                FilterChip(
                                    selected = isVerticalScroll,
                                    onClick = {
                                        if (columnCount == 2) {
                                            onColumnCountChange(1)
                                        }
                                        onReaderViewModeChange("VERTICAL_SCROLL")
                                    },
                                    enabled = !isFixedLayoutBook,
                                    label = { Text("Vertical Scroll") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("view_mode_vertical_chip")
                                )
                            }
                        }

                        // Page Turn Animation Selector (only active in Paginated mode)
                        if (!isVerticalScroll) {
                            HorizontalDivider(color = Color(0xFFE4E4E7))

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Page Turn Animation",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        "PAGE_CURL" to "📖 3D Page Curl",
                                        "SLIDE" to "↔️ Slide",
                                        "NONE" to "⚡ Instant"
                                    ).forEach { (animId, label) ->
                                        FilterChip(
                                            selected = pageTurnAnimationSetting.equals(animId, ignoreCase = true),
                                            onClick = { onPageTurnAnimationChange(animId) },
                                            label = { Text(label, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color.Black,
                                                selectedLabelColor = Color.White
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("page_turn_anim_${animId.lowercase()}_chip")
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Animate Volume Button Page Turns",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.Black
                                        )
                                        Text(
                                            text = "Play page turn animation when using hardware Volume Up / Down keys",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF71717A)
                                        )
                                    }
                                    Switch(
                                        checked = volumeKeyAnimationSetting,
                                        onCheckedChange = onVolumeKeyAnimationChange,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color.Black
                                        ),
                                        modifier = Modifier.testTag("volume_key_anim_switch")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // SECTION 2: PAGE APPEARANCE & SCALING (Works alongside Publisher Styling)
            // =========================================================================
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFFAFAFA),
                border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "2. Page Appearance & Scaling",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        if (!isPdfDocument && publisherStylesEnabled) {
                            Surface(
                                color = Color(0xFFDCFCE7),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.5.dp, Color(0xFF16A34A))
                            ) {
                                Text(
                                    text = "Works with Publisher Styling",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF15803D),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Background Color Theme Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Page Background & Text Color",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReaderTheme.entries.forEach { themeOption ->
                                val isSelected = readerThemeSetting.equals(themeOption.id, ignoreCase = true)
                                Surface(
                                    onClick = { onReaderThemeChange(themeOption.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(if (isSelected) 2.5.dp else 1.dp, if (isSelected) Color(0xFF2563EB) else Color(0xFFD4D4D8)),
                                    color = themeOption.backgroundColor,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "Aa",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = themeOption.textColor
                                        )
                                        Text(
                                            text = themeOption.label,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = themeOption.textColor,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isPdfDocument || isPdfEbookMode) {
                        HorizontalDivider(color = Color(0xFFE4E4E7))

                        // Font Size Control (adapted to scale proportionally even when Publisher Styling is enabled)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Font Size",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isScalingSupported) Color.Black else Color(0xFF71717A)
                                )
                                val sizeDesc = when {
                                    !isScalingSupported -> "Fixed-Layout Page"
                                    fontSizeSp >= 60f -> "Giant"
                                    fontSizeSp >= 44f -> "Extra Huge"
                                    fontSizeSp >= 32f -> "Very Large"
                                    fontSizeSp >= 24f -> "Large"
                                    fontSizeSp >= 18f -> "Normal"
                                    else -> "Small"
                                }
                                val scalePct = ((fontSizeSp / 18f) * 100).toInt()
                                Text(
                                    text = if (isScalingSupported) "${fontSizeSp.toInt()} sp ($scalePct% • $sizeDesc)" else sizeDesc,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isScalingSupported) Color.Black else Color(0xFF71717A)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (fontSizeSp > 12f) onFontSizeChange(fontSizeSp - 2f) },
                                    enabled = isScalingSupported
                                ) {
                                    Text(
                                        text = "A-",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isScalingSupported) Color.Black else Color(0xFFA1A1AA)
                                    )
                                }
                                Slider(
                                    value = fontSizeSp,
                                    onValueChange = onFontSizeChange,
                                    enabled = isScalingSupported,
                                    valueRange = 12f..76f,
                                    steps = 31,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Black,
                                        activeTrackColor = Color.Black,
                                        inactiveTrackColor = Color(0xFFE4E4E7)
                                    )
                                )
                                IconButton(
                                    onClick = { if (fontSizeSp < 76f) onFontSizeChange(fontSizeSp + 2f) },
                                    enabled = isScalingSupported
                                ) {
                                    Text(
                                        text = "A+",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isScalingSupported) Color.Black else Color(0xFFA1A1AA)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFFE4E4E7))

                        // Page Margins (Side Margins + Paginated Top & Bottom Margins)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Left & Right Margins",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isScalingSupported) Color.Black else Color(0xFF71717A)
                                )
                                val marginDesc = when {
                                    !isScalingSupported -> "Fixed-Layout Page"
                                    marginDp <= 10 -> "Ultra Slim"
                                    marginDp <= 20 -> "Narrow"
                                    marginDp <= 36 -> "Standard"
                                    marginDp <= 64 -> "Wide"
                                    marginDp <= 112 -> "Extra Wide"
                                    else -> "Book Column"
                                }
                                Text(
                                    text = if (isScalingSupported) "${marginDp} dp ($marginDesc)" else marginDesc,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isScalingSupported) Color.Black else Color(0xFF71717A)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onMarginDpChange((marginDp - 8).coerceAtLeast(4)) },
                                    enabled = isScalingSupported
                                ) {
                                    Text(
                                        text = "-",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isScalingSupported) Color.Black else Color(0xFFA1A1AA)
                                    )
                                }
                                Slider(
                                    value = marginDp.toFloat(),
                                    onValueChange = { onMarginDpChange(it.toInt()) },
                                    enabled = isScalingSupported,
                                    valueRange = 4f..200f,
                                    steps = 48,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Black,
                                        activeTrackColor = Color.Black,
                                        inactiveTrackColor = Color(0xFFE4E4E7)
                                    )
                                )
                                IconButton(
                                    onClick = { onMarginDpChange((marginDp + 8).coerceAtMost(200)) },
                                    enabled = isScalingSupported
                                ) {
                                    Text(
                                        text = "+",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isScalingSupported) Color.Black else Color(0xFFA1A1AA)
                                    )
                                }
                            }
                        }

                        // Top & Bottom Margins (enabled when Paginated Pages mode is active)
                        val isPaginatedMarginsEnabled = isScalingSupported && !isVerticalScroll
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Top & Bottom Page Margins",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFF71717A)
                                )
                                Surface(
                                    color = if (isPaginatedMarginsEnabled) Color(0xFFEDE9FE) else Color(0xFFF4F4F5),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = when {
                                            !isScalingSupported -> "Fixed-Layout Page"
                                            isVerticalScroll -> "Paginated Mode Only"
                                            else -> "Top ${topMarginDp} dp • Bottom ${bottomMarginDp} dp"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPaginatedMarginsEnabled) Color(0xFF6D28D9) else Color(0xFF71717A),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (isVerticalScroll && isScalingSupported) {
                                Text(
                                    text = "Top and bottom page margins apply when Paginated Pages is turned on in Reading View Mode.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF71717A)
                                )
                            } else {
                                // Top Margin Slider
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Top Margin",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isPaginatedMarginsEnabled) Color(0xFF3F3F46) else Color(0xFF71717A)
                                        )
                                        Text(
                                            text = "${topMarginDp} dp",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFF71717A)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { onTopMarginDpChange((topMarginDp - 8).coerceAtLeast(0)) },
                                            enabled = isPaginatedMarginsEnabled,
                                            modifier = Modifier.testTag("sheet_top_margin_decrease")
                                        ) {
                                            Text(
                                                text = "-",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFFA1A1AA)
                                            )
                                        }
                                        Slider(
                                            value = topMarginDp.toFloat(),
                                            onValueChange = { onTopMarginDpChange(it.toInt()) },
                                            enabled = isPaginatedMarginsEnabled,
                                            valueRange = 0f..160f,
                                            steps = 39,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("sheet_top_margin_slider"),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.Black,
                                                activeTrackColor = Color.Black,
                                                inactiveTrackColor = Color(0xFFE4E4E7)
                                            )
                                        )
                                        IconButton(
                                            onClick = { onTopMarginDpChange((topMarginDp + 8).coerceAtMost(160)) },
                                            enabled = isPaginatedMarginsEnabled,
                                            modifier = Modifier.testTag("sheet_top_margin_increase")
                                        ) {
                                            Text(
                                                text = "+",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFFA1A1AA)
                                            )
                                        }
                                    }
                                }

                                // Bottom Margin Slider
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Bottom Margin",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isPaginatedMarginsEnabled) Color(0xFF3F3F46) else Color(0xFF71717A)
                                        )
                                        Text(
                                            text = "${bottomMarginDp} dp",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFF71717A)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { onBottomMarginDpChange((bottomMarginDp - 8).coerceAtLeast(0)) },
                                            enabled = isPaginatedMarginsEnabled,
                                            modifier = Modifier.testTag("sheet_bottom_margin_decrease")
                                        ) {
                                            Text(
                                                text = "-",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFFA1A1AA)
                                            )
                                        }
                                        Slider(
                                            value = bottomMarginDp.toFloat(),
                                            onValueChange = { onBottomMarginDpChange(it.toInt()) },
                                            enabled = isPaginatedMarginsEnabled,
                                            valueRange = 0f..160f,
                                            steps = 39,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("sheet_bottom_margin_slider"),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.Black,
                                                activeTrackColor = Color.Black,
                                                inactiveTrackColor = Color(0xFFE4E4E7)
                                            )
                                        )
                                        IconButton(
                                            onClick = { onBottomMarginDpChange((bottomMarginDp + 8).coerceAtMost(160)) },
                                            enabled = isPaginatedMarginsEnabled,
                                            modifier = Modifier.testTag("sheet_bottom_margin_increase")
                                        ) {
                                            Text(
                                                text = "+",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPaginatedMarginsEnabled) Color.Black else Color(0xFFA1A1AA)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // SECTION 3: PUBLISHER STYLING & CUSTOM TYPOGRAPHY OVERRIDES
            // =========================================================================
            if (!isPdfDocument || isPdfEbookMode) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isPublisherOverrideActive) Color(0xFFF0FDF4) else Color(0xFFFAFAFA),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isPublisherOverrideActive) Color(0xFF16A34A) else Color(0xFFE4E4E7)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Publisher Styling Toggle Header
                        if (!isPdfDocument) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "3. Publisher Styling",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                        Surface(
                                            color = if (publisherStylesEnabled) Color(0xFF16A34A) else Color(0xFF27272A),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (publisherStylesEnabled) "Original CSS Active" else "Custom Typography",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isFixedLayoutBook) {
                                            "Fixed-layout EPUB 3 books always use the publisher's exact page positioning and typography."
                                        } else if (publisherStylesEnabled) {
                                            "Preserving the publisher's original font family, text alignment, and line spacing. Turn off Publisher Styling to customize these options below."
                                        } else {
                                            "Publisher styling is disabled. Custom font family, text alignment, and line spacing overrides are active below."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF52525B)
                                    )
                                }
                                Switch(
                                    checked = publisherStylesEnabled || isFixedLayoutBook,
                                    onCheckedChange = { enabled ->
                                        if (!isFixedLayoutBook) {
                                            onPublisherStylesEnabledChange(enabled)
                                        }
                                    },
                                    enabled = !isFixedLayoutBook,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF16A34A)
                                    ),
                                    modifier = Modifier.testTag("sheet_publisher_styles_switch")
                                )
                            }

                            HorizontalDivider(
                                color = if (publisherStylesEnabled) Color(0xFFBBF7D0) else Color(0xFFE4E4E7)
                            )
                        } else {
                            Text(
                                text = "3. Typography Overrides",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        // Notice banner when Publisher Styling is overriding custom typography controls
                        if (isPublisherOverrideActive) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color(0xFF86EFAC)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Managed by Publisher Styling",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF166534)
                                        )
                                        Text(
                                            text = "Font family, text alignment, and line spacing follow the book's stylesheet.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF3F3F46)
                                        )
                                    }
                                    if (!isFixedLayoutBook) {
                                        TextButton(
                                            onClick = { onPublisherStylesEnabledChange(false) },
                                            modifier = Modifier.testTag("unlock_custom_typography_btn")
                                        ) {
                                            Text(
                                                text = "Customize",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF15803D)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val typographyControlsEnabled = !isPublisherOverrideActive && !isFixedLayoutBook
                        val labelTint = if (typographyControlsEnabled) Color.Black else Color(0xFF71717A)

                        // Font Family
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Font Family",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = labelTint
                                )
                                Text(
                                    text = if (typographyControlsEnabled) {
                                        when (fontFamilySetting) {
                                            "SANS" -> "Sans-Serif"
                                            "MONO" -> "Monospace"
                                            else -> "Serif"
                                        }
                                    } else {
                                        "Publisher Default"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = labelTint
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "SERIF" to "Serif",
                                    "SANS" to "Sans-Serif",
                                    "MONO" to "Mono"
                                ).forEach { (family, label) ->
                                    FilterChip(
                                        selected = typographyControlsEnabled && fontFamilySetting == family,
                                        onClick = { onFontFamilyChange(family) },
                                        enabled = typographyControlsEnabled,
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color.Black,
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Text Alignment (Justified vs Left vs Center)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Text Alignment",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = labelTint
                                )
                                Text(
                                    text = if (typographyControlsEnabled) {
                                        when (textAlignSetting) {
                                            "LEFT" -> "Left Aligned"
                                            "CENTER" -> "Centered"
                                            else -> "Justified"
                                        }
                                    } else {
                                        "Publisher Default"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = labelTint
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = typographyControlsEnabled && textAlignSetting == "JUSTIFY",
                                    onClick = { onTextAlignChange("JUSTIFY") },
                                    enabled = typographyControlsEnabled,
                                    label = { Text("Justified") },
                                    leadingIcon = { Icon(Icons.Default.FormatAlignJustify, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White,
                                        selectedLeadingIconColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = typographyControlsEnabled && textAlignSetting == "LEFT",
                                    onClick = { onTextAlignChange("LEFT") },
                                    enabled = typographyControlsEnabled,
                                    label = { Text("Left") },
                                    leadingIcon = { Icon(Icons.Default.FormatAlignLeft, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White,
                                        selectedLeadingIconColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = typographyControlsEnabled && textAlignSetting == "CENTER",
                                    onClick = { onTextAlignChange("CENTER") },
                                    enabled = typographyControlsEnabled,
                                    label = { Text("Center") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Line Spacing
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Line Spacing",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = labelTint
                                )
                                Text(
                                    text = if (typographyControlsEnabled) "${lineSpacingMultiplier}x" else "Publisher Default",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = labelTint
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    1.25f to "Tight (1.25x)",
                                    1.55f to "Normal (1.55x)",
                                    1.9f to "Relaxed (1.9x)",
                                    2.3f to "Spacious (2.3x)"
                                ).forEach { (mult, label) ->
                                    FilterChip(
                                        selected = typographyControlsEnabled && kotlin.math.abs(lineSpacingMultiplier - mult) < 0.1f,
                                        onClick = { onLineSpacingChange(mult) },
                                        enabled = typographyControlsEnabled,
                                        label = { Text(label, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color.Black,
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // SECTION 4: PRINT EDITION PAGE NUMBERS (EPUB Page Equivalent)
            // =========================================================================
            if (!isPdfDocument) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFAFAFA),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "4. Printed Page Numbers",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    if (activeEpubHasPageListMetadata) {
                                        Surface(
                                            color = Color(0xFF7C3AED),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "Page List Detected",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "Show printed edition page numbers in the book header, status bar, and inline text when the EPUB has Page List metadata",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF71717A)
                                )
                            }
                            Switch(
                                checked = showEpubPageEquivalentsSetting,
                                onCheckedChange = onShowEpubPageEquivalentsChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color.Black
                                ),
                                modifier = Modifier.testTag("sheet_show_epub_page_equivalents_switch")
                            )
                        }

                        if (showEpubPageEquivalentsSetting) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "1:1 Print Edition Pagination",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "Each screen shows exactly one printed page; text on long pages is shrunk (to at most 70%) so the page fits. Paginated mode only, single column. In Vertical Scroll, a ruled line with the page number marks each printed page instead",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF71717A)
                                    )
                                }
                                Switch(
                                    checked = paginateByEpubPageEquivalentsSetting,
                                    onCheckedChange = onPaginateByEpubPageEquivalentsChange,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF7C3AED)
                                    ),
                                    modifier = Modifier.testTag("sheet_paginate_by_epub_page_equivalents_switch")
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderHighlightsBottomSheet(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onOpenExportPreview: () -> Unit,
    onSelectHighlight: (HighlightEntity) -> Unit,
    onEditHighlight: (HighlightEntity) -> Unit,
    onDeleteHighlight: (HighlightEntity) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        scrimColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
    ) {
        com.example.ui.theme.DisableDialogDimming()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Notes & Highlights",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "${highlights.size} notes for ${document.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF71717A)
                    )
                }

                Button(
                    onClick = onOpenExportPreview,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("open_export_preview_button")
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export Vault")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (highlights.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No notes recorded yet.\nTap any paragraph or quote while reading to add a note.",
                        color = Color(0xFF71717A),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(highlights) { _, h ->
                        val badgeColor = Color(parseHighlightColor(h.colorHex))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(8.dp))
                                .clickable { onSelectHighlight(h) }
                                .padding(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = h.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { onEditHighlight(h) },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("sheet_edit_highlight_${h.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Edit Highlight",
                                                tint = Color(0xFF2563EB),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteHighlight(h) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = Color(0xFF71717A),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "\"${h.highlightedText}\"",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Serif),
                                    color = Color.Black,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(badgeColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )

                                if (h.userNotes.isNotBlank()) {
                                    Text(
                                        text = "Note: ${h.userNotes}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF3F3F46)
                                    )
                                }

                                val noteTags = NoteSuggestionEngine.parseTagsList(h.tags)
                                if (noteTags.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        noteTags.forEach { tag ->
                                            Surface(
                                                color = Color(0xFFF4F4F5),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(0.5.dp, Color(0xFFD4D4D8))
                                            ) {
                                                Text(
                                                    text = "#$tag",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF3F3F46),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val isPdfDoc = document.fileType.equals("PDF", ignoreCase = true)
                                    val pageText = MarkdownExporter.formatHighlightPageDisplay(h, isPdfDoc)
                                    val locationSummary = if (isPdfDoc && h.chapterTitle.isNotBlank() && !pageText.contains(h.chapterTitle, ignoreCase = true)) {
                                        "$pageText • ${h.chapterTitle}"
                                    } else {
                                        pageText
                                    }
                                    Text(
                                        text = locationSummary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF71717A),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "File: ${h.title}.md",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Medium
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
