package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.em
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.sp
import com.example.data.local.SettingsEntity
import com.example.reader.MarkdownExporter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    currentSettings: SettingsEntity,
    onSaveSettings: (SettingsEntity) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenWidgetManager: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tagsText by remember(currentSettings) { mutableStateOf(currentSettings.defaultTags) }
    var mocTitleFormat by remember(currentSettings) { mutableStateOf(currentSettings.mocTitleFormat) }
    var wikiLinkStyle by remember(currentSettings) { mutableStateOf(currentSettings.wikiLinkStyle) }
    var includeDate by remember(currentSettings) { mutableStateOf(currentSettings.includeDateInFrontmatter) }
    var includePage by remember(currentSettings) { mutableStateOf(currentSettings.includePageInFrontmatter) }
    var includeSource by remember(currentSettings) { mutableStateOf(currentSettings.includeSourceInFrontmatter) }
    var includeAuthor by remember(currentSettings) { mutableStateOf(currentSettings.includeAuthorInFrontmatter) }
    var customYaml by remember(currentSettings) { mutableStateOf(currentSettings.customFrontmatterYaml) }
    var volumeKeyNav by remember(currentSettings) { mutableStateOf(currentSettings.volumeKeyNavigation) }
    var volumeKeyAnim by remember(currentSettings) { mutableStateOf(currentSettings.volumeKeyAnimation) }
    var readerViewMode by remember(currentSettings) { mutableStateOf(currentSettings.readerViewMode.ifBlank { "PAGINATED" }) }
    var pageTurnAnimation by remember(currentSettings) { mutableStateOf(currentSettings.pageTurnAnimation.ifBlank { "PAGE_CURL" }) }
    var readerTheme by remember(currentSettings) { mutableStateOf(if (currentSettings.readerTheme.equals("DARK", true)) "DARK" else "WHITE") }
    var readerMarginDp by remember(currentSettings) { mutableIntStateOf(currentSettings.readerMarginDp) }
    var readerFontSizeSp by remember(currentSettings) { mutableFloatStateOf(currentSettings.readerFontSizeSp) }
    var showEpubPageEquivalents by remember(currentSettings) { mutableStateOf(currentSettings.showEpubPageEquivalents) }
    var paginateByEpubPageEquivalents by remember(currentSettings) { mutableStateOf(currentSettings.paginateByEpubPageEquivalents) }
    var readerParagraphStyle by remember(currentSettings) { mutableStateOf(currentSettings.readerParagraphStyle.ifBlank { "BOTH" }) }
    var readerColumnCount by remember(currentSettings) { mutableIntStateOf(currentSettings.readerColumnCount.coerceIn(1, 2)) }
    var publisherStylesEnabled by remember(currentSettings) { mutableStateOf(currentSettings.publisherStylesEnabled) }
    var readerTopMarginDp by remember(currentSettings) { mutableIntStateOf(currentSettings.readerTopMarginDp.coerceIn(0, 160)) }
    var readerBottomMarginDp by remember(currentSettings) { mutableIntStateOf(currentSettings.readerBottomMarginDp.coerceIn(0, 160)) }
    var showContinueReadingCard by remember(currentSettings) { mutableStateOf(currentSettings.showContinueReadingCard) }
    var customStoragePath by remember(currentSettings) { mutableStateOf(currentSettings.customStoragePath) }
    var storageLocationType by remember(currentSettings) { mutableStateOf(currentSettings.storageLocationType) }

    val tagsList = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    // The index and "Find a setting", as in the web view's Settings page.
    val settingsScroll = rememberScrollState()
    var settingsQuery by remember { mutableStateOf("") }
    val groupPositions = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }

    // Saved straight away, as in the web view: every change is written after a short pause (no Save button).
    // The storage location is the exception: it moves the library, so it waits for "Use this storage".
    val autoSaved = currentSettings.copy(
        defaultTags = tagsText.trim(),
        mocTitleFormat = mocTitleFormat.trim(),
        wikiLinkStyle = wikiLinkStyle,
        includeDateInFrontmatter = includeDate,
        includePageInFrontmatter = includePage,
        includeSourceInFrontmatter = includeSource,
        includeAuthorInFrontmatter = includeAuthor,
        customFrontmatterYaml = customYaml.trim(),
        volumeKeyNavigation = volumeKeyNav,
        volumeKeyAnimation = volumeKeyAnim,
        readerViewMode = readerViewMode,
        pageTurnAnimation = pageTurnAnimation,
        readerTheme = readerTheme,
        readerMarginDp = readerMarginDp,
        readerFontSizeSp = readerFontSizeSp,
        showEpubPageEquivalents = showEpubPageEquivalents,
        paginateByEpubPageEquivalents = paginateByEpubPageEquivalents,
        readerParagraphStyle = readerParagraphStyle,
        readerColumnCount = readerColumnCount.coerceIn(1, 2),
        publisherStylesEnabled = publisherStylesEnabled,
        readerTopMarginDp = readerTopMarginDp.coerceIn(0, 160),
        readerBottomMarginDp = readerBottomMarginDp.coerceIn(0, 160),
        showContinueReadingCard = showContinueReadingCard,
        customStoragePath = currentSettings.customStoragePath,
        storageLocationType = currentSettings.storageLocationType
    )
    androidx.compose.runtime.LaunchedEffect(autoSaved) {
        if (autoSaved != currentSettings) {
            kotlinx.coroutines.delay(600)
            onSaveSettings(autoSaved)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.Black
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White
                    )
                )
                SettingsIndexBar(
                    query = settingsQuery,
                    onQueryChange = { settingsQuery = it },
                    groups = SETTINGS_GROUPS,
                    onJump = { id -> groupPositions[id]?.let { y -> coroutineScope.launch { settingsScroll.scrollTo(y) } } }
                )
                androidx.compose.material3.HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
            }
        },
        containerColor = Color.White,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(settingsScroll),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Section 4: Local Persistent Storage & Custom Storage Path
            val storageStats = remember(customStoragePath, storageLocationType) {
                StorageManager.getStorageStats(context, customStoragePath, storageLocationType)
            }

            val folderPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                if (uri != null) {
                    val path = uri.path ?: uri.toString()
                    val cleanPath = if (path.contains(":")) path.substringAfter(":") else path
                    val resolved = if (!cleanPath.startsWith("/")) "/storage/emulated/0/$cleanPath" else cleanPath
                    customStoragePath = resolved
                    storageLocationType = "CUSTOM"
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Storage folder selected: $resolved")
                    }
                }
            }

            SettingsGroup("ai", "AI", "ai provider key model prompt title summary tags claude gemini chatgpt", settingsQuery, groupPositions) {
                AiSettingsSection()

            }

            SettingsGroup("notes", "Notes & export", "moc map of content obsidian wikilink frontmatter yaml tags default metadata date page source author preview export", settingsQuery, groupPositions) {
                // Section 1: MOC Configuration
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Black, RoundedCornerShape(0.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Map of Content (MOC)",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        OutlinedTextField(
                            value = mocTitleFormat,
                            onValueChange = { mocTitleFormat = it },
                            label = { Text("MOC Title Template") },
                            supportingText = { Text("Use {{title}} placeholder. e.g. Notes on {{title}}", color = Color(0xFF3F3F46)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("moc_title_format_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black,
                                focusedLabelColor = Color.Black,
                                cursorColor = Color.Black
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "WikiLink Format [[Note Title]]",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                Text(
                                    text = if (wikiLinkStyle) "Obsidian/Logseq format: [[Note Title]]" else "Standard Markdown: [Note Title](Note Title.md)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF3F3F46)
                                )
                            }
                            Switch(
                                checked = wikiLinkStyle,
                                onCheckedChange = { wikiLinkStyle = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color.Black,
                                    uncheckedThumbColor = Color.Black,
                                    uncheckedTrackColor = Color(0xFFE4E4E7)
                                ),
                                modifier = Modifier.testTag("wikilink_style_switch")
                            )
                        }
                    }
                }

                // Section 2: YAML Frontmatter Tags & Custom Properties
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Black, RoundedCornerShape(0.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "YAML Frontmatter & Tags",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        OutlinedTextField(
                            value = tagsText,
                            onValueChange = { tagsText = it },
                            label = { Text("Default Tags (comma separated)") },
                            placeholder = { Text("Literature") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("default_tags_input"),
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black,
                                focusedLabelColor = Color.Black,
                                cursorColor = Color.Black
                            )
                        )

                        // Tag chips preview
                        if (tagsList.isNotEmpty()) {
                            Text(
                                text = "Tags in frontmatter:",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF3F3F46)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                tagsList.forEach { tag ->
                                    FilterChip(
                                        selected = true,
                                        onClick = { },
                                        label = { Text("#$tag", color = Color.White) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color.Black
                                        ),
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.height(14.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = customYaml,
                            onValueChange = { customYaml = it },
                            label = { Text("Extra Frontmatter YAML lines") },
                            placeholder = { Text("type: literature-note") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_yaml_input"),
                            minLines = 2,
                            shape = RoundedCornerShape(0.dp),
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
                }

                // Section 3: Included Metadata Fields
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Black, RoundedCornerShape(0.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Included Metadata Fields in Frontmatter",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        MetadataToggleRow("Source Document Title", "source: \"Document Title\"", includeSource) { includeSource = it }
                        MetadataToggleRow(
                            title = "Author(s)",
                            subtitle = "Author(s):\n  - \"[[Author 1]]\"\n  - \"[[Author 2]]\"",
                            checked = includeAuthor
                        ) { includeAuthor = it }
                        MetadataToggleRow("Page & Chapter Number", "page: 14, chapter: \"Chapter 1\"", includePage) { includePage = it }
                        MetadataToggleRow("Created Date & Timestamp", "date: 2026-10-02", includeDate) { includeDate = it }
                    }
                }

                // Section 4: Live Frontmatter Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(8.dp))
                        .background(Color(0xFFFAFAFA))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Frontmatter Output Preview",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        val samplePreview = buildString {
                            appendLine("---")
                            appendLine("title: \"The Ethnographer's Toolkit\"")
                            if (includeSource) appendLine("source: \"Qualitative Research\"")
                            if (includeAuthor) {
                                appendLine(MarkdownExporter.formatAuthorsFrontmatter("Margaret Diane LeCompte, Jean J. Schensul"))
                            }
                            if (includePage) appendLine("page: 14\nchapter: \"Chapter 1\"")
                            if (includeDate) appendLine("date: 2026-10-02")
                            if (customYaml.isNotBlank()) appendLine(customYaml.trim())
                            if (tagsList.isNotEmpty()) {
                                appendLine("tags:")
                                tagsList.forEach { appendLine("  - $it") }
                            }
                            appendLine("---")
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(0.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(0.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = samplePreview,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Color.Black
                            )
                        }
                    }
                }

            }

            SettingsGroup("cite", "Citations", "citation style apa chicago mla harvard reference", settingsQuery, groupPositions) {
                CitationSettingsSection()

            }

            SettingsGroup("reading", "Reading", "view pages scroll columns page turn animation volume buttons page numbers printed", settingsQuery, groupPositions) {
                // Section 4: Reader Gestures & Hardware Buttons
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Black, RoundedCornerShape(0.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Reading & Page Navigation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Reading View Mode",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                            Text(
                                text = "Choose between fixed-page pagination or a continuous vertical lazy list that reflows seamlessly regardless of font size",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = readerViewMode != "VERTICAL_SCROLL",
                                    onClick = { readerViewMode = "PAGINATED" },
                                    label = { Text("Paginated Pages") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_view_mode_paginated")
                                )
                                FilterChip(
                                    selected = readerViewMode == "VERTICAL_SCROLL",
                                    onClick = { readerViewMode = "VERTICAL_SCROLL" },
                                    label = { Text("Vertical Scroll (Continuous)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_view_mode_vertical")
                                )
                            }
                        }

                        // Column Layout (Single-Column vs Two-Column)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val isVerticalMode = readerViewMode == "VERTICAL_SCROLL"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Column Layout",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isVerticalMode) Color(0xFF71717A) else Color.Black
                                )
                                Text(
                                    text = if (isVerticalMode) "Unavailable in Vertical Scroll" else if (readerColumnCount == 2) "Two-Column Mode" else "Single-Column Mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVerticalMode) Color(0xFF71717A) else Color(0xFF2563EB)
                                )
                            }
                            Text(
                                text = if (isVerticalMode) {
                                    "Vertical Scroll mode always displays a single continuous column. Switch to Paginated Pages to choose between 1 and 2 columns."
                                } else {
                                    "Choose between a single continuous column or a side-by-side two-column layout when opening books."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = isVerticalMode || readerColumnCount == 1,
                                    onClick = { readerColumnCount = 1 },
                                    enabled = !isVerticalMode,
                                    label = { Text("Single Column (1)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_column_mode_single")
                                )
                                FilterChip(
                                    selected = !isVerticalMode && readerColumnCount == 2,
                                    onClick = { readerColumnCount = 2 },
                                    enabled = !isVerticalMode,
                                    label = { Text("Two Columns (2)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Black,
                                        selectedLabelColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_column_mode_two")
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Page Turn Animation",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                            Text(
                                text = "Physical page turn animation when turning pages forward or backward via swipe, buttons, or volume keys",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "PAGE_CURL" to "📖 3D Page Flip",
                                    "SLIDE" to "↔️ Slide",
                                    "NONE" to "⚡ Instant"
                                ).forEach { (animId, label) ->
                                    FilterChip(
                                        selected = pageTurnAnimation.equals(animId, ignoreCase = true),
                                        onClick = { pageTurnAnimation = animId },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color.Black,
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("settings_page_anim_${animId.lowercase()}")
                                    )
                                }
                            }
                        }

                        MetadataToggleRow(
                            title = "Volume Buttons Navigation",
                            subtitle = "Physical Volume Up turns to previous page, Volume Down turns to next page",
                            checked = volumeKeyNav
                        ) { volumeKeyNav = it }

                        if (volumeKeyNav) {
                            MetadataToggleRow(
                                title = "Animate Volume Button Page Turns",
                                subtitle = "Play the 3D page curl animation when turning pages with Volume Up / Down keys",
                                checked = volumeKeyAnim
                            ) { volumeKeyAnim = it }
                        }

                        MetadataToggleRow(
                            title = "Show page numbers",
                            subtitle = "For EPUBs with page numbers: the printed book's page numbers as small labels in the text, where each printed page begins",
                            checked = showEpubPageEquivalents
                        ) {
                            showEpubPageEquivalents = it
                            if (!it) paginateByEpubPageEquivalents = false
                        }

                        if (showEpubPageEquivalents) {
                            MetadataToggleRow(
                                title = "Like the printed book",
                                subtitle = "Pages: each printed page starts on a new screen, with the chapter and author at the top and the page number at the foot. Scroll: a line across the text where each printed page begins, labelled with its number",
                                checked = paginateByEpubPageEquivalents
                            ) { paginateByEpubPageEquivalents = it }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Horizontal Swipe Gestures", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
                                Text(text = "Swipe left/right anywhere on screen to turn pages", style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
                            }
                            Text(text = "Always on", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }

            }

            SettingsGroup("text", "Text & layout", "colours colour theme white black font size margins line spacing alignment paragraphs publisher styling", settingsQuery, groupPositions) {
                // Section: Default Reader Display & Colors
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Reader Display & Text Colors",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Background & Text Color",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("WHITE", "White", Color(0xFFFFFFFF) to Color(0xFF18181B)),
                                    Triple("DARK", "Black & White", Color(0xFF000000) to Color(0xFFFFFFFF))
                                ).forEach { (id, label, colors) ->
                                    val (bg, fg) = colors
                                    val isSelected = readerTheme.equals(id, ignoreCase = true)
                                    Surface(
                                        onClick = { readerTheme = id },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(if (isSelected) 2.5.dp else 1.dp, if (isSelected) Color(0xFF2563EB) else Color(0xFFD4D4D8)),
                                        color = bg,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(56.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text("Aa", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
                                            Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = fg, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        // Publisher Styling toggle
                        MetadataToggleRow(
                            title = "Preserve Publisher Styling",
                            subtitle = "Keep the book's original publisher fonts, alignment, line spacing, and paragraph formatting. Disable to apply custom typography overrides.",
                            checked = publisherStylesEnabled
                        ) { publisherStylesEnabled = it }

                        // Paragraph style selector (four options)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Paragraph style",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (publisherStylesEnabled) Color(0xFF71717A) else Color.Black
                                )
                                if (publisherStylesEnabled) {
                                    Text(
                                        text = "Overridden by Publisher Styling",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF71717A)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "INDENT" to "Indent",
                                    "SPACED" to "Spaced",
                                    "BOTH" to "Both",
                                    "NONE" to "None"
                                ).forEach { (styleId, label) ->
                                    FilterChip(
                                        selected = !publisherStylesEnabled && readerParagraphStyle.equals(styleId, ignoreCase = true),
                                        onClick = { readerParagraphStyle = styleId },
                                        enabled = !publisherStylesEnabled,
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color.Black,
                                            selectedLabelColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("settings_paragraph_style_${styleId.lowercase()}")
                                    )
                                }
                            }
                        }

                        // Left & Right Margins Slider
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Default Left & Right Margins", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
                                Text("${readerMarginDp} dp", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Slider(
                                value = readerMarginDp.toFloat(),
                                onValueChange = { readerMarginDp = it.toInt() },
                                valueRange = 4f..200f,
                                steps = 48,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Black,
                                    activeTrackColor = Color.Black,
                                    inactiveTrackColor = Color(0xFFE4E4E7)
                                )
                            )
                        }

                        // Top & Bottom Page Margins (Paginated Pages Mode)
                        val isPaginatedMode = readerViewMode != "VERTICAL_SCROLL"
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Top & Bottom Page Margins",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPaginatedMode) Color.Black else Color(0xFF71717A)
                                )
                                Text(
                                    text = if (isPaginatedMode) "Top ${readerTopMarginDp} dp • Bottom ${readerBottomMarginDp} dp" else "Paginated Pages Mode Only",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPaginatedMode) Color(0xFF2563EB) else Color(0xFF71717A)
                                )
                            }
                            if (!isPaginatedMode) {
                                Text(
                                    text = "Switch Reading View Mode to Paginated Pages to adjust top and bottom page margins.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF71717A)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Top Margin", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Color.Black)
                                        Text("${readerTopMarginDp} dp", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                    Slider(
                                        value = readerTopMarginDp.toFloat(),
                                        onValueChange = { readerTopMarginDp = it.toInt() },
                                        valueRange = 0f..160f,
                                        steps = 39,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_top_margin_slider"),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.Black,
                                            activeTrackColor = Color.Black,
                                            inactiveTrackColor = Color(0xFFE4E4E7)
                                        )
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Bottom Margin", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Color.Black)
                                        Text("${readerBottomMarginDp} dp", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                    Slider(
                                        value = readerBottomMarginDp.toFloat(),
                                        onValueChange = { readerBottomMarginDp = it.toInt() },
                                        valueRange = 0f..160f,
                                        steps = 39,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_bottom_margin_slider"),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.Black,
                                            activeTrackColor = Color.Black,
                                            inactiveTrackColor = Color(0xFFE4E4E7)
                                        )
                                    )
                                }
                            }
                        }

                        // Font Size Slider
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Default Font Size", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
                                Text("${readerFontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Slider(
                                value = readerFontSizeSp,
                                onValueChange = { readerFontSizeSp = it },
                                valueRange = 12f..76f,
                                steps = 31,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Black,
                                    activeTrackColor = Color.Black,
                                    inactiveTrackColor = Color(0xFFE4E4E7)
                                )
                            )
                        }
                    }
                }

            }

            SettingsGroup("library", "Library & screen", "library home continue reading card widget e-ink refresh ghosting", settingsQuery, groupPositions) {
                // Section 3b: Library & Home Screen + Widgets
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Black, RoundedCornerShape(0.dp))
                        .background(Color.White)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Library & Home Screen",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Show Continue reading card on Home",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Display a quick-resume banner at the top of the library. Does not affect the home-screen widget.",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                    color = Color(0xFF3F3F46)
                                )
                            }
                            Switch(
                                checked = showContinueReadingCard,
                                onCheckedChange = { checked ->
                                    showContinueReadingCard = checked
                                    onSaveSettings(
                                        currentSettings.copy(
                                            defaultTags = tagsText.trim(),
                                            mocTitleFormat = mocTitleFormat.trim(),
                                            wikiLinkStyle = wikiLinkStyle,
                                            includeDateInFrontmatter = includeDate,
                                            includePageInFrontmatter = includePage,
                                            includeSourceInFrontmatter = includeSource,
                                            includeAuthorInFrontmatter = includeAuthor,
                                            customFrontmatterYaml = customYaml.trim(),
                                            volumeKeyNavigation = volumeKeyNav,
                                            volumeKeyAnimation = volumeKeyAnim,
                                            readerViewMode = readerViewMode,
                                            pageTurnAnimation = pageTurnAnimation,
                                            readerTheme = readerTheme,
                                            readerMarginDp = readerMarginDp,
                                            readerFontSizeSp = readerFontSizeSp,
                                            showEpubPageEquivalents = showEpubPageEquivalents,
                                            paginateByEpubPageEquivalents = paginateByEpubPageEquivalents,
                                            readerParagraphStyle = readerParagraphStyle,
                                            readerColumnCount = readerColumnCount.coerceIn(1, 2),
                                            publisherStylesEnabled = publisherStylesEnabled,
                                            readerTopMarginDp = readerTopMarginDp.coerceIn(0, 160),
                                            readerBottomMarginDp = readerBottomMarginDp.coerceIn(0, 160),
                                            showContinueReadingCard = checked,
                                            customStoragePath = customStoragePath.trim(),
                                            storageLocationType = storageLocationType
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color.Black,
                                    uncheckedThumbColor = Color.Black,
                                    uncheckedTrackColor = Color(0xFFE4E4E7)
                                ),
                                modifier = Modifier.testTag("settings_show_continue_reading_card_switch")
                            )
                        }

                        Text(
                            text = "Configure your Continue Reading widget (All / Books / Articles with exact citable locus resume) or your Pinned Books Shelf widget.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF3F3F46)
                        )
                        Button(
                            onClick = onOpenWidgetManager,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_widget_manager_button")
                        ) {
                            Text(
                                text = "Configure Continue Reading & Shelf Widgets",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                EinkSettingsSection()

            }

            SettingsGroup("dictation", "Dictation", "dictation microphone speech whisper offline voice", settingsQuery, groupPositions) {
                DictationSettingsSection()

            }

            SettingsGroup("sync", "Sync & web view", "folder sync syncthing vault web server browser computer phone tablet pair qr", settingsQuery, groupPositions) {
                FolderSyncSettingsSection()

                WebServerSettingsSection()

                TabletLinkSection()

            }

            SettingsGroup("storage", "Storage & backup", "storage folder readmark backup restore google drive encryption", settingsQuery, groupPositions) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Storage Location & Persistence",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                            Text(
                                text = "Configurable",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        Text(
                            text = "Where Marginalia Lectoris keeps your files, in its Readmark folder: one folder per book or article, with the book file and its Markdown notes side by side.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF71717A)
                        )

                        // Storage Preset Selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = storageLocationType != "CUSTOM" && customStoragePath.isBlank(),
                                onClick = {
                                    storageLocationType = "DOCUMENTS"
                                    customStoragePath = ""
                                },
                                label = { Text("App Storage (Default)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.Black,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = storageLocationType == "CUSTOM" || customStoragePath.isNotBlank(),
                                onClick = {
                                    storageLocationType = "CUSTOM"
                                    if (customStoragePath.isBlank()) {
                                        customStoragePath = "/storage/emulated/0/Documents/Readmark"
                                    }
                                },
                                label = { Text("Custom Folder") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.Black,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Custom folder path input
                        if (storageLocationType == "CUSTOM" || customStoragePath.isNotBlank()) {
                            OutlinedTextField(
                                value = customStoragePath,
                                onValueChange = { customStoragePath = it },
                                label = { Text("Custom Storage Directory") },
                                placeholder = { Text("/storage/emulated/0/Documents/Readmark") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(0.dp),
                                trailingIcon = {
                                    IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                                        Icon(Icons.Default.Folder, contentDescription = "Choose Folder", tint = Color.Black)
                                    }
                                },
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

                        if (customStoragePath.trim() != currentSettings.customStoragePath || storageLocationType != currentSettings.storageLocationType) {
                            InkButton(
                                label = "Use this storage",
                                filled = true,
                                minHeight = 44.dp,
                                onClick = {
                                    onSaveSettings(autoSaved.copy(customStoragePath = customStoragePath.trim(), storageLocationType = storageLocationType))
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Storage location saved") }
                                },
                                modifier = Modifier.testTag("save_storage_button")
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(0.dp)).border(1.dp, Color.Black)
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Active Root Directory:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                Text(
                                    text = storageStats.rootPath,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = Color(0xFF3F3F46)
                                )
                            }
                        }

                        // Directory tree preview matching user's requested specification
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFFE4E4E7), RoundedCornerShape(0.dp))
                                .background(Color(0xFFFAFAFA))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = "Folder Structure:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Readmark/\n├── Articles/\n│   ├── [Article Title]/\n│   │   ├── article.pdf / article.txt\n│   │   └── highlight.md\n└── Books/\n    └── [Book Title]/\n        ├── book.epub / book.pdf\n        └── highlight.md",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    ),
                                    color = Color(0xFF27272A)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Readmark Storage Path", storageStats.rootPath)
                                    clipboard.setPrimaryClip(clip)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Storage path copied to clipboard")
                                    }
                                },
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Path", color = Color.Black, fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val db = AppDatabase.getInstance(context)
                                        StorageManager.scanAndSyncLibrary(context, db, customStoragePath, storageLocationType)
                                        snackbarHostState.showSnackbar("Storage scanned & library synced successfully")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Now", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Books: ${storageStats.booksCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                            Text(
                                text = "Articles: ${storageStats.articlesCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                            Text(
                                text = "Notes: ${storageStats.notesCount} .md",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                            Text(
                                text = "Total: ${storageStats.totalFilesCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF71717A)
                            )
                        }
                    }
                }

                BackupSettingsSection()

            }


            UninstallSettingsSection()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetadataToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.Black)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF71717A))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color.Black,
                uncheckedThumbColor = Color.Black,
                uncheckedTrackColor = Color(0xFFE4E4E7)
            )
        )
    }
}


