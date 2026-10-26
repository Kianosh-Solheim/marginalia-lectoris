package com.example.ui.koreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.data.local.SettingsEntity
import com.example.reader.TtsUiState
import com.example.reader.TtsVoiceDescriptor
import com.example.ui.ReaderTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KOReader 5-Tab Top Menu Bar:
 * [Back/File] - [TOC/Navigation] - [Typesetting] - [Obsidian & Highlights Hub] - [Settings]
 */
/** One tool in the reader's top bar: a square cell with an icon and a small label; inverted when on. */
@Composable
private fun ReaderToolCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    theme: ReaderTheme,
    onClick: () -> Unit,
    active: Boolean = false,
    badge: String? = null,
    tag: String = ""
) {
    val ink = theme.textColor
    val paper = theme.barColor
    Column(
        modifier = Modifier
            .width(54.dp)
            .height(52.dp)
            .background(if (active) ink else paper)
            .clickable(onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box {
            Icon(icon, contentDescription = label, tint = if (active) paper else ink, modifier = Modifier.size(22.dp))
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 14.dp)
                        .background(if (active) paper else ink)
                        .padding(horizontal = 3.dp)
                ) {
                    Text(badge, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (active) ink else paper)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label.uppercase(Locale.ROOT),
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            color = if (active) paper else ink,
            maxLines = 1
        )
    }
}

@Composable
fun KOReaderTopBar(
    document: DocumentEntity,
    currentPage: Int,
    totalPages: Int,
    chapterTitle: String,
    highlightsCount: Int,
    activeTheme: ReaderTheme,
    visible: Boolean,
    isTtsPlaying: Boolean = false,
    isTtsBarVisible: Boolean = false,
    isPdfEbookMode: Boolean = false,
    pageInfoSubtitle: String = "",
    onTogglePdfEbookMode: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
    onOpenFileMenu: () -> Unit,
    onOpenTocMenu: () -> Unit,
    onOpenTypesettingMenu: () -> Unit,
    onOpenPdfCropMenu: (() -> Unit)? = null,
    onOpenPdfTextToolsMenu: (() -> Unit)? = null,
    onToggleTtsBar: (() -> Unit)? = null,
    isRecording: Boolean = false,
    onToggleRecording: (() -> Unit)? = null,
    onOpenAnnotationsMenu: () -> Unit,
    onOpenSettingsMenu: () -> Unit,
    glossaeEnabled: Boolean = false,
    onToggleGlossae: (() -> Unit)? = null,
    volumeChapterLine: String = ""
) {
    val ink = activeTheme.textColor
    val isPdf = document.fileType.equals("PDF", ignoreCase = true)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(activeTheme.barColor)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(46.dp)
                        .testTag("koreader_back_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library", tint = ink, modifier = Modifier.size(24.dp))
                }

                // Title, place in the book, and (edited volumes) the chapter being read
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenFileMenu() }
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = document.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val percent = if (totalPages > 0) ((currentPage.toFloat() / totalPages) * 100).toInt() else 0
                    val place = (if (pageInfoSubtitle.isNotBlank()) pageInfoSubtitle else "p. $currentPage / $totalPages") + " · $percent%" +
                        if (isPdfEbookMode && isPdf) " · reflowed" else ""
                    Text(
                        text = place,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = activeTheme.secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("top_bar_page_info_subtitle")
                    )
                    val third = volumeChapterLine.ifBlank { chapterTitle }
                    if (third.isNotBlank()) {
                        Text(
                            text = third,
                            fontFamily = FontFamily.Serif,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontSize = 12.sp,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("top_bar_volume_chapter")
                        )
                    }
                }

                // Tools: square cells with labels; a tool that is switched on is shown inverted.
                Row(
                    modifier = Modifier.border(1.dp, ink),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cells = mutableListOf<@Composable () -> Unit>()
                    cells += { ReaderToolCell(Icons.Default.Folder, "Book", activeTheme, onOpenFileMenu, tag = "koreader_tab_file") }
                    cells += { ReaderToolCell(Icons.AutoMirrored.Filled.List, "Contents", activeTheme, onOpenTocMenu, tag = "koreader_tab_toc") }
                    if (onTogglePdfEbookMode != null && isPdf) {
                        cells += { ReaderToolCell(Icons.Default.AutoStories, "Reflow", activeTheme, onTogglePdfEbookMode, active = isPdfEbookMode, tag = "koreader_tab_pdf_ebook_mode") }
                    }
                    if (onOpenPdfCropMenu != null && isPdf && !isPdfEbookMode) {
                        val isCropped = document.pdfCropLeft > 0.005f || document.pdfCropTop > 0.005f ||
                            document.pdfCropRight < 0.995f || document.pdfCropBottom < 0.995f
                        cells += { ReaderToolCell(Icons.Default.Crop, "Crop", activeTheme, onOpenPdfCropMenu, active = isCropped, tag = "koreader_tab_pdf_crop") }
                    }
                    if (onOpenPdfTextToolsMenu != null && isPdf) {
                        val custom = !document.pdfTextExtractionMode.equals("AUTO", ignoreCase = true) && document.pdfTextExtractionMode.isNotBlank() ||
                            document.pdfPageExtractionOverridesJson.isNotBlank()
                        cells += { ReaderToolCell(Icons.Default.Search, "Text", activeTheme, onOpenPdfTextToolsMenu, active = custom, tag = "koreader_tab_pdf_text_tools") }
                    }
                    cells += { ReaderToolCell(Icons.Default.FormatSize, "Type", activeTheme, onOpenTypesettingMenu, tag = "koreader_tab_typesetting") }
                    if (onToggleTtsBar != null) {
                        cells += { ReaderToolCell(Icons.Default.RecordVoiceOver, "Listen", activeTheme, onToggleTtsBar, active = isTtsPlaying || isTtsBarVisible, tag = "koreader_tab_tts") }
                    }
                    if (onToggleRecording != null) {
                        cells += { ReaderToolCell(Icons.Default.Mic, if (isRecording) "Stop rec." else "Record", activeTheme, onToggleRecording, active = isRecording, tag = "koreader_tab_record") }
                    }
                    if (onToggleGlossae != null) {
                        cells += { ReaderToolCell(Icons.Default.Layers, "Glossae", activeTheme, onToggleGlossae, active = glossaeEnabled, tag = "koreader_tab_glossae") }
                    }
                    cells += {
                        ReaderToolCell(Icons.Default.DriveFileRenameOutline, "Notes", activeTheme, onOpenAnnotationsMenu,
                            badge = if (highlightsCount > 0) (if (highlightsCount > 99) "99+" else highlightsCount.toString()) else null,
                            tag = "koreader_tab_annotations")
                    }
                    cells += { ReaderToolCell(Icons.Default.Settings, "Tools", activeTheme, onOpenSettingsMenu, tag = "koreader_tab_settings") }
                    cells.forEachIndexed { i, cell ->
                        if (i > 0) Box(Modifier.width(1.dp).height(52.dp).background(activeTheme.dividerColor))
                        cell()
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(ink))
        }
    }
}

