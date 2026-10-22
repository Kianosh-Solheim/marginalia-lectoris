package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.reader.*
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive **PDF Text Tools, Diagnostics, Visual Comparison, Repair & OCR Controls** Dialog.
 *
 * Implements:
 * 1. PDF Text Extraction Modes (Auto, Use Original PDF Text, Force OCR, Hybrid Mode) + per-page overrides
 * 2. Text Quality Diagnostic Tool ("Test Text Extraction") with 10 multi-method checks & 5 page classifications
 * 3. Visual Text Comparison ("Compare Text Results") showing Original PDF appearance, Extracted PDF text,
 *    Repaired text, and OCR alternative side-by-side
 * 4. Automatic Repair Suggestions & "Fix Text Extraction" action with live preview
 * 5. OCR Controls (language selection, Auto language detection, OCR for entire document, selected pages,
 *    selected regions, reprocessing poor pages, and previewing results before applying)
 * 6. Non-Destructive Editing & Export (Keep original PDF unchanged, save extraction preferences,
 *    optionally edit recognized text, export clean extracted text, and export a searchable PDF with corrected OCR layer)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTextToolsDialog(
    documentTitle: String,
    currentPageNumber: Int,
    totalPages: Int,
    renderer: PdfDocumentRenderer?,
    currentMode: PdfTextExtractionMode,
    currentPageOverrides: Map<Int, PdfPageExtractionOverride>,
    currentOcrLanguage: String,
    initialSelectedRegion: RectF = RectF(0f, 0f, 1f, 1f),
    initialSelectedText: String = "",
    initialTabIndex: Int = 0,
    onApplyExtractionConfiguration: (
        mode: PdfTextExtractionMode,
        overrides: Map<Int, PdfPageExtractionOverride>,
        ocrLanguage: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(initialTabIndex.coerceIn(0, 4)) }
    var workingMode by remember(currentMode) { mutableStateOf(currentMode) }
    var workingOverrides by remember(currentPageOverrides) { mutableStateOf(currentPageOverrides) }
    var workingOcrLanguage by remember(currentOcrLanguage) { mutableStateOf(currentOcrLanguage.ifBlank { "AUTO" }) }
    var inspectedPageNumber by remember(currentPageNumber) {
        mutableIntStateOf(currentPageNumber.coerceIn(1, totalPages.coerceAtLeast(1)))
    }

    // Diagnostics State
    var isRunningDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsProgressPage by remember { mutableIntStateOf(0) }
    var diagnosticsTotalPages by remember { mutableIntStateOf(totalPages.coerceAtLeast(1)) }
    var documentDiagnosticSummary by remember { mutableStateOf<PdfDocumentDiagnosticSummary?>(null) }
    var currentPageDiagnostic by remember { mutableStateOf<PdfPageDiagnosticReport?>(null) }

    // Visual Comparison State
    var isBuildingComparison by remember { mutableStateOf(false) }
    var comparisonRegion by remember { mutableStateOf(initialSelectedRegion) }
    var visualComparison by remember { mutableStateOf<PdfVisualTextComparison?>(null) }

    // Repair Preview State
    var selectedRepairOption by remember { mutableStateOf(PdfPageExtractionOverride.REPAIR_UNICODE_AND_DUPLICATES) }
    var repairPreviewBefore by remember { mutableStateOf("") }
    var repairPreviewAfter by remember { mutableStateOf("") }
    var isGeneratingRepairPreview by remember { mutableStateOf(false) }

    // OCR Controls & Batch Progress State
    var isRunningOcrJob by remember { mutableStateOf(false) }
    var ocrJobProgressPage by remember { mutableIntStateOf(0) }
    var ocrJobTotalPages by remember { mutableIntStateOf(1) }
    var ocrSelectedPagesRangeInput by remember(currentPageNumber, totalPages) {
        mutableStateOf("$currentPageNumber")
    }
    var ocrRegionPreset by remember { mutableStateOf("FULL_PAGE") } // FULL_PAGE, TOP_HALF, BOTTOM_HALF, LEFT_COLUMN, RIGHT_COLUMN, CUSTOM_CROP
    var ocrPreviewText by remember { mutableStateOf("") }
    var ocrDetectedLanguageLabel by remember { mutableStateOf("") }
    var showOcrPreviewAcceptBar by remember { mutableStateOf(false) }

    // Non-Destructive Editable Text & Export State
    var editablePageText by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    // Automatically analyze the current page on open so user immediately sees health indicator & plain-language guidance
    LaunchedEffect(renderer, inspectedPageNumber) {
        if (renderer != null) {
            withContext(Dispatchers.IO) {
                try {
                    val pageIdx = (inspectedPageNumber - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    val report = renderer.getPageDiagnostics(pageIdx, includeBitmapInspection = true)
                    currentPageDiagnostic = report
                    val effectiveData = renderer.extractPageTextDataWithMode(
                        pageIndex = pageIdx,
                        documentMode = workingMode,
                        pageOverride = workingOverrides[inspectedPageNumber] ?: PdfPageExtractionOverride.FOLLOW_DOCUMENT
                    )
                    editablePageText = effectiveData.fullText.ifBlank {
                        effectiveData.words.joinToString(" ") { it.text }
                    }
                    ocrDetectedLanguageLabel = PdfPageDiagnosticsAndOcrEngine.detectDominantLanguage(editablePageText)
                } catch (_: Throwable) {}
            }
        }
    }

    // Load comparison when switching to Compare tab (tab 2) or Repair tab (tab 3)
    LaunchedEffect(selectedTab, renderer, inspectedPageNumber, comparisonRegion) {
        if (renderer != null && (selectedTab == 2 || selectedTab == 3)) {
            isBuildingComparison = true
            withContext(Dispatchers.IO) {
                try {
                    val pageIdx = (inspectedPageNumber - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    val comp = PdfPageDiagnosticsAndOcrEngine.buildVisualTextComparison(
                        renderer = renderer,
                        pageIndex = pageIdx,
                        regionRectNormalized = comparisonRegion
                    )
                    visualComparison = comp
                    repairPreviewBefore = comp.originalExtractedText
                    selectedRepairOption = comp.recommendedChoice.let {
                        if (it == PdfPageExtractionOverride.FOLLOW_DOCUMENT || it == PdfPageExtractionOverride.ORIGINAL) {
                            PdfPageExtractionOverride.REPAIR_UNICODE_AND_DUPLICATES
                        } else it
                    }
                } catch (_: Throwable) {
                } finally {
                    isBuildingComparison = false
                }
            }
        }
    }

    // Update repair preview whenever selectedRepairOption changes
    LaunchedEffect(selectedRepairOption, visualComparison, renderer, inspectedPageNumber) {
        val comp = visualComparison
        if (renderer != null && comp != null) {
            isGeneratingRepairPreview = true
            withContext(Dispatchers.IO) {
                try {
                    val pageIdx = (inspectedPageNumber - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    val raw = renderer.extractRawOriginalPageTextData(pageIdx)
                    val previewData = when (selectedRepairOption) {
                        PdfPageExtractionOverride.OCR -> renderer.extractOcrPageTextData(pageIdx, comparisonRegion)
                        PdfPageExtractionOverride.HYBRID -> {
                            val ocr = renderer.extractOcrPageTextData(pageIdx, comparisonRegion)
                            PdfPageDiagnosticsAndOcrEngine.mergeHybridPageText(pageIdx, raw, ocr)
                        }
                        PdfPageExtractionOverride.REPAIR_DUPLICATES ->
                            PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                                rawTextData = raw,
                                deduplicateAndCollapseRepeats = true,
                                reconstructReadingOrder = false,
                                stripInvalidUnicode = false,
                                repairMissingSpaces = false
                            )
                        PdfPageExtractionOverride.REPAIR_MISSING_SPACES ->
                            PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                                rawTextData = raw,
                                deduplicateAndCollapseRepeats = false,
                                reconstructReadingOrder = false,
                                stripInvalidUnicode = false,
                                repairMissingSpaces = true
                            )
                        PdfPageExtractionOverride.REPAIR_READING_ORDER ->
                            PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                                rawTextData = raw,
                                deduplicateAndCollapseRepeats = false,
                                reconstructReadingOrder = true,
                                stripInvalidUnicode = false,
                                repairMissingSpaces = false
                            )
                        else ->
                            PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                                rawTextData = raw,
                                deduplicateAndCollapseRepeats = true,
                                reconstructReadingOrder = true,
                                stripInvalidUnicode = true,
                                repairMissingSpaces = true
                            )
                    }
                    repairPreviewAfter = previewData.words.joinToString(" ") { it.text }
                        .ifBlank { previewData.fullText }
                        .ifBlank { comp.ocrAlternativeText }
                } catch (_: Throwable) {
                } finally {
                    isGeneratingRepairPreview = false
                }
            }
        }
    }

    fun applyAndPersistChanges(toastMsg: String? = null) {
        renderer?.updateExtractionConfiguration(
            mode = workingMode,
            overrides = workingOverrides,
            ocrLanguage = workingOcrLanguage
        )
        onApplyExtractionConfiguration(workingMode, workingOverrides, workingOcrLanguage)
        if (!toastMsg.isNullOrBlank()) {
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        com.example.ui.theme.DisableDialogDimming()
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 14.dp)
                .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
                .testTag("pdf_text_tools_dialog"),
            shape = RoundedCornerShape(6.dp),
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Surface(
                    color = Color(0xFF18181B),
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DocumentScanner,
                                    contentDescription = null,
                                    tint = Color(0xFFA78BFA),
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "PDF Text Tools & OCR Diagnostics",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "$documentTitle • Page $inspectedPageNumber of $totalPages",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFA1A1AA),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        applyAndPersistChanges("PDF Text Extraction settings saved")
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF7C3AED),
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("save_pdf_text_tools_btn")
                                ) {
                                    Text("Apply & Close", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("close_pdf_text_tools_btn")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Page Selector & Active Mode Pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (inspectedPageNumber > 1) inspectedPageNumber--
                                    },
                                    enabled = inspectedPageNumber > 1,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                    border = BorderStroke(1.dp, Color(0xFF52525B))
                                ) {
                                    Text("‹ Prev Page", fontSize = 11.sp, color = Color.White)
                                }
                                Text(
                                    text = "Page $inspectedPageNumber / $totalPages",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (inspectedPageNumber < totalPages) inspectedPageNumber++
                                    },
                                    enabled = inspectedPageNumber < totalPages,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                    border = BorderStroke(1.dp, Color(0xFF52525B))
                                ) {
                                    Text("Next Page ›", fontSize = 11.sp, color = Color.White)
                                }
                            }

                            val activePageOverride = workingOverrides[inspectedPageNumber] ?: PdfPageExtractionOverride.FOLLOW_DOCUMENT
                            Surface(
                                color = Color(0xFF27272A),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0xFF7C3AED))
                            ) {
                                Text(
                                    text = if (activePageOverride == PdfPageExtractionOverride.FOLLOW_DOCUMENT) {
                                        "Mode: ${workingMode.shortBadge}"
                                    } else {
                                        "Page Override: ${activePageOverride.shortLabel}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFDDD6FE),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Live Page Health Banner
                currentPageDiagnostic?.let { diag ->
                    val badgeColor = Color(diag.classification.badgeColorHex)
                    Surface(
                        color = badgeColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("pdf_page_health_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(badgeColor)
                                    )
                                    Text(
                                        text = "Page ${diag.pageNumber}: ${diag.classification.displayTitle}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF18181B)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = diag.plainLanguageReport,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF3F3F46),
                                    fontSize = 11.sp
                                )
                            }
                            if (diag.classification != PdfPageHealthClassification.HEALTHY_TEXT_LAYER) {
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledTonalButton(
                                    onClick = {
                                        val updated = workingOverrides.toMutableMap()
                                        updated[inspectedPageNumber] = diag.recommendedOverride
                                        workingOverrides = updated
                                        applyAndPersistChanges("Applied ${diag.recommendedOverride.shortLabel} to Page $inspectedPageNumber")
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = badgeColor,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("quick_fix_page_btn")
                                ) {
                                    Text("Quick Fix", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Navigation Tabs for the 5 core sections
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.White,
                    contentColor = Color(0xFF18181B),
                    edgePadding = 8.dp
                ) {
                    val tabs = listOf(
                        "1. Extraction Mode",
                        "2. Test Diagnostics",
                        "3. Compare Results",
                        "4. Fix Text",
                        "5. OCR & Export"
                    )
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            modifier = Modifier.testTag("pdf_text_tools_tab_$index"),
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFE4E4E7))

                // Main Content Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (selectedTab) {
                        // =========================================================================
                        // TAB 0: PDF TEXT EXTRACTION MODES & PER-PAGE OVERRIDES
                        // =========================================================================
                        0 -> {
                            Text(
                                text = "Text Extraction Mode (Document-Wide)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Text(
                                text = "Choose how the app extracts, selects, copies, searches, and reflows text from this PDF. Your original PDF file is never modified.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF52525B)
                            )

                            PdfTextExtractionMode.entries.forEach { modeOption ->
                                val isSelected = workingMode == modeOption
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFFF5F3FF) else Color.White,
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF7C3AED) else Color(0xFFE4E4E7)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            workingMode = modeOption
                                            applyAndPersistChanges("Text Extraction Mode: ${modeOption.title}")
                                        }
                                        .testTag("extraction_mode_${modeOption.id.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                workingMode = modeOption
                                                applyAndPersistChanges("Text Extraction Mode: ${modeOption.title}")
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF7C3AED))
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = modeOption.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF18181B)
                                                )
                                                if (modeOption == PdfTextExtractionMode.AUTO) {
                                                    Surface(
                                                        color = Color(0xFFDCFCE7),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "Recommended",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF166534),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = modeOption.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF52525B)
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = Color(0xFFE4E4E7))

                            // Per-Page Override Section
                            Text(
                                text = "Individual Page Override (Page $inspectedPageNumber)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Text(
                                text = "Override the document-wide setting specifically for Page $inspectedPageNumber (useful for mixed documents containing both scanned and digital pages).",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF52525B)
                            )

                            val currentOverrideForPage = workingOverrides[inspectedPageNumber]
                                ?: PdfPageExtractionOverride.FOLLOW_DOCUMENT

                            PdfPageExtractionOverride.entries.forEach { overrideOption ->
                                val isPageSelected = currentOverrideForPage == overrideOption
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isPageSelected) Color(0xFFEDE9FE) else Color.White,
                                    border = BorderStroke(
                                        width = if (isPageSelected) 1.5.dp else 1.dp,
                                        color = if (isPageSelected) Color(0xFF7C3AED) else Color(0xFFE4E4E7)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val nextMap = workingOverrides.toMutableMap()
                                            if (overrideOption == PdfPageExtractionOverride.FOLLOW_DOCUMENT) {
                                                nextMap.remove(inspectedPageNumber)
                                            } else {
                                                nextMap[inspectedPageNumber] = overrideOption
                                            }
                                            workingOverrides = nextMap
                                            applyAndPersistChanges("Page $inspectedPageNumber override: ${overrideOption.shortLabel}")
                                        }
                                        .testTag("page_override_${overrideOption.id.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = isPageSelected,
                                            onClick = {
                                                val nextMap = workingOverrides.toMutableMap()
                                                if (overrideOption == PdfPageExtractionOverride.FOLLOW_DOCUMENT) {
                                                    nextMap.remove(inspectedPageNumber)
                                                } else {
                                                    nextMap[inspectedPageNumber] = overrideOption
                                                }
                                                workingOverrides = nextMap
                                                applyAndPersistChanges("Page $inspectedPageNumber override: ${overrideOption.shortLabel}")
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF7C3AED))
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = overrideOption.displayTitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF18181B)
                                            )
                                            Text(
                                                text = overrideOption.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF52525B)
                                            )
                                        }
                                    }
                                }
                            }

                            if (workingOverrides.isNotEmpty()) {
                                Surface(
                                    color = Color(0xFFF4F4F5),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Active per-page overrides: " +
                                                workingOverrides.entries.sortedBy { it.key }
                                                    .joinToString(", ") { "P.${it.key}: ${it.value.shortLabel}" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF3F3F46),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                workingOverrides = emptyMap()
                                                applyAndPersistChanges("Cleared all per-page overrides")
                                            }
                                        ) {
                                            Text("Clear All", fontSize = 11.sp, color = Color(0xFFDC2626))
                                        }
                                    }
                                }
                            }
                        }

                        // =========================================================================
                        // TAB 1: TEXT QUALITY DIAGNOSTIC TOOL ("Test Text Extraction")
                        // =========================================================================
                        1 -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFF5F3FF),
                                border = BorderStroke(1.5.dp, Color(0xFF7C3AED)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Text Quality Diagnostic Tool",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF18181B)
                                    )
                                    Text(
                                        text = "Analyzes the PDF for missing/damaged ToUnicode maps, replacement characters (\uFFFD), repeated letters ('TThhee'), missing word spaces ('Thequickbrownfox'), overlapping text layers, invisible/corrupted OCR layers, scrambled reading order, unselectable visible text, scanned image-only pages, and misaligned selection coordinates.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF3F3F46)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                if (renderer != null && !isRunningDiagnostics) {
                                                    isRunningDiagnostics = true
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val summary = PdfPageDiagnosticsAndOcrEngine.analyzeDocumentDiagnostics(
                                                                renderer = renderer,
                                                                maxPagesToInspect = 50,
                                                                includeBitmapInspection = true,
                                                                onProgress = { cur, tot ->
                                                                    diagnosticsProgressPage = cur
                                                                    diagnosticsTotalPages = tot
                                                                }
                                                            )
                                                            documentDiagnosticSummary = summary
                                                            val curReport = summary.pageReports.firstOrNull {
                                                                it.pageNumber == inspectedPageNumber
                                                            }
                                                            if (curReport != null) {
                                                                currentPageDiagnostic = curReport
                                                            }
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            isRunningDiagnostics = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isRunningDiagnostics && renderer != null,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF7C3AED),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("test_text_extraction_btn")
                                        ) {
                                            Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isRunningDiagnostics) {
                                                    "Analyzing Page $diagnosticsProgressPage / $diagnosticsTotalPages..."
                                                } else {
                                                    "Test Text Extraction"
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    if (isRunningDiagnostics) {
                                        LinearProgressIndicator(
                                            progress = {
                                                diagnosticsProgressPage.toFloat() / diagnosticsTotalPages.coerceAtLeast(1).toFloat()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Color(0xFF7C3AED)
                                        )
                                    }
                                }
                            }

                            // Document-wide summary if run
                            documentDiagnosticSummary?.let { summary ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("document_diagnostic_summary_card")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val overallColor = Color(summary.overallClassification.badgeColorHex)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "Document Assessment",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                color = overallColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = summary.overallClassification.displayTitle,
                                                    color = overallColor,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = summary.headlineMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF18181B)
                                        )
                                        Text(
                                            text = summary.detailedExplanation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF52525B)
                                        )

                                        // Breakdown chips
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            DiagnosticStatBadge("Healthy: ${summary.healthyPageCount}", Color(0xFF16A34A))
                                            DiagnosticStatBadge("Problems: ${summary.potentialProblemsCount}", Color(0xFFD97706))
                                            DiagnosticStatBadge("Corrupted: ${summary.corruptedCount}", Color(0xFFDC2626))
                                            DiagnosticStatBadge("Scanned: ${summary.imageOnlyCount}", Color(0xFF7C3AED))
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    workingMode = summary.recommendedDocumentMode
                                                    val nextOverrides = workingOverrides.toMutableMap()
                                                    summary.pageReports.forEach { pr ->
                                                        if (pr.classification != PdfPageHealthClassification.HEALTHY_TEXT_LAYER &&
                                                            pr.recommendedOverride != PdfPageExtractionOverride.FOLLOW_DOCUMENT
                                                        ) {
                                                            nextOverrides[pr.pageNumber] = pr.recommendedOverride
                                                        }
                                                    }
                                                    workingOverrides = nextOverrides
                                                    applyAndPersistChanges("Applied recommended mode (${summary.recommendedDocumentMode.shortBadge}) & page repairs")
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF18181B),
                                                    contentColor = Color.White
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "Apply Recommended Setup (${summary.recommendedDocumentMode.title})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Current Page Detailed Check Breakdown
                            currentPageDiagnostic?.let { pageDiag ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Page ${pageDiag.pageNumber} Diagnostic Report",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF18181B)
                                        )
                                        Text(
                                            text = "Words detected: ${pageDiag.wordCount} • Estimated columns: ${pageDiag.estimatedColumnCount} • Visual ink present: ${if (pageDiag.hasVisualInkContent) "Yes" else "No"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF52525B)
                                        )

                                        if (pageDiag.issues.isEmpty()) {
                                            Surface(
                                                color = Color(0xFFDCFCE7),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "Text extraction looks good. OCR is probably unnecessary.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF166534),
                                                    modifier = Modifier.padding(10.dp)
                                                )
                                            }
                                        } else {
                                            pageDiag.issues.forEach { issue ->
                                                Surface(
                                                    color = Color(0xFFFEF3C7),
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "Problem: ${issue.title}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF92400E)
                                                        )
                                                        Text(
                                                            text = issue.plainLanguageExplanation,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFF78350F)
                                                        )
                                                        Text(
                                                            text = "Suggested action: ${issue.suggestedActionTitle}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF18181B)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Text(
                                            text = "Note: Diagnostic checks evaluate multiple signals (Unicode mappings, character repetition, spatial overlap, column geometry, and visual ink density). Always verify critical passages using 'Compare Results'.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF71717A),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        // =========================================================================
                        // TAB 2: VISUAL TEXT COMPARISON ("Compare Text Results")
                        // =========================================================================
                        2 -> {
                            Text(
                                text = "Interactive Visual Text Comparison (Page $inspectedPageNumber)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Text(
                                text = "Compare what the PDF visibly displays with what the original PDF text layer extracts, what Layout/Deduplication Repair produces, and what OCR recognizes from that same area.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF52525B)
                            )

                            // Region selector chips (Full Page, Top Paragraph, Middle Section, Bottom Section)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val presets = listOf(
                                    "Full Page" to RectF(0f, 0f, 1f, 1f),
                                    "Top Area" to RectF(0.04f, 0.05f, 0.96f, 0.42f),
                                    "Middle Area" to RectF(0.04f, 0.30f, 0.96f, 0.70f),
                                    "Bottom Area" to RectF(0.04f, 0.58f, 0.96f, 0.95f)
                                )
                                presets.forEach { (label, rect) ->
                                    val selected = abs(comparisonRegion.top - rect.top) < 0.03f &&
                                        abs(comparisonRegion.bottom - rect.bottom) < 0.03f
                                    FilterChip(
                                        selected = selected,
                                        onClick = { comparisonRegion = rect },
                                        label = { Text(label, fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            if (isBuildingComparison) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                        Text("Comparing Original PDF Text vs. OCR...", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            visualComparison?.let { comp ->
                                // Warning prompt if unusual characters or repeated letters detected
                                Surface(
                                    color = if (comp.detectedIssuesInSelection.isNotEmpty()) Color(0xFFFEF3C7) else Color(0xFFDCFCE7),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = comp.warningPrompt,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (comp.detectedIssuesInSelection.isNotEmpty()) Color(0xFF92400E) else Color(0xFF166534),
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }

                                // 1. Original PDF Appearance (Visual Bitmap)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "1. Original PDF Appearance (What the selected area visibly says)",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF18181B)
                                        )
                                        if (comp.appearanceBitmap != null) {
                                            Image(
                                                bitmap = comp.appearanceBitmap.asImageBitmap(),
                                                contentDescription = "Original PDF Visual Appearance",
                                                contentScale = ContentScale.FillWidth,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 90.dp, max = 210.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFFF4F4F5))
                                            )
                                        } else {
                                            Text(
                                                text = initialSelectedText.ifBlank { comp.repairedNativeText },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF52525B)
                                            )
                                        }
                                    }
                                }

                                // 2. Extracted PDF Text (Original Text Layer)
                                ComparisonResultCard(
                                    title = "2. Extracted PDF Text (Raw Original Text Layer)",
                                    subtitle = "Direct extraction from the PDF's embedded font/character map",
                                    textContent = comp.originalExtractedText,
                                    badgeText = "Original PDF",
                                    badgeColor = Color(0xFF52525B),
                                    actionButtonLabel = "Use Original PDF Text",
                                    testTag = "use_original_from_comparison_btn",
                                    onSelect = {
                                        val nextMap = workingOverrides.toMutableMap()
                                        nextMap[inspectedPageNumber] = PdfPageExtractionOverride.ORIGINAL
                                        workingOverrides = nextMap
                                        applyAndPersistChanges("Using Original PDF Text for Page $inspectedPageNumber")
                                    }
                                )

                                // 3. Repaired Native Text (Deduplicated & Reordered)
                                if (comp.repairedNativeText != comp.originalExtractedText) {
                                    ComparisonResultCard(
                                        title = "3. Repaired PDF Text Layer (Deduplicated & Reordered)",
                                        subtitle = "Removes duplicate overlapping letters ('TThhee' -> 'The') and fixes column reading order",
                                        textContent = comp.repairedNativeText,
                                        badgeText = "Repaired Layer",
                                        badgeColor = Color(0xFFD97706),
                                        actionButtonLabel = "Use Repaired Text Layer",
                                        testTag = "use_repaired_from_comparison_btn",
                                        onSelect = {
                                            val nextMap = workingOverrides.toMutableMap()
                                            nextMap[inspectedPageNumber] = PdfPageExtractionOverride.REPAIR_UNICODE_AND_DUPLICATES
                                            workingOverrides = nextMap
                                            applyAndPersistChanges("Using Repaired Text Layer for Page $inspectedPageNumber")
                                        }
                                    )
                                }

                                // 4. OCR Alternative
                                ComparisonResultCard(
                                    title = "4. OCR Alternative (Recognized from Visible Page)",
                                    subtitle = "Bypasses broken font maps and recognizes text directly from the rendered page",
                                    textContent = comp.ocrAlternativeText,
                                    badgeText = "OCR Result",
                                    badgeColor = Color(0xFF7C3AED),
                                    actionButtonLabel = "Use OCR Result on This Page",
                                    testTag = "use_ocr_from_comparison_btn",
                                    onSelect = {
                                        val nextMap = workingOverrides.toMutableMap()
                                        nextMap[inspectedPageNumber] = PdfPageExtractionOverride.OCR
                                        workingOverrides = nextMap
                                        applyAndPersistChanges("Using OCR Result for Page $inspectedPageNumber")
                                    }
                                )
                            }
                        }

                        // =========================================================================
                        // TAB 3: AUTOMATIC REPAIR SUGGESTIONS ("Fix Text Extraction")
                        // =========================================================================
                        3 -> {
                            Text(
                                text = "Fix Text Extraction & Repair Suggestions",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Text(
                                text = "Select a repair method below to preview the expected result before applying it to Page $inspectedPageNumber or the entire document.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF52525B)
                            )

                            val repairMethods = listOf(
                                Triple(
                                    PdfPageExtractionOverride.OCR,
                                    "Problem: Corrupted character mapping or Scanned PDF without selectable text",
                                    "Suggested action: Use OCR instead of the original PDF text to generate a clean selectable text layer."
                                ),
                                Triple(
                                    PdfPageExtractionOverride.REPAIR_DUPLICATES,
                                    "Problem: Duplicate letters or overlapping text objects ('TThhee')",
                                    "Suggested action: Detect and remove duplicate overlapping text objects and collapse shadowed repeated letters."
                                ),
                                Triple(
                                    PdfPageExtractionOverride.REPAIR_MISSING_SPACES,
                                    "Problem: Missing spaces between words (run-together text like 'Thequickbrownfox')",
                                    "Suggested action: Segment merged words using lexical dictionary & glyph-gap analysis, or reconstruct with OCR."
                                ),
                                Triple(
                                    PdfPageExtractionOverride.REPAIR_READING_ORDER,
                                    "Problem: Incorrect multi-column text reading order",
                                    "Suggested action: Reconstruct the reading order using text positions and column layout analysis."
                                ),
                                Triple(
                                    PdfPageExtractionOverride.HYBRID,
                                    "Problem: Mixed page with partially missing or corrupted regions",
                                    "Suggested action: Keep healthy native text and apply OCR only to missing or damaged regions without duplicating text."
                                ),
                                Triple(
                                    PdfPageExtractionOverride.REPAIR_UNICODE_AND_DUPLICATES,
                                    "Problem: Combined duplicate letters + reading order + stray symbols",
                                    "Suggested action: Run full text layer repair (deduplicate + reorder columns + strip broken symbols, with OCR fallback)."
                                )
                            )

                            repairMethods.forEach { (overrideType, problemHeadline, suggestionText) ->
                                val isSelected = selectedRepairOption == overrideType
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0xFFF5F3FF) else Color.White,
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF7C3AED) else Color(0xFFE4E4E7)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedRepairOption = overrideType }
                                        .testTag("repair_option_${overrideType.id.lowercase()}")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = problemHeadline,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF18181B)
                                        )
                                        Text(
                                            text = suggestionText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF52525B)
                                        )
                                    }
                                }
                            }

                            // Expected Result Preview Card
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF4F4F5),
                                border = BorderStroke(1.dp, Color(0xFFD4D4D8)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Repair Preview (Page $inspectedPageNumber)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF18181B)
                                    )
                                    Text(
                                        text = "Before (Original PDF Text):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF71717A)
                                    )
                                    Text(
                                        text = repairPreviewBefore.take(360).ifBlank { "[No native text layer]" },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF52525B)
                                    )
                                    HorizontalDivider(color = Color(0xFFE4E4E7))
                                    Text(
                                        text = "After (${selectedRepairOption.displayTitle}):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF7C3AED)
                                    )
                                    if (isGeneratingRepairPreview) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(
                                            text = repairPreviewAfter.take(420).ifBlank { "[Preview ready]" },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Color(0xFF18181B)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val nextMap = workingOverrides.toMutableMap()
                                                nextMap[inspectedPageNumber] = selectedRepairOption
                                                workingOverrides = nextMap
                                                applyAndPersistChanges("Applied ${selectedRepairOption.shortLabel} to Page $inspectedPageNumber")
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF7C3AED),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("apply_repair_current_page_btn")
                                        ) {
                                            Text("Apply to Page $inspectedPageNumber", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val nextMap = mutableMapOf<Int, PdfPageExtractionOverride>()
                                                for (p in 1..totalPages.coerceAtLeast(1)) {
                                                    nextMap[p] = selectedRepairOption
                                                }
                                                workingOverrides = nextMap
                                                applyAndPersistChanges("Applied ${selectedRepairOption.shortLabel} to all $totalPages pages")
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("apply_repair_all_pages_btn")
                                        ) {
                                            Text("Apply to All Pages", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // =========================================================================
                        // TAB 4: OCR CONTROLS & NON-DESTRUCTIVE EXPORT
                        // =========================================================================
                        4 -> {
                            Text(
                                text = "OCR Controls & Language Settings",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                            Text(
                                text = "Configure OCR language recognition, run OCR on the entire document, selected pages, or specific page regions, and preview results before applying.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF52525B)
                            )

                            // Language Selection
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
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
                                        Text(
                                            text = "OCR Language Selection",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (ocrDetectedLanguageLabel.isNotBlank()) {
                                            Text(
                                                text = "Auto-detected: $ocrDetectedLanguageLabel",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF7C3AED),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        PdfOcrLanguageOption.entries.take(4).forEach { langOpt ->
                                            FilterChip(
                                                selected = workingOcrLanguage.equals(langOpt.code, ignoreCase = true),
                                                onClick = {
                                                    workingOcrLanguage = langOpt.code
                                                    applyAndPersistChanges("OCR Language: ${langOpt.displayName}")
                                                },
                                                label = { Text(langOpt.displayName, fontSize = 10.sp) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        PdfOcrLanguageOption.entries.drop(4).forEach { langOpt ->
                                            FilterChip(
                                                selected = workingOcrLanguage.equals(langOpt.code, ignoreCase = true),
                                                onClick = {
                                                    workingOcrLanguage = langOpt.code
                                                    applyAndPersistChanges("OCR Language: ${langOpt.displayName}")
                                                },
                                                label = { Text(langOpt.displayName, fontSize = 10.sp) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Scope & Region Controls
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Run or Preview OCR",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )

                                    OutlinedTextField(
                                        value = ocrSelectedPagesRangeInput,
                                        onValueChange = { ocrSelectedPagesRangeInput = it },
                                        label = { Text("Selected Pages (e.g. 1, 3-5)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("ocr_selected_pages_input")
                                    )

                                    Text(
                                        text = "Region Scope (for Region OCR):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF52525B)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            "FULL_PAGE" to "Full Page",
                                            "TOP_HALF" to "Top Half",
                                            "BOTTOM_HALF" to "Bottom Half",
                                            "LEFT_COL" to "Left Col",
                                            "RIGHT_COL" to "Right Col"
                                        ).forEach { (id, label) ->
                                            FilterChip(
                                                selected = ocrRegionPreset == id,
                                                onClick = { ocrRegionPreset = id },
                                                label = { Text(label, fontSize = 10.sp) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    // Action buttons: Preview OCR on Current Page/Region, Run OCR on Selected Pages, Run OCR on Entire Document, Reprocess Poor Pages
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                if (renderer != null && !isRunningOcrJob) {
                                                    isRunningOcrJob = true
                                                    ocrJobProgressPage = 1
                                                    ocrJobTotalPages = 1
                                                    val targetRect = when (ocrRegionPreset) {
                                                        "TOP_HALF" -> RectF(0f, 0f, 1f, 0.52f)
                                                        "BOTTOM_HALF" -> RectF(0f, 0.48f, 1f, 1f)
                                                        "LEFT_COL" -> RectF(0f, 0f, 0.52f, 1f)
                                                        "RIGHT_COL" -> RectF(0.48f, 0f, 1f, 1f)
                                                        else -> RectF(0f, 0f, 1f, 1f)
                                                    }
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val pageIdx = (inspectedPageNumber - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                                                            val ocrData = renderer.extractOcrPageTextData(pageIdx, targetRect)
                                                            ocrPreviewText = ocrData.words.joinToString(" ") { it.text }
                                                                .ifBlank { ocrData.fullText }
                                                            ocrDetectedLanguageLabel = PdfPageDiagnosticsAndOcrEngine.detectDominantLanguage(ocrPreviewText)
                                                            showOcrPreviewAcceptBar = true
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            isRunningOcrJob = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isRunningOcrJob && renderer != null,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("preview_ocr_region_btn")
                                        ) {
                                            Text("Preview OCR (Page $inspectedPageNumber)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                if (renderer != null && !isRunningOcrJob) {
                                                    isRunningOcrJob = true
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val pageIdx = (inspectedPageNumber - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                                                            renderer.invalidateOcrCacheForPage(pageIdx)
                                                            val fresh = renderer.extractOcrPageTextData(pageIdx)
                                                            ocrPreviewText = fresh.words.joinToString(" ") { it.text }.ifBlank { fresh.fullText }
                                                            showOcrPreviewAcceptBar = true
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            isRunningOcrJob = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isRunningOcrJob && renderer != null,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("reprocess_ocr_page_btn")
                                        ) {
                                            Text("Reprocess Page OCR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                if (renderer != null && !isRunningOcrJob) {
                                                    val targetPages = parsePageRangeInput(ocrSelectedPagesRangeInput, totalPages)
                                                    if (targetPages.isNotEmpty()) {
                                                        isRunningOcrJob = true
                                                        ocrJobTotalPages = targetPages.size
                                                        ocrJobProgressPage = 0
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val nextMap = workingOverrides.toMutableMap()
                                                                for ((idx, pageNum) in targetPages.withIndex()) {
                                                                    ocrJobProgressPage = idx + 1
                                                                    renderer.extractOcrPageTextData((pageNum - 1).coerceAtLeast(0))
                                                                    nextMap[pageNum] = PdfPageExtractionOverride.OCR
                                                                }
                                                                workingOverrides = nextMap
                                                                withContext(Dispatchers.Main) {
                                                                    applyAndPersistChanges("Completed OCR on ${targetPages.size} selected page(s)")
                                                                }
                                                            } catch (_: Throwable) {
                                                            } finally {
                                                                isRunningOcrJob = false
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isRunningOcrJob && renderer != null,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("run_ocr_selected_pages_btn")
                                        ) {
                                            Text("OCR Selected Pages", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                if (renderer != null && !isRunningOcrJob) {
                                                    isRunningOcrJob = true
                                                    val maxPages = totalPages.coerceAtLeast(1)
                                                    ocrJobTotalPages = maxPages
                                                    ocrJobProgressPage = 0
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            for (p in 1..maxPages) {
                                                                ocrJobProgressPage = p
                                                                renderer.extractOcrPageTextData(p - 1)
                                                            }
                                                            workingMode = PdfTextExtractionMode.OCR
                                                            withContext(Dispatchers.Main) {
                                                                applyAndPersistChanges("OCR enabled for entire document ($maxPages pages)")
                                                            }
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            isRunningOcrJob = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isRunningOcrJob && renderer != null,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF18181B)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("run_ocr_entire_document_btn")
                                        ) {
                                            Text("OCR Entire Document", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    if (isRunningOcrJob) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = "Processing OCR: Page $ocrJobProgressPage of $ocrJobTotalPages...",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF7C3AED)
                                            )
                                            LinearProgressIndicator(
                                                progress = {
                                                    ocrJobProgressPage.toFloat() / ocrJobTotalPages.coerceAtLeast(1).toFloat()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = Color(0xFF7C3AED)
                                            )
                                        }
                                    }

                                    if (showOcrPreviewAcceptBar && ocrPreviewText.isNotBlank()) {
                                        Surface(
                                            color = Color(0xFFF5F3FF),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFF7C3AED)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "OCR Preview Result (Page $inspectedPageNumber):",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF7C3AED)
                                                )
                                                Text(
                                                    text = ocrPreviewText.take(500),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            val nextMap = workingOverrides.toMutableMap()
                                                            nextMap[inspectedPageNumber] = if (ocrRegionPreset == "FULL_PAGE") {
                                                                PdfPageExtractionOverride.OCR
                                                            } else {
                                                                PdfPageExtractionOverride.HYBRID
                                                            }
                                                            workingOverrides = nextMap
                                                            editablePageText = ocrPreviewText
                                                            showOcrPreviewAcceptBar = false
                                                            applyAndPersistChanges("Applied previewed OCR to Page $inspectedPageNumber")
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text("Apply OCR Result", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                    OutlinedButton(
                                                        onClick = { showOcrPreviewAcceptBar = false },
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text("Discard Preview", fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Non-Destructive Text Editing & Export Section
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Non-Destructive Text Editor & Export",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF18181B)
                                    )
                                    Text(
                                        text = "Your original PDF file is never modified. You can optionally edit the recognized text for Page $inspectedPageNumber below, or export clean text / a searchable PDF with a corrected OCR text layer.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF52525B)
                                    )

                                    OutlinedTextField(
                                        value = editablePageText,
                                        onValueChange = { editablePageText = it },
                                        label = { Text("Recognized / Extracted Text (Page $inspectedPageNumber)") },
                                        minLines = 4,
                                        maxLines = 8,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("editable_recognized_text_input")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                renderer?.setManualPageTextEdit(inspectedPageNumber, editablePageText)
                                                Toast.makeText(
                                                    context,
                                                    "Saved edited text layer for Page $inspectedPageNumber (original PDF untouched)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("save_edited_page_text_btn")
                                        ) {
                                            Text("Save Page Text Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                MarkdownExporter.copyToClipboard(
                                                    context,
                                                    editablePageText,
                                                    "Clean Page $inspectedPageNumber Text"
                                                )
                                                Toast.makeText(context, "Copied clean text to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("copy_clean_page_text_btn")
                                        ) {
                                            Text("Copy Clean Text", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                if (renderer != null && !isExporting) {
                                                    isExporting = true
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val pagesList = (0 until renderer.pageCount.coerceAtLeast(1)).map { idx ->
                                                                val pNum = idx + 1
                                                                val txt = if (pNum == inspectedPageNumber && editablePageText.isNotBlank()) {
                                                                    editablePageText
                                                                } else {
                                                                    renderer.extractPageTextDataWithMode(
                                                                        pageIndex = idx,
                                                                        documentMode = workingMode,
                                                                        pageOverride = workingOverrides[pNum] ?: PdfPageExtractionOverride.FOLLOW_DOCUMENT
                                                                    ).fullText
                                                                }
                                                                pNum to txt
                                                            }
                                                            val outFile = PdfPageDiagnosticsAndOcrEngine.exportCleanExtractedTextFile(
                                                                context = context,
                                                                documentTitle = documentTitle,
                                                                pagesText = pagesList
                                                            )
                                                            withContext(Dispatchers.Main) {
                                                                exportStatusMessage = "Exported clean text: ${outFile.name}"
                                                                MarkdownExporter.shareFile(
                                                                    context = context,
                                                                    file = outFile,
                                                                    mimeType = "text/plain",
                                                                    chooserTitle = "Export Clean Extracted Text"
                                                                )
                                                            }
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            isExporting = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isExporting && renderer != null,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("export_clean_text_btn")
                                        ) {
                                            Text("Export Clean Text (.txt)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                if (renderer != null && !isExporting) {
                                                    isExporting = true
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        try {
                                                            if (editablePageText.isNotBlank()) {
                                                                renderer.setManualPageTextEdit(inspectedPageNumber, editablePageText)
                                                            }
                                                            val pdfFile = PdfPageDiagnosticsAndOcrEngine.exportSearchablePdfWithCorrectedTextLayer(
                                                                context = context,
                                                                renderer = renderer,
                                                                documentTitle = documentTitle,
                                                                extractionMode = workingMode,
                                                                pageOverrides = workingOverrides,
                                                                manualPageTextEdits = renderer.getManualPageTextEdits()
                                                            )
                                                            withContext(Dispatchers.Main) {
                                                                exportStatusMessage = "Exported searchable PDF: ${pdfFile.name}"
                                                                MarkdownExporter.shareFile(
                                                                    context = context,
                                                                    file = pdfFile,
                                                                    mimeType = "application/pdf",
                                                                    chooserTitle = "Export Searchable PDF with Corrected OCR Layer"
                                                                )
                                                            }
                                                        } catch (_: Throwable) {
                                                        } finally {
                                                            isExporting = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isExporting && renderer != null,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF18181B)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("export_searchable_pdf_btn")
                                        ) {
                                            Text("Export Searchable PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    exportStatusMessage?.let { msg ->
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF16A34A),
                                            fontWeight = FontWeight.Bold
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
}

@Composable
private fun DiagnosticStatBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ComparisonResultCard(
    title: String,
    subtitle: String,
    textContent: String,
    badgeText: String,
    badgeColor: Color,
    actionButtonLabel: String,
    testTag: String,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF18181B),
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF71717A)
            )
            Surface(
                color = Color(0xFFF4F4F5),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = textContent.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF18181B),
                    modifier = Modifier.padding(10.dp)
                )
            }
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = badgeColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag)
            ) {
                Text(actionButtonLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun parsePageRangeInput(rawInput: String, maxPages: Int): List<Int> {
    val safeMax = maxPages.coerceAtLeast(1)
    val pages = sortedSetOf<Int>()
    val parts = rawInput.split(",")
    for (part in parts) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.contains("-")) {
            val bounds = trimmed.split("-", limit = 2)
            val start = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val end = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: start
            val s = minOf(start, end).coerceIn(1, safeMax)
            val e = maxOf(start, end).coerceIn(1, safeMax)
            for (p in s..e) pages.add(p)
        } else {
            val single = trimmed.toIntOrNull()
            if (single != null && single in 1..safeMax) {
                pages.add(single)
            }
        }
    }
    return pages.toList()
}