/** The groups of Settings, in the web view's order (SettingsApi groups first, then the tablet's own). */
private val SETTINGS_GROUPS = listOf(
    "ai" to "AI", "notes" to "Notes & export", "cite" to "Citations", "reading" to "Reading",
    "text" to "Text & layout", "library" to "Library & screen", "dictation" to "Dictation",
    "sync" to "Sync & web view", "storage" to "Storage & backup"
)

/** "Find a setting" and the index of groups: a strip of square tabs; a tab jumps to its group (no scroll animation, for e-ink). */
@Composable
private fun SettingsIndexBar(query: String, onQueryChange: (String) -> Unit, groups: List<Pair<String, String>>, onJump: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .height(44.dp)
                .border(1.dp, Color.Black)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) Text("Find a setting", fontSize = 14.sp, color = Color(0xFF71717A))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.Black),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Black),
                modifier = Modifier.fillMaxWidth().testTag("settings_find")
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black)
                .horizontalScroll(rememberScrollState())
        ) {
            groups.forEachIndexed { i, (id, title) ->
                if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(Color.Black))
                Box(
                    Modifier
                        .height(44.dp)
                        .clickable { onJump(id) }
                        .padding(horizontal = 14.dp)
                        .testTag("settings_tab_$id"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black, maxLines = 1)
                }
            }
        }
    }
}

/**
 * One group of Settings: a mono label, then its sections. Hidden when "Find a setting" does not
 * match its title, its keywords or the words shown in it; reports where it starts for the index.
 */
@Composable
private fun SettingsGroup(
    id: String,
    title: String,
    keywords: String,
    query: String,
    positions: MutableMap<String, Int>,
    content: @Composable () -> Unit
) {
    val q = query.trim().lowercase()
    if (q.isNotEmpty() && q.split(' ').filter { it.isNotBlank() }.any { w -> !title.lowercase().contains(w) && !keywords.contains(w) }) return
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { positions[id] = it.positionInParent().y.toInt() },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            title.uppercase(),
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.06.em,
            color = Color.Black,
            modifier = Modifier.padding(top = 8.dp)
        )
        content()
    }
}