/**
 * Reader footer. With the menu open: progress, chapter, page, time left, battery, clock and the
 * menu button. Full screen: one slim, quiet line (page and clock) with the menu button in the corner.
 * Tapping the text opens the page scrubber; the corner button shows or hides the menu.
 */
@Composable
fun KOReaderBottomStatusBar(
    currentPage: Int,
    totalPages: Int,
    chapterTitle: String,
    activeTheme: ReaderTheme,
    visible: Boolean,
    pageInfoLabel: String = "",
    onScrubberClick: () -> Unit,
    onToggleFullscreen: () -> Unit = {}
) {
    val context = LocalContext.current
    var currentTimeStr by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableIntStateOf(100) }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            currentTimeStr = timeFormat.format(Date())
            delay(30000L)
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) batteryPercent = (level * 100) / scale
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    val ink = activeTheme.textColor
    val progressFraction = when {
        totalPages <= 1 -> 0f
        currentPage >= totalPages -> 1f
        else -> ((currentPage - 1).toFloat() / (totalPages - 1).toFloat()).coerceIn(0f, 0.99f)
    }
    val percent = (progressFraction * 100).toInt()

    @Composable
    fun MenuButton(open: Boolean, size: androidx.compose.ui.unit.Dp) {
        Box(
            modifier = Modifier
                .size(size)
                .border(1.dp, if (open) ink else activeTheme.secondaryTextColor)
                .background(if (open) ink else activeTheme.backgroundColor)
                .clickable { onToggleFullscreen() }
                .testTag("bottom_bar_fullscreen_toggle_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (open) Icons.Default.Fullscreen else Icons.Default.FullscreenExit,
                contentDescription = if (open) "Hide menu (full screen)" else "Show menu",
                tint = if (open) activeTheme.barColor else ink,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }

    if (visible) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(activeTheme.barColor)
                .navigationBarsPadding()
        ) {
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(ink))
            // Square progress rule
            Box(Modifier.fillMaxWidth().height(3.dp).background(activeTheme.dividerColor)) {
                Box(Modifier.fillMaxWidth(progressFraction.coerceAtLeast(0.002f)).height(3.dp).background(ink))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable { onScrubberClick() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapterTitle.ifBlank { "Reading" },
                        fontFamily = FontFamily.Serif,
                        fontSize = 12.sp,
                        color = activeTheme.secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = (if (pageInfoLabel.isNotBlank()) pageInfoLabel else "p. $currentPage / $totalPages") + " · $percent%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        modifier = Modifier.testTag("bottom_status_bar_page_info")
                    )
                }
                Spacer(Modifier.width(12.dp))
                val remainingPages = (totalPages - currentPage).coerceAtLeast(0)
                val isAtLastPage = totalPages > 1 && currentPage >= totalPages
                val estMinutesLeft = when {
                    isAtLastPage -> 0
                    totalPages <= 1 -> 1
                    else -> kotlin.math.ceil(remainingPages * 1.3f).toInt().coerceAtLeast(1)
                }
                val timeRemainingText = when {
                    isAtLastPage -> "finished"
                    estMinutesLeft >= 60 -> "${estMinutesLeft / 60} h ${estMinutesLeft % 60} min left"
                    else -> "$estMinutesLeft min left"
                }
                Text(
                    listOf(timeRemainingText, "$batteryPercent%", currentTimeStr).filter { it.isNotBlank() }.joinToString("  ·  "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = activeTheme.secondaryTextColor,
                    maxLines = 1
                )
                Spacer(Modifier.width(10.dp))
                MenuButton(open = true, size = 34.dp)
            }
        }
    } else {
        // Full screen: one slim line, hairline progress, button in the corner.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(activeTheme.backgroundColor)
                .navigationBarsPadding()
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(activeTheme.dividerColor.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(progressFraction.coerceAtLeast(0.002f)).height(1.dp).background(activeTheme.secondaryTextColor))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (if (pageInfoLabel.isNotBlank()) pageInfoLabel else "$currentPage / $totalPages"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = activeTheme.secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { onScrubberClick() }
                )
                if (currentTimeStr.isNotBlank()) {
                    Text(currentTimeStr, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = activeTheme.secondaryTextColor)
                    Spacer(Modifier.width(8.dp))
                }
                MenuButton(open = false, size = 26.dp)
            }
        }
    }
}

/**
 * KOReader Quick Page Scrubber Modal:
 * Quick page scrubbing slider, Chapter jumps, and Jump-to-Page input.
 */
@Composable
fun KOReaderQuickScrubberDialog(
    currentPage: Int,
    totalPages: Int,
    chapterTitle: String,
    onDismiss: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    epubBook: com.example.reader.EpubBook? = null,
    epubPages: List<com.example.reader.EpubPage> = emptyList(),
    hasPageList: Boolean = epubBook?.hasPageList == true || epubPages.any { it.pageEquivalentLabel.isNotBlank() },
    totalStableLocations: Int = epubBook?.totalStableLocations ?: epubPages.maxOfOrNull { it.endStableLocation } ?: 1
) {
    var scrubPage by remember { mutableIntStateOf(currentPage) }
    var pageInputText by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Scrubber, 1 = Jump to

    // Jump to mode: 0 = Print page, 1 = Location, 2 = Paragraph, 3 = CFI
    var selectedJumpMode by remember(hasPageList) {
        mutableIntStateOf(if (hasPageList) 0 else 1)
    }
    var jumpInputText by remember { mutableStateOf("") }
    var jumpErrorMessage by remember { mutableStateOf<String?>(null) }

    val currentPageObj = remember(epubPages, currentPage) {
        epubPages.getOrNull((currentPage - 1).coerceIn(0, (epubPages.size - 1).coerceAtLeast(0)))
    }
    val currentChapterIndex = currentPageObj?.chapterIndex ?: 0
    val currentChapterPages = remember(epubPages, currentChapterIndex, chapterTitle) {
        val byIdx = epubPages.filter { !it.isCoverPage && it.chapterIndex == currentChapterIndex }
        byIdx.ifEmpty {
            epubPages.filter { !it.isCoverPage && it.chapterTitle.equals(chapterTitle, ignoreCase = true) }
        }
    }
    val maxParagraphInCurrentChapter = remember(currentChapterPages, epubBook, currentChapterIndex) {
        val fromBook = epubBook?.chapters?.getOrNull(currentChapterIndex)?.countedTextParagraphs?.size ?: 0
        val fromPages = currentChapterPages.maxOfOrNull { it.endParagraphNumber } ?: 1
        maxOf(fromBook, fromPages, 1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier
            .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
            .testTag("page_scrubber_modal"),
        title = {
            com.example.ui.theme.DisableDialogDimming()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Page Scrubber",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "Page $scrubPage / $totalPages",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                // Flat ruled tab bar: Scrubber | Jump to
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
                ) {
                    val tabs = listOf("Scrubber", "Jump to")
                    tabs.forEachIndexed { idx, label ->
                        val isSelected = activeTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (isSelected) Color.Black else Color.White)
                                .clickable {
                                    activeTab = idx
                                    jumpErrorMessage = null
                                }
                                .padding(vertical = 8.dp)
                                .testTag(if (idx == 0) "scrubber_tab_slider" else "scrubber_tab_jump_to"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Color.Black
                            )
                        }
                    }
                }
            }
        },
        text = {
            if (activeTab == 0) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF52525B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Slider
                    Slider(
                        value = scrubPage.toFloat(),
                        onValueChange = { scrubPage = it.toInt().coerceIn(1, totalPages) },
                        valueRange = 1f..totalPages.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Black,
                            activeTrackColor = Color.Black,
                            inactiveTrackColor = Color(0xFFE4E4E7)
                        )
                    )

                    // Quick -10 / -1 / +1 / +10 controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        OutlinedButton(
                            onClick = { scrubPage = (scrubPage - 10).coerceAtLeast(1) },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("-10", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        OutlinedButton(
                            onClick = { scrubPage = (scrubPage - 1).coerceAtLeast(1) },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("-1", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        OutlinedButton(
                            onClick = { scrubPage = (scrubPage + 1).coerceAtMost(totalPages) },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("+1", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        OutlinedButton(
                            onClick = { scrubPage = (scrubPage + 10).coerceAtMost(totalPages) },
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("+10", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }

                    // Direct numeric jump
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pageInputText,
                            onValueChange = { pageInputText = it.filter { ch -> ch.isDigit() } },
                            placeholder = { Text("Go to page...", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black,
                                cursorColor = Color.Black
                            )
                        )
                        Button(
                            onClick = {
                                val parsed = pageInputText.toIntOrNull()
                                if (parsed != null && parsed in 1..totalPages) {
                                    onJumpToPage(parsed)
                                    onDismiss()
                                }
                            },
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                        ) {
                            Text("Jump", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // "Jump to" tab with 4 modes: Print page, Location, Paragraph, CFI
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scrubber_jump_to_tab_content"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val modeSpecs = listOf(
                        Triple(0, "Print page", hasPageList),
                        Triple(1, "Location", true),
                        Triple(2, "Paragraph", true),
                        Triple(3, "CFI", true)
                    )

                    // Flat monospace mode chips in 2x2 grid with sharp ruled lines
                    for (rowModes in modeSpecs.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((modeIdx, modeLabel, isEnabled) in rowModes) {
                                val isSelected = selectedJumpMode == modeIdx
                                val bgColor = when {
                                    !isEnabled -> Color(0xFFF4F4F5)
                                    isSelected -> Color.Black
                                    else -> Color.White
                                }
                                val borderColor = if (isEnabled) Color.Black else Color(0xFFD4D4D8)
                                val textColor = when {
                                    !isEnabled -> Color(0xFFA1A1AA)
                                    isSelected -> Color.White
                                    else -> Color.Black
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, borderColor, RoundedCornerShape(0.dp))
                                        .background(bgColor)
                                        .clickable(enabled = isEnabled) {
                                            selectedJumpMode = modeIdx
                                            jumpErrorMessage = null
                                        }
                                        .padding(vertical = 8.dp, horizontal = 6.dp)
                                        .testTag("jump_mode_chip_$modeIdx"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = modeLabel,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = Color.Black)

                    val modeHint = when (selectedJumpMode) {
                        0 -> "Enter print page label (e.g. 42 or xiv)"
                        1 -> "Enter stable location (1–${totalStableLocations.coerceAtLeast(1)})"
                        2 -> "Paragraph in current chapter (¶ 1–$maxParagraphInCurrentChapter)"
                        else -> "Paste epubcfi(...) string"
                    }
                    val inputPlaceholder = when (selectedJumpMode) {
                        0 -> "42 or xiv"
                        1 -> "1..${totalStableLocations.coerceAtLeast(1)}"
                        2 -> "¶ 12"
                        else -> "epubcfi(/6/2!/4/2/1:0)"
                    }

                    Text(
                        text = modeHint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF3F3F46)
                    )

                    OutlinedTextField(
                        value = jumpInputText,
                        onValueChange = {
                            jumpInputText = it
                            jumpErrorMessage = null
                        },
                        placeholder = {
                            Text(
                                text = inputPlaceholder,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF71717A)
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.Black
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = if (jumpErrorMessage != null) Color(0xFFB91C1C) else Color.Black,
                            unfocusedBorderColor = if (jumpErrorMessage != null) Color(0xFFB91C1C) else Color.Black,
                            cursorColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scrubber_jump_input_field")
                    )

                    if (jumpErrorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFFB91C1C), RoundedCornerShape(0.dp))
                                .background(Color(0xFFFEF2F2))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("scrubber_jump_error_text")
                        ) {
                            Text(
                                text = jumpErrorMessage!!,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFB91C1C)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val raw = jumpInputText.trim()
                            if (raw.isEmpty()) {
                                jumpErrorMessage = "Input cannot be empty."
                                return@Button
                            }
                            when (selectedJumpMode) {
                                0 -> {
                                    // Print page: match label exactly (e.g. "42" or "xiv"), case-insensitive
                                    if (!hasPageList) {
                                        jumpErrorMessage = "No page-list available in this book."
                                        return@Button
                                    }
                                    val cleanTarget = raw.removePrefix("pp.").removePrefix("p.").trim()
                                    val matchedPage = epubPages.firstOrNull { p ->
                                        if (p.isCoverPage) return@firstOrNull false
                                        val pageLbl = p.pageEquivalentLabel.trim()
                                        val startLbl = pageLbl.substringBefore("–").substringBefore("-").trim()
                                        val endLbl = pageLbl.substringAfterLast("–").substringAfterLast("-").trim()
                                        pageLbl.equals(cleanTarget, ignoreCase = true) ||
                                            startLbl.equals(cleanTarget, ignoreCase = true) ||
                                            endLbl.equals(cleanTarget, ignoreCase = true) ||
                                            p.semanticBlocks.any { sb ->
                                                val bLbl = sb.pageEquivalent.trim()
                                                bLbl.equals(cleanTarget, ignoreCase = true) ||
                                                    bLbl.substringBefore("–").trim().equals(cleanTarget, ignoreCase = true)
                                            }
                                    } ?: run {
                                        val entry = epubBook?.pageListEntries?.firstOrNull {
                                            it.label.trim().equals(cleanTarget, ignoreCase = true)
                                        }
                                        if (entry != null) {
                                            epubPages.firstOrNull { p ->
                                                !p.isCoverPage && (
                                                    (entry.anchorId.isNotEmpty() && p.anchorIds.any { a -> a.equals(entry.anchorId, ignoreCase = true) }) ||
                                                        p.chapterIndex == entry.spineIndex ||
                                                        (entry.spineFileKey.isNotEmpty() && p.fileKey.equals(entry.spineFileKey, ignoreCase = true))
                                                    )
                                            }
                                        } else null
                                    }

                                    if (matchedPage != null) {
                                        onJumpToPage((matchedPage.globalIndex + 1).coerceIn(1, totalPages))
                                        onDismiss()
                                    } else {
                                        jumpErrorMessage = "Print page \"$cleanTarget\" not found."
                                    }
                                }
                                1 -> {
                                    // Location: integer 1 to totalStableLocations
                                    val cleanLoc = raw.removePrefix("EPUB").trim().removePrefix("loc.").removePrefix("loc").trim()
                                    val locNum = cleanLoc.toIntOrNull()
                                    val maxLoc = totalStableLocations.coerceAtLeast(1)
                                    if (locNum == null || locNum < 1 || locNum > maxLoc) {
                                        jumpErrorMessage = "Enter a location between 1 and $maxLoc."
                                        return@Button
                                    }
                                    val matchedPage = epubPages.firstOrNull { p ->
                                        !p.isCoverPage && locNum in p.startStableLocation..p.endStableLocation
                                    } ?: epubPages.filter { !it.isCoverPage }.minByOrNull { p ->
                                        kotlin.math.abs(p.startStableLocation - locNum)
                                    }
                                    if (matchedPage != null) {
                                        onJumpToPage((matchedPage.globalIndex + 1).coerceIn(1, totalPages))
                                        onDismiss()
                                    } else {
                                        jumpErrorMessage = "Location $locNum is out of range (1–$maxLoc)."
                                    }
                                }
                                2 -> {
                                    // Paragraph: integer within the current chapter ("¶ 12" or "12")
                                    val cleanPara = raw.removePrefix("¶¶").removePrefix("¶").trim()
                                    val paraNum = cleanPara.toIntOrNull()
                                    if (paraNum == null || paraNum < 1 || paraNum > maxParagraphInCurrentChapter) {
                                        jumpErrorMessage = "Enter a paragraph between 1 and $maxParagraphInCurrentChapter in this chapter."
                                        return@Button
                                    }
                                    val matchedPage = currentChapterPages.firstOrNull { p ->
                                        p.semanticBlocks.any { sb -> sb.paragraphNumberInChapter == paraNum } ||
                                            paraNum in p.startParagraphNumber..p.endParagraphNumber
                                    }
                                    if (matchedPage != null) {
                                        onJumpToPage((matchedPage.globalIndex + 1).coerceIn(1, totalPages))
                                        onDismiss()
                                    } else {
                                        jumpErrorMessage = "Paragraph ¶ $paraNum not found in current chapter."
                                    }
                                }
                                3 -> {
                                    // CFI: paste a string, validate with parseCfi, then resolve with resolveCfi
                                    when (val parseRes = com.example.reader.EpubCfiEngine.parseCfi(raw)) {
                                        is com.example.reader.EpubCfiParseResult.Error -> {
                                            jumpErrorMessage = parseRes.message
                                        }
                                        is com.example.reader.EpubCfiParseResult.Success -> {
                                            val resolution = com.example.reader.EpubCfiEngine.resolveCfi(
                                                cfi = raw,
                                                pages = epubPages,
                                                epubBook = epubBook
                                            )
                                            val targetPage = resolution?.page
                                            val maxSpine = (epubBook?.chapters?.size ?: epubPages.maxOfOrNull { it.chapterIndex + 1 } ?: 1).coerceAtLeast(1)
                                            if (parseRes.parsed.spineIndex < 0 || parseRes.parsed.spineIndex >= maxSpine && targetPage == null) {
                                                jumpErrorMessage = "CFI spine index is out of range for this book."
                                            } else if (targetPage != null) {
                                                onJumpToPage((targetPage.globalIndex + 1).coerceIn(1, totalPages))
                                                onDismiss()
                                            } else {
                                                val fallbackIdx = com.example.reader.EpubCfiEngine.resolveReflowPageIndexForCfi(
                                                    pages = epubPages,
                                                    rawCfi = raw,
                                                    epubBook = epubBook
                                                )
                                                if (fallbackIdx != null && fallbackIdx in 0 until totalPages) {
                                                    onJumpToPage(fallbackIdx + 1)
                                                    onDismiss()
                                                } else {
                                                    jumpErrorMessage = "CFI location could not be resolved in this book."
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("scrubber_jump_submit_button")
                    ) {
                        Text(
                            text = "Jump to location",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (activeTab == 0) {
                Button(
                    onClick = {
                        onJumpToPage(scrubPage)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                ) {
                    Text("Confirm Page", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = FontFamily.Monospace, color = Color(0xFF52525B))
            }
        }
    )
}

/**
 * KOReader Book & File Info Dialog:
 * Shows book metadata, file details, reading statistics, and quick navigation.
 */
@Composable
fun KOReaderBookInfoDialog(
    document: DocumentEntity,
    currentPage: Int,
    totalPages: Int,
    highlightsCount: Int,
    onDismiss: () -> Unit,
    onNavigateBackToLibrary: () -> Unit,
    onToggleWidgetPin: (() -> Unit)? = null
) {
    val file = remember(document.filePath) { File(document.filePath) }
    val fileSizeStr = remember(file) {
        if (file.exists()) {
            val bytes = file.length()
            if (bytes > 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
            else "${bytes / 1024} KB"
        } else "N/A"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(6.dp)),
        title = {
            com.example.ui.theme.DisableDialogDimming()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Black)
                Text(
                    text = "Document Information",
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
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.testTag("book_info_title_text")
                )
                Text(
                    text = "Author: ${document.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF3F3F46),
                    modifier = Modifier.testTag("book_info_author_text")
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(4.dp))
                        .background(Color(0xFFFAFAFA), RoundedCornerShape(4.dp))
                        .padding(10.dp)
                        .testTag("book_info_abstract_section"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Abstract / Summary",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = document.summary.trim().ifBlank { "No abstract or summary set yet." },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (document.summary.isNotBlank()) Color.Black else Color(0xFF71717A),
                        modifier = Modifier.testTag("book_info_abstract_text")
                    )
                }

                HorizontalDivider(color = Color(0xFFE4E4E7))

                // Stats Grid
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val percent = if (totalPages > 0) ((currentPage.toFloat() / totalPages) * 100).toInt() else 0
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Reading Progress", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                        Text("Page $currentPage of $totalPages ($percent%)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Format", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                        Text("${document.fileType.uppercase()} (${document.category})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("File Size", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                        Text(fileSizeStr, style = MaterialTheme.typography.labelSmall, color = Color.Black)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Obsidian Highlights", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                        Text("$highlightsCount annotations", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("File Path", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                        Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF71717A))
                    }
                }

                var showBibliography by remember { mutableStateOf(false) }
                if (showBibliography) {
                    com.example.ui.BibliographyEditorDialog(document) { showBibliography = false }
                }
                HorizontalDivider(color = Color(0xFFE4E4E7))
                OutlinedButton(
                    onClick = { showBibliography = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("book_info_bibliography_btn"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color.Black)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Bibliographic details, BibTeX & citation",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                if (onToggleWidgetPin != null) {
                    HorizontalDivider(color = Color(0xFFE4E4E7))
                    OutlinedButton(
                        onClick = onToggleWidgetPin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("book_info_toggle_widget_pin_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.Black)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (document.isPinnedToWidget) "Pinned to Home Screen Widget (Tap to Unpin)" else "Pin Book to Home Screen Widget",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onNavigateBackToLibrary()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
            ) {
                Text("Back to Library", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF71717A))
            }
        }
    )
}

/**
 * KOReader Obsidian & Highlights Hub Dialog:
 * Central workflow command center for exporting and managing highlights in Obsidian format.
 */
@Composable
fun KOReaderObsidianHubDialog(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onDismiss: () -> Unit,
    onExportObsidianMarkdown: () -> Unit,
    onCopyObsidianMarkdown: () -> Unit,
    onSyncToVault: () -> Unit,
    onExportZipVault: () -> Unit,
    onOpenHighlightPage: (Int) -> Unit,
    onOpenHighlight: (HighlightEntity) -> Unit = { onOpenHighlightPage(it.pageNumber) },
    onEditHighlight: (HighlightEntity) -> Unit = {},
    onDeleteHighlight: (HighlightEntity) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredHighlights = remember(highlights, searchQuery) {
        if (searchQuery.isBlank()) highlights
        else highlights.filter {
            it.highlightedText.contains(searchQuery, ignoreCase = true) ||
            it.userNotes.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(6.dp)),
        title = {
            com.example.ui.theme.DisableDialogDimming()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = Color(0xFF2563EB))
                    Column {
                        Text(
                            text = "Obsidian PKM Hub",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = "${highlights.size} annotations in ${document.title}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A)
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary Action Bar: 4 Obsidian Export Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 1. Export .md (Share / Save)
                    Button(
                        onClick = onExportObsidianMarkdown,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)) // Obsidian Purple
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export .md", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // 2. Copy Obsidian Markdown
                    OutlinedButton(
                        onClick = onCopyObsidianMarkdown,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        border = BorderStroke(1.dp, Color(0xFF27272A))
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy MD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }

                    // 3. Sync to Vault
                    OutlinedButton(
                        onClick = onSyncToVault,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        border = BorderStroke(1.dp, Color(0xFF27272A))
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Vault", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                OutlinedButton(
                    onClick = onExportZipVault,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    border = BorderStroke(1.dp, Color(0xFFD4D4D8))
                ) {
                    Text("📦 Export Complete Vault (ZIP + MOC.md)", fontSize = 11.sp, color = Color(0xFF3F3F46), fontWeight = FontWeight.SemiBold)
                }

                HorizontalDivider(color = Color(0xFFE4E4E7))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search highlights & reflections...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        cursorColor = Color.Black
                    )
                )

                // List of Highlights
                if (filteredHighlights.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No highlights yet.\nSelect text while reading to capture Obsidian notes." else "No notes match '$searchQuery'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF71717A),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isPdfDoc = document.fileType.equals("PDF", ignoreCase = true)
                        items(filteredHighlights, key = { it.id }) { h ->
                            val badgeColor = Color(com.example.model.parseHighlightColor(h.colorHex))
                            val pageBadgeText = com.example.reader.MarkdownExporter.formatHighlightPageDisplay(h, isPdfDoc)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenHighlight(h)
                                        onDismiss()
                                    }
                                    .testTag("hub_highlight_item_${h.id}"),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFAFAFA),
                                border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Surface(
                                                color = badgeColor,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = pageBadgeText,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black
                                                )
                                            }

                                            Text(
                                                text = h.title.ifBlank { "Note - Page ${h.pageNumber}" },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { onEditHighlight(h) },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("hub_edit_highlight_${h.id}")
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
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .testTag("hub_delete_highlight_${h.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete Highlight",
                                                    tint = Color(0xFF71717A),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Highlight text (Obsidian Quote) with visual highlighter background preview
                                    Text(
                                        text = "\"${h.highlightedText}\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF18181B),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(badgeColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )

                                    if (h.userNotes.isNotBlank()) {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Color(0xFFF4F4F5),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "Note: ${h.userNotes}",
                                                modifier = Modifier.padding(6.dp),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = Color(0xFF27272A),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Text(
                                        text = "${h.chapterTitle.ifBlank { "General" }} • Tap to jump to highlight in book",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color(0xFF71717A),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    )
}

/**
 * KOReader Tools & Reading Preferences Dialog
 */
@Composable
fun KOReaderToolsDialog(
    settings: SettingsEntity,
    onSaveSettings: (SettingsEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var volumeKeys by remember { mutableStateOf(settings.volumeKeyNavigation) }
    var volumeKeyAnim by remember { mutableStateOf(settings.volumeKeyAnimation) }
    var viewMode by remember { mutableStateOf(settings.readerViewMode.ifBlank { "PAGINATED" }) }
    var pageTurnAnim by remember { mutableStateOf(settings.pageTurnAnimation.ifBlank { "PAGE_CURL" }) }
    var wikiLinks by remember { mutableStateOf(settings.wikiLinkStyle) }
    var incAuthor by remember { mutableStateOf(settings.includeAuthorInFrontmatter) }
    var incDate by remember { mutableStateOf(settings.includeDateInFrontmatter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(6.dp)),
        title = {
            com.example.ui.theme.DisableDialogDimming()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Black)
                Text(
                    text = "KOReader & PKM Tools",
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Navigation & Hardware",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2563EB)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Vertical Scroll", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                        Text("Use a vertical lazy list instead of fixed pages so text reflows continuously", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                    }
                    Switch(
                        checked = viewMode == "VERTICAL_SCROLL",
                        onCheckedChange = { isVertical ->
                            viewMode = if (isVertical) "VERTICAL_SCROLL" else "PAGINATED"
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Volume Buttons Page Turn", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                        Text("Use hardware volume keys to turn pages", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                    }
                    Switch(
                        checked = volumeKeys,
                        onCheckedChange = { volumeKeys = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black)
                    )
                }

                if (volumeKeys) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Animate Volume Button Page Turns", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                            Text("Show 3D page curl animation when pressing Volume Up / Down", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                        }
                        Switch(
                            checked = volumeKeyAnim,
                            onCheckedChange = { volumeKeyAnim = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black),
                            modifier = Modifier.testTag("koreader_volume_anim_switch")
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Page Turn Animation",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black
                    )
                    Text(
                        text = "Physical page flip effect when swiping, using page buttons, or pressing volume keys",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF71717A)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "PAGE_CURL" to "📖 3D Page Flip",
                            "SLIDE" to "↔️ Slide",
                            "NONE" to "⚡ Instant"
                        ).forEach { (animId, label) ->
                            FilterChip(
                                selected = pageTurnAnim.equals(animId, ignoreCase = true),
                                onClick = { pageTurnAnim = animId },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.Black,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("koreader_page_anim_${animId.lowercase()}")
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE4E4E7))

                Text(
                    text = "Obsidian Markdown Output",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2563EB)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use Obsidian [[Wikilinks]]", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                        Text("Formats links as [[Page]] instead of markdown links", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                    }
                    Switch(
                        checked = wikiLinks,
                        onCheckedChange = { wikiLinks = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include Author in YAML", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                        Text("Adds [[Author]] in frontmatter for Dataview", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                    }
                    Switch(
                        checked = incAuthor,
                        onCheckedChange = { incAuthor = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include Timestamp in YAML", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                        Text("Adds creation and export ISO date", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                    }
                    Switch(
                        checked = incDate,
                        onCheckedChange = { incDate = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = settings.copy(
                        volumeKeyNavigation = volumeKeys,
                        volumeKeyAnimation = volumeKeyAnim,
                        readerViewMode = viewMode,
                        pageTurnAnimation = pageTurnAnim,
                        wikiLinkStyle = wikiLinks,
                        includeAuthorInFrontmatter = incAuthor,
                        includeDateInFrontmatter = incDate
                    )
                    onSaveSettings(updated)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF71717A))
            }
        }
    )
}

/**
 * Floating KOReader Offline Male British English & Natural Norwegian TTS Player Bar:
 * - Supports both Full Card mode and Minimized Compact Pill mode (sits unobtrusively in the bottom-right corner so it never covers text!)
 * - Instant 1-tap switching between 🇬🇧 UK Male (`en-GB`) and 🇳🇴 Norsk (`nb-NO`) offline voices
 * - Fast, low-latency offline sentence-by-sentence playback
 * - Play / Pause / Stop / Previous Sentence / Next Sentence
 * - Speed quick-pills (0.95x, 1.05x, 1.25x, 1.5x)
 */
@Composable
fun KOReaderTtsControllerCard(
    ttsState: TtsUiState,
    activeTheme: ReaderTheme,
    isMinimized: Boolean = false,
    onToggleMinimized: () -> Unit = {},
    onToggleLanguage: (String) -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onStop: () -> Unit,
    onChangeSpeechRate: (Float) -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onCloseBar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isNorwegian = ttsState.activeLanguage == "nb-NO"
    val accentColor = if (isNorwegian) Color(0xFFDC2626) else Color(0xFF2563EB)

    if (isMinimized) {
        // Compact Minimized Pill that sits unobtrusively at the bottom-right corner without covering book text
        Surface(
            modifier = modifier
                .border(BorderStroke(1.5.dp, Color.Black), RoundedCornerShape(6.dp))
                .testTag("koreader_tts_minimized_pill"),
            shape = RoundedCornerShape(6.dp),
            color = activeTheme.barColor,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Language toggle chip (toggles between 🇬🇧 EN and 🇳🇴 NO)
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onToggleLanguage(if (isNorwegian) "en-GB" else "nb-NO")
                        }
                        .testTag("tts_minimized_lang_badge"),
                    color = accentColor,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isNorwegian) "🇳🇴 NO" else "🇬🇧 UK",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Play / Pause button
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("tts_minimized_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (ttsState.isPlaying) "Pause TTS" else "Play TTS",
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Next sentence button
                IconButton(
                    onClick = onNextSentence,
                    enabled = ttsState.totalSentences > 0,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("tts_minimized_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Sentence",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (ttsState.totalSentences > 0) {
                    Text(
                        text = "${ttsState.currentSentenceIndex + 1}/${ttsState.totalSentences}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = activeTheme.secondaryTextColor
                    )
                }

                // Expand full TTS player button
                IconButton(
                    onClick = onToggleMinimized,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("tts_expand_bar_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandLess,
                        contentDescription = "Expand TTS Player",
                        tint = activeTheme.textColor,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Close / Stop button
                IconButton(
                    onClick = onCloseBar,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("tts_minimized_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close TTS Player",
                        tint = activeTheme.secondaryTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, Color.Black), RoundedCornerShape(6.dp))
            .testTag("koreader_tts_controller_card"),
        shape = RoundedCornerShape(6.dp),
        color = activeTheme.barColor,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row: Language Switcher pills (🇬🇧 UK Male / 🇳🇴 Norsk) + Minimize + Settings + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // 🇬🇧 UK Male pill
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onToggleLanguage("en-GB") }
                            .testTag("tts_lang_en_gb_chip"),
                        color = if (!isNorwegian) Color(0xFF2563EB) else activeTheme.backgroundColor,
                        border = BorderStroke(1.dp, if (!isNorwegian) Color(0xFF2563EB) else activeTheme.dividerColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "🇬🇧 UK MALE",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (!isNorwegian) Color.White else activeTheme.textColor
                        )
                    }

                    // 🇳🇴 Norsk pill
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onToggleLanguage("nb-NO") }
                            .testTag("tts_lang_nb_no_chip"),
                        color = if (isNorwegian) Color(0xFFDC2626) else activeTheme.backgroundColor,
                        border = BorderStroke(1.dp, if (isNorwegian) Color(0xFFDC2626) else activeTheme.dividerColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "🇳🇴 NORSK",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (isNorwegian) Color.White else activeTheme.textColor
                        )
                    }

                    Text(
                        text = ttsState.activeVoiceName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = activeTheme.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Minimize player button so it doesn't cover book text
                    IconButton(
                        onClick = onToggleMinimized,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("tts_minimize_bar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "Minimize TTS Player",
                            tint = activeTheme.textColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = onOpenVoiceSettings,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("tts_voice_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "TTS Voice & Pitch Settings",
                            tint = activeTheme.textColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(
                        onClick = onCloseBar,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("tts_close_bar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close TTS Player",
                            tint = activeTheme.secondaryTextColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Active spoken sentence preview
            if (ttsState.currentSentenceText.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = activeTheme.backgroundColor,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, activeTheme.dividerColor)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = ttsState.currentSentenceText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Serif,
                                fontSize = 12.sp
                            ),
                            color = activeTheme.textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("tts_current_sentence_text")
                        )
                        if (ttsState.totalSentences > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Sentence ${ttsState.currentSentenceIndex + 1} of ${ttsState.totalSentences} • ${String.format(Locale.US, "%.2fx", ttsState.speechRate)} • Offline • Vol keys adjust audio",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = activeTheme.secondaryTextColor
                            )
                        }
                    }
                }
            }

            // Playback controls + Speed chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onPreviousSentence,
                        enabled = ttsState.totalSentences > 0,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("tts_prev_sentence_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Sentence",
                            tint = activeTheme.textColor
                        )
                    }

                    Button(
                        onClick = onTogglePlayPause,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("tts_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (ttsState.isPlaying) "Pause TTS" else "Play TTS",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (ttsState.isPlaying) "Pause" else "Speak",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onNextSentence,
                        enabled = ttsState.totalSentences > 0,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("tts_next_sentence_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Sentence",
                            tint = activeTheme.textColor
                        )
                    }

                    if (ttsState.isPlaying || ttsState.isPaused) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("tts_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop TTS",
                                tint = activeTheme.secondaryTextColor
                            )
                        }
                    }
                }

                // Fast speed cycle pills
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val speeds = listOf(0.95f to "0.95x", 1.05f to "1.05x", 1.25f to "1.25x", 1.5f to "1.5x")
                    speeds.forEach { (spd, label) ->
                        val isSelected = kotlin.math.abs(ttsState.speechRate - spd) < 0.04f
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onChangeSpeechRate(spd) }
                                .testTag("tts_speed_${label}"),
                            color = if (isSelected) accentColor else activeTheme.backgroundColor,
                            border = BorderStroke(1.dp, if (isSelected) accentColor else activeTheme.dividerColor),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else activeTheme.textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for fine-tuning the Male British English (`en-GB`) & Natural Norwegian (`nb-NO`) Offline TTS voice,
 * speaking speed, baritone pitch, and automatic page turn on page completion.
 */
@Composable
fun KOReaderTtsSettingsDialog(
    ttsState: TtsUiState,
    onLanguageChange: (String) -> Unit = {},
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onAutoAdvanceChange: (Boolean) -> Unit,
    onSelectVoice: (String) -> Unit,
    onInstallSystemVoices: () -> Unit,
    onResetBritishMaleDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    val isNorwegian = ttsState.activeLanguage == "nb-NO"
    val accentColor = if (isNorwegian) Color(0xFFDC2626) else Color(0xFF2563EB)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
            .testTag("koreader_tts_settings_dialog"),
        title = {
            com.example.ui.theme.DisableDialogDimming()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = accentColor)
                Column {
                    Text(
                        text = "Offline Neural TTS (UK & Norsk)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "Fast embedded offline voice • Volume keys adjust audio while playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF71717A)
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
                // Language Selector Row (🇬🇧 British English Male vs 🇳🇴 Natural Norwegian)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Offline Language & Accent",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isNorwegian,
                            onClick = { onLanguageChange("en-GB") },
                            label = { Text("🇬🇧 British English Male", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tts_dialog_lang_en_gb")
                        )
                        FilterChip(
                            selected = isNorwegian,
                            onClick = { onLanguageChange("nb-NO") },
                            label = { Text("🇳🇴 Norsk (Natural)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFDC2626),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tts_dialog_lang_nb_no")
                        )
                    }
                }

                // Active Voice Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF4F4F5),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Active Voice Profile (${if (isNorwegian) "Norwegian Bokmål / Nynorsk" else "British English UK"})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                        Text(
                            text = ttsState.activeVoiceName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = if (ttsState.isOfflineVoiceActive) {
                                "✓ Local Offline Synthesis Active (Zero internet required)"
                            } else {
                                "Using fallback voice (Tap 'System Voices...' to download offline pack)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ttsState.isOfflineVoiceActive) Color(0xFF059669) else Color(0xFFD97706)
                        )
                    }
                }

                // Speech Rate Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Speaking Speed (Fast & Articulate)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", ttsState.speechRate),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    Slider(
                        value = ttsState.speechRate,
                        onValueChange = onSpeechRateChange,
                        valueRange = 0.6f..2.2f,
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("tts_rate_slider")
                    )
                }

                // Baritone Male Pitch Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Voice Timbre / Pitch (0.92 = Natural Male)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f", ttsState.pitch),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    Slider(
                        value = ttsState.pitch,
                        onValueChange = onPitchChange,
                        valueRange = 0.65f..1.35f,
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("tts_pitch_slider")
                    )
                }

                // Auto-turn page when TTS finishes page
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Turn Page When Finished",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Text(
                            text = "Automatically flips to the next page and continues reading aloud",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A)
                        )
                    }
                    Switch(
                        checked = ttsState.autoAdvancePage,
                        onCheckedChange = onAutoAdvanceChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.testTag("tts_auto_advance_switch")
                    )
                }

                // Available Offline Voices selector
                if (ttsState.availableVoices.isNotEmpty()) {
                    HorizontalDivider(color = Color(0xFFE4E4E7))
                    Text(
                        text = if (isNorwegian) {
                            "Available Device Norwegian Voices (Offline prioritized)"
                        } else {
                            "Available Device English Voices (British Male prioritized)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    ttsState.availableVoices.take(6).forEach { voice ->
                        val isSelected = voice.id == ttsState.activeVoiceId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectVoice(voice.id) },
                            color = if (isSelected) Color(0xFFEFF6FF) else Color.White,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) accentColor else Color(0xFFE4E4E7)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = voice.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = Color.Black
                                    )
                                    Text(
                                        text = "${voice.localeTag} • ${if (voice.isOffline) "Offline Embedded" else "Requires Network"}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color(0xFF71717A)
                                    )
                                }
                                if (isSelected) {
                                    Text(
                                        text = "Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetBritishMaleDefault,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, accentColor)
                    ) {
                        Text("Reset Default (1.05x)", fontSize = 11.sp, color = accentColor, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onInstallSystemVoices,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color(0xFF71717A))
                    ) {
                        Text("System Voices...", fontSize = 11.sp, color = Color.Black)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

