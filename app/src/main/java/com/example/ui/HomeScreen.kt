package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import com.example.data.StorageManager
import com.example.reader.EpubParser
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.reader.MarkdownExporter
import com.example.reader.NoteSuggestionEngine
import com.example.reader.PdfDocumentRenderer
import com.example.reader.ReadingTimeEstimator
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
import com.example.ui.theme.kaleidoSwatchForFileType
import com.example.ui.theme.kaleidoSwatchForTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TileScalePreference(val label: String, val scaleFactor: Float) {
    COMPACT("Small", 0.82f),
    AUTO("Auto", 1.0f),
    COMFORTABLE("Large", 1.22f)
}

enum class LibraryViewMode {
    GRID,
    LIST
}

private val CARD_HEIGHT = 120.dp
private val CARD_CONTENT_ROW_HEIGHT = 120.dp
private const val DEFAULT_COVER_RATIO = 2f / 3f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    currentSection: AppNavSection,
    searchQuery: String,
    documents: List<DocumentEntity>,
    recentDocuments: List<DocumentEntity>,
    allHighlights: List<HighlightEntity> = emptyList(),
    settings: SettingsEntity = SettingsEntity(),
    onSelectSection: (AppNavSection) -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onOpenDocumentWithResume: (DocumentEntity, com.example.widget.ResumeTarget?) -> Unit = { doc, _ -> onOpenDocument(doc) },
    onOpenChapters: (DocumentEntity) -> Unit = {},
    onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit = { doc, _, _ -> onOpenDocument(doc) },
    onOpenSettings: () -> Unit,
    onAddToFolderBook: (DocumentEntity) -> Unit = {},
    onChangeCategory: (DocumentEntity, String) -> Unit = { _, _ -> },
    onNewFolderBook: () -> Unit = {},
    onOpenAllHighlights: () -> Unit,
    onAddDocumentClick: () -> Unit,
    onToggleBookWidgetPin: (DocumentEntity) -> Unit = {},
    onOpenWidgetManager: () -> Unit = {},
    onDeleteDocument: (DocumentEntity) -> Unit = {},
    onUpdateDocumentInfo: (DocumentEntity, String, String, String, Bitmap?, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onUpdateDocumentSummary: (Long, String) -> Unit = { _, _ -> },
    onExportSingleHighlight: (HighlightEntity, DocumentEntity) -> Unit = { _, _ -> },
    onExportMoc: (DocumentEntity, List<HighlightEntity>) -> Unit = { _, _ -> },
    onExportCompleteBundleZip: (DocumentEntity, List<HighlightEntity>, (MarkdownExporter.ExportBundleResult) -> Unit) -> Unit = { _, _, _ -> },
    onExportCompleteBundleFolder: (DocumentEntity, List<HighlightEntity>, File?, (MarkdownExporter.ExportBundleResult) -> Unit) -> Unit = { _, _, _, _ -> },
    onShareSourceFile: (DocumentEntity) -> Unit = {},
    onShareSelectedHighlights: (List<HighlightEntity>, DocumentEntity) -> Unit = { _, _ -> },
    onShareAllNotesAndMocOnly: (DocumentEntity, List<HighlightEntity>, Boolean) -> Unit = { _, _, _ -> },
    onUpdateCoverAspectRatio: (Long, Float) -> Unit = { _, _ -> },
    onUpdateEstimatedReadingMinutes: (Long, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val uiPrefs = remember(context) {
        context.getSharedPreferences("lector_meus_ui_prefs", android.content.Context.MODE_PRIVATE)
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var tileScalePref by remember { mutableStateOf(TileScalePreference.AUTO) }
    var libraryViewMode by remember {
        val saved = uiPrefs.getString("library_view_mode", LibraryViewMode.GRID.name)
        val initial = runCatching { LibraryViewMode.valueOf(saved ?: LibraryViewMode.GRID.name) }
            .getOrDefault(LibraryViewMode.GRID)
        mutableStateOf(initial)
    }
    val setViewMode: (LibraryViewMode) -> Unit = { mode ->
        libraryViewMode = mode
        uiPrefs.edit().putString("library_view_mode", mode.name).apply()
    }
    val highlightCountsByDocId = remember(allHighlights) {
        allHighlights.groupingBy { it.documentId }.eachCount()
    }

    var contextMenuDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var shareOptionsDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var previewMocDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var exportBundleDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var editInfoDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var bibliographyDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var coverRefreshTrigger by remember { mutableStateOf(0) }
    // Covers changed from the browser keep their file name, so the shelf is told to redraw them.
    val webCoverVersion by com.example.server.CoverChanger.version.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Transparent,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(284.dp)
                    .border(width = 2.dp, color = Color.Black),
                drawerContainerColor = Color.White,
                drawerTonalElevation = 0.dp
            ) {
                // Editorial Color E-Ink Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KaleidoAmberFill)
                        .padding(horizontal = 20.dp, vertical = 22.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Marginalia Lectoris",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.Black
                        )
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Color.Black)
                        ) {
                            Text(
                                text = "COLOR E-INK",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = KaleidoAmberInk,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
                Spacer(modifier = Modifier.height(12.dp))

                // Drawer Navigation Links — crisp ink border + Kaleido tint instead of dark heavy blocks
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = if (currentSection == AppNavSection.HOME) KaleidoCobaltInk else Color.Black) },
                    label = { Text("All files", fontWeight = if (currentSection == AppNavSection.HOME) FontWeight.Bold else FontWeight.Medium) },
                    selected = currentSection == AppNavSection.HOME,
                    onClick = {
                        onSelectSection(AppNavSection.HOME)
                        coroutineScope.launch { drawerState.close() }
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = KaleidoCobaltFill,
                        selectedTextColor = Color.Black,
                        selectedIconColor = KaleidoCobaltInk,
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .border(if (currentSection == AppNavSection.HOME) 1.5.dp else 1.dp, Color.Black)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = if (currentSection == AppNavSection.BOOKS) KaleidoSageInk else Color.Black) },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Books", fontWeight = if (currentSection == AppNavSection.BOOKS) FontWeight.Bold else FontWeight.Medium)
                            val count = recentDocuments.count { it.category == "BOOK" }
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, Color.Black)
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    },
                    selected = currentSection == AppNavSection.BOOKS,
                    onClick = {
                        onSelectSection(AppNavSection.BOOKS)
                        coroutineScope.launch { drawerState.close() }
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = KaleidoSageFill,
                        selectedTextColor = Color.Black,
                        selectedIconColor = KaleidoSageInk,
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .border(if (currentSection == AppNavSection.BOOKS) 1.5.dp else 1.dp, Color.Black)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Article, contentDescription = null, tint = if (currentSection == AppNavSection.ARTICLES) KaleidoTerracottaInk else Color.Black) },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Articles", fontWeight = if (currentSection == AppNavSection.ARTICLES) FontWeight.Bold else FontWeight.Medium)
                            val count = recentDocuments.count { it.category == "ARTICLE" }
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, Color.Black)
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    },
                    selected = currentSection == AppNavSection.ARTICLES,
                    onClick = {
                        onSelectSection(AppNavSection.ARTICLES)
                        coroutineScope.launch { drawerState.close() }
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = KaleidoTerracottaFill,
                        selectedTextColor = Color.Black,
                        selectedIconColor = KaleidoTerracottaInk,
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .border(if (currentSection == AppNavSection.ARTICLES) 1.5.dp else 1.dp, Color.Black)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.FormatQuote, contentDescription = null, tint = KaleidoAmberInk) },
                    label = { Text("Highlights & Notes", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenAllHighlights()
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .border(1.dp, Color.Black)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Widgets, contentDescription = null, tint = KaleidoPlumInk) },
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Home Screen Widget", fontWeight = FontWeight.Medium)
                            val pinnedCount = recentDocuments.count { it.isPinnedToWidget }
                            if (pinnedCount > 0) {
                                Surface(
                                    color = KaleidoPlumFill,
                                    shape = RoundedCornerShape(0.dp),
                                    border = BorderStroke(1.dp, Color.Black)
                                ) {
                                    Text(
                                        text = "$pinnedCount pinned",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = KaleidoPlumInk,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenWidgetManager()
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .border(1.dp, Color.Black)
                        .testTag("drawer_widget_manager_item")
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color.Black) },
                    label = { Text("New folder book…", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onNewFolderBook()
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .border(1.dp, Color.Black)
                )

                DrawerLibraryServerBlock(Modifier.padding(horizontal = 12.dp, vertical = 3.dp))

                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
                Spacer(modifier = Modifier.height(8.dp))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Black) },
                    label = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.White,
                        unselectedTextColor = Color.Black
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .border(1.dp, Color.Black)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    // Row 1: [ Menu ]        Marginalia Lectoris        [ + ]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = Color.White,
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .clickable { coroutineScope.launch { drawerState.open() } }
                                .testTag("open_side_drawer_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Menu",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.Black
                                )
                            }
                        }

                        Text(
                            text = "Marginalia Lectoris",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = Color.Black,
                            modifier = Modifier.align(Alignment.Center)
                        )

                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = Color.White,
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .clickable { onAddDocumentClick() }
                                .testTag("add_document_fab")
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Book or Article",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
                }
            },
            containerColor = Color.White
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.White)
            ) {
                val availableWidth = maxWidth
                val isExpandedTablet = availableWidth >= 840.dp
                val isMediumReadingTablet = availableWidth >= 600.dp && availableWidth < 840.dp
                val isCompactSmallPhone = availableWidth < 390.dp

                // Base target width of a book tile adapts to the device display class:
                // - Small phone (<390dp): ~100dp -> 3 neat bookshelf columns
                // - Standard phone (390..599dp): ~112dp -> 3-4 compact bookshelf columns
                // - Reading tablet / E-Reader (600..839dp): ~132dp -> 4-5 well-proportioned columns
                // - Large tablet / landscape (>=840dp): ~148dp -> 5-7 bookshelf columns
                val baseMinTileWidth: Dp = when {
                    isExpandedTablet -> 148.dp
                    isMediumReadingTablet -> 132.dp
                    isCompactSmallPhone -> 98.dp
                    else -> 110.dp
                }
                val effectiveMinTileWidth: Dp = (baseMinTileWidth * tileScalePref.scaleFactor).coerceIn(88.dp, 210.dp)
                val horizontalPadding: Dp = when {
                    isExpandedTablet -> 28.dp
                    isMediumReadingTablet -> 22.dp
                    else -> 16.dp
                }
                val gridHorizontalSpacing: Dp = when {
                    isExpandedTablet -> 18.dp
                    isMediumReadingTablet -> 14.dp
                    else -> 12.dp
                }
                val gridVerticalSpacing: Dp = when {
                    isExpandedTablet -> 20.dp
                    isMediumReadingTablet -> 16.dp
                    else -> 14.dp
                }
                val estimatedColumns = ((availableWidth - horizontalPadding * 2) / (effectiveMinTileWidth + gridHorizontalSpacing))
                    .toInt()
                    .coerceIn(2, 8)

                val allAvailableTags = remember(recentDocuments, currentSection) {
                    val sectionDocuments = when (currentSection) {
                        AppNavSection.BOOKS -> recentDocuments.filter { it.category == "BOOK" }
                        AppNavSection.ARTICLES -> recentDocuments.filter { it.category == "ARTICLE" }
                        else -> recentDocuments
                    }
                    sectionDocuments
                        .flatMap { NoteSuggestionEngine.parseTagsList(it.tags) }
                        .distinct()
                        .sorted()
                }

                // Row 2: [ Grid | Cards ] (left) + Tags (besides it to the right) + [ Auto (Xc) ] (right)
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Single Button View Mode Toggle showing the opposite of the current mode
                        val isCurrentlyGrid = libraryViewMode == LibraryViewMode.GRID
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = Color.White,
                            border = BorderStroke(1.5.dp, Color.Black),
                            modifier = Modifier
                                .clickable {
                                    setViewMode(if (isCurrentlyGrid) LibraryViewMode.LIST else LibraryViewMode.GRID)
                                }
                                .testTag("view_mode_toggle_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCurrentlyGrid) Icons.Default.ViewAgenda else Icons.Default.GridView,
                                    contentDescription = if (isCurrentlyGrid) "Switch to Cards View" else "Switch to Grid View",
                                    tint = Color.Black,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isCurrentlyGrid) "Cards" else "Grid",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                        }

                        // Tags placed besides the toggle button to the right of it
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (allAvailableTags.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .horizontalScroll(rememberScrollState())
                                        .testTag("my_files_tag_filter_row"),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    allAvailableTags.forEach { tag ->
                                        val normalizedSearch = searchQuery.removePrefix("#").trim()
                                        val isTagSelected = normalizedSearch.equals(tag, ignoreCase = true)
                                        Surface(
                                            shape = RoundedCornerShape(0.dp),
                                            color = if (isTagSelected) KaleidoCobaltFill else Color.White,
                                            border = BorderStroke(
                                                width = if (isTagSelected) 1.5.dp else 1.dp,
                                                color = Color.Black
                                            ),
                                            modifier = Modifier
                                                .clickable {
                                                    if (isTagSelected) {
                                                        onUpdateSearchQuery("")
                                                    } else {
                                                        onUpdateSearchQuery(tag)
                                                    }
                                                }
                                                .testTag("filter_tag_chip_$tag")
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp
                                                ),
                                                fontWeight = if (isTagSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = Color.Black,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (libraryViewMode == LibraryViewMode.GRID) {
                            // Adaptive Display Badge + Quick Tile Size Toggle (Auto / Small / Large)
                            Surface(
                                shape = RoundedCornerShape(0.dp),
                                color = Color.White,
                                border = BorderStroke(1.5.dp, Color.Black),
                                modifier = Modifier
                                    .clickable {
                                        tileScalePref = when (tileScalePref) {
                                            TileScalePreference.AUTO -> TileScalePreference.COMPACT
                                            TileScalePreference.COMPACT -> TileScalePreference.COMFORTABLE
                                            TileScalePreference.COMFORTABLE -> TileScalePreference.AUTO
                                        }
                                    }
                                    .testTag("home_tile_scale_toggle")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "${tileScalePref.label} (${estimatedColumns}c)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding)
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { newValue ->
                                onUpdateSearchQuery(newValue)
                            },
                            placeholder = {
                                Text(
                                    text = "Search books & articles by title, author, or #tag...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = Color(0xFF71717A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search library",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            onUpdateSearchQuery("")
                                        },
                                        modifier = Modifier.testTag("inline_search_clear_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear search query",
                                            tint = Color.Black,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = Color.Black
                            ),
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black,
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                cursorColor = Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("my_files_global_search_input")
                        )

                        if (searchQuery.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Found ${documents.size} ${if (documents.size == 1) "match" else "matches"} for \"${searchQuery.trim()}\"",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF52525B),
                                    modifier = Modifier.testTag("search_results_count_label")
                                )
                                Text(
                                    text = "Clear filter",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier
                                        .clickable {
                                            onUpdateSearchQuery("")
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    HorizontalDivider(
                        thickness = 1.5.dp,
                        color = Color.Black,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_and_tags_divider")
                    )

                    val continueFilterForSection = remember(currentSection) {
                        when (currentSection) {
                            AppNavSection.BOOKS -> com.example.widget.ContinueFilter.BOOKS
                            AppNavSection.ARTICLES -> com.example.widget.ContinueFilter.ARTICLES
                            else -> com.example.widget.ContinueFilter.ALL
                        }
                    }
                    val continueTargetDoc = remember(recentDocuments, continueFilterForSection) {
                        com.example.widget.ContinueReadingSelector.selectMostRecent(
                            documents = recentDocuments,
                            filter = continueFilterForSection
                        )
                    }
                    var localCardHidden by remember(settings.showContinueReadingCard) {
                        mutableStateOf(!settings.showContinueReadingCard)
                    }

                    if (settings.showContinueReadingCard && !localCardHidden && searchQuery.isBlank() && continueTargetDoc != null && documents.isNotEmpty()) {
                        ContinueReadingBannerCard(
                            document = continueTargetDoc,
                            filter = continueFilterForSection,
                            horizontalPadding = horizontalPadding,
                            onResumeClick = { doc ->
                                val hasSavedPos = doc.lastPositionCfi.isNotBlank() ||
                                    doc.lastPositionParagraph > 0 ||
                                    doc.lastPositionLoc > 0
                                val resume = if (hasSavedPos) {
                                    com.example.widget.ResumeTarget(
                                        position = com.example.widget.ReadingPosition(
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
                            },
                            onConfigureWidgetsClick = onOpenWidgetManager,
                            onHideClick = {
                                localCardHidden = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val db = com.example.data.local.AppDatabase.getInstance(context)
                                        val current = db.settingsDao().getSettingsSnapshot() ?: settings
                                        db.settingsDao().saveSettings(current.copy(showContinueReadingCard = false))
                                    } catch (_: Throwable) {}
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    "Hidden. Turn it back on in Settings.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding)
                    ) {
                        if (documents.isEmpty()) {
                            if (searchQuery.isNotBlank()) {
                                EmptySearchResultsState(
                                    query = searchQuery,
                                    onClearSearch = {
                                        onUpdateSearchQuery("")
                                    }
                                )
                            } else {
                                EmptyFilesState(
                                    onAddClick = onAddDocumentClick
                                )
                            }
                        } else if (libraryViewMode == LibraryViewMode.LIST) {
                            // Single-column Horizontal Editorial E-Paper Card List (one book per row)
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("home_horizontal_cards_list"),
                                contentPadding = PaddingValues(bottom = 24.dp, top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(documents, key = { it.id }) { doc ->
                                    DocumentHorizontalListItem(
                                        document = doc,
                                        coverRefreshKey = coverRefreshTrigger + webCoverVersion,
                                        highlightCount = highlightCountsByDocId[doc.id] ?: 0,
                                        onClick = { onOpenDocument(doc) },
                                        onLongClickOrRightClick = { contextMenuDocument = doc },
                                        onTagClick = { clickedTag ->
                                            onUpdateSearchQuery(clickedTag)
                                        },
                                        onCoverRatioResolved = { ratio ->
                                            onUpdateCoverAspectRatio(doc.id, ratio)
                                        },
                                        onReadingTimeResolved = { minutes ->
                                            onUpdateEstimatedReadingMinutes(doc.id, minutes)
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = effectiveMinTileWidth),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("home_adaptive_books_grid"),
                                contentPadding = PaddingValues(bottom = 24.dp, top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(gridHorizontalSpacing),
                                verticalArrangement = Arrangement.spacedBy(gridVerticalSpacing)
                            ) {
                                items(documents, key = { it.id }) { doc ->
                                    DocumentCardItem(
                                        document = doc,
                                        coverRefreshKey = coverRefreshTrigger + webCoverVersion,
                                        isTabletDisplay = isMediumReadingTablet || isExpandedTablet,
                                        isCompactTile = effectiveMinTileWidth < 118.dp,
                                        onClick = { onOpenDocument(doc) },
                                        onLongClickOrRightClick = { contextMenuDocument = doc },
                                        onToggleWidgetPin = { onToggleBookWidgetPin(doc) },
                                        onTagClick = { clickedTag ->
                                            onUpdateSearchQuery(clickedTag)
                                        },
                                        onCoverRatioResolved = { ratio ->
                                            onUpdateCoverAspectRatio(doc.id, ratio)
                                        },
                                        onReadingTimeResolved = { minutes ->
                                            onUpdateEstimatedReadingMinutes(doc.id, minutes)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 1. Long-press / Right-click Context Menu Dialog on a Library Item
    val activeMenuDoc = contextMenuDocument
    if (activeMenuDoc != null) {
        val latestMenuDoc = documents.firstOrNull { it.id == activeMenuDoc.id }
            ?: recentDocuments.firstOrNull { it.id == activeMenuDoc.id }
            ?: activeMenuDoc
        val docHighlights = remember(latestMenuDoc.id, allHighlights) {
            allHighlights.filter { it.documentId == latestMenuDoc.id }
        }
        LibraryItemContextMenuDialog(
            document = latestMenuDoc,
            highlights = docHighlights,
            settings = settings,
            onDismiss = { contextMenuDocument = null },
            onPreviewNotesMoc = {
                val target = latestMenuDoc
                contextMenuDocument = null
                previewMocDocument = target
            },
            onShareClick = {
                val target = latestMenuDoc
                contextMenuDocument = null
                shareOptionsDocument = target
            },
            onExportAllNotesAndSource = {
                val target = latestMenuDoc
                contextMenuDocument = null
                exportBundleDocument = target
            },
            onEditDocumentInfo = {
                val target = latestMenuDoc
                contextMenuDocument = null
                editInfoDocument = target
            },
            onUpdateDocumentTags = { updatedTagsStr ->
                onUpdateDocumentInfo(
                    latestMenuDoc,
                    latestMenuDoc.title,
                    latestMenuDoc.author,
                    updatedTagsStr,
                    null,
                    false
                )
                contextMenuDocument = latestMenuDoc.copy(tags = updatedTagsStr)
            },
            onOpenDocument = {
                val target = latestMenuDoc
                contextMenuDocument = null
                onOpenDocument(target)
            },
            onOpenChapters = {
                val target = latestMenuDoc
                contextMenuDocument = null
                onOpenChapters(target)
            },
            onAddToFolderBook = {
                val target = latestMenuDoc
                contextMenuDocument = null
                onAddToFolderBook(target)
            },
            onChangeCategory = { cat ->
                val target = latestMenuDoc
                contextMenuDocument = null
                onChangeCategory(target, cat)
                android.widget.Toast.makeText(context, if (cat == "ARTICLE") "Moved to Articles" else "Moved to Books", android.widget.Toast.LENGTH_SHORT).show()
            },
            onOpenBibliography = {
                val target = latestMenuDoc
                contextMenuDocument = null
                bibliographyDocument = target
            },
            onTogglePinToWidget = {
                onToggleBookWidgetPin(latestMenuDoc)
                contextMenuDocument = null
            },
            onDeleteDocument = {
                val target = latestMenuDoc
                contextMenuDocument = null
                onDeleteDocument(target)
            }
        )
    }

    // 1a. Share Options Dialog (Share source file, one/multiple note .md files, or all notes + MOC without source/cover)
    val activeShareDoc = shareOptionsDocument
    if (activeShareDoc != null) {
        val latestShareDoc = documents.firstOrNull { it.id == activeShareDoc.id }
            ?: recentDocuments.firstOrNull { it.id == activeShareDoc.id }
            ?: activeShareDoc
        val docHighlights = remember(latestShareDoc.id, allHighlights) {
            allHighlights.filter { it.documentId == latestShareDoc.id }
        }
        ShareDocumentOptionsDialog(
            document = latestShareDoc,
            highlights = docHighlights,
            settings = settings,
            onDismiss = { shareOptionsDocument = null },
            onShareSourceFile = {
                onShareSourceFile(latestShareDoc)
            },
            onShareSelectedNotes = { selectedList ->
                onShareSelectedHighlights(selectedList, latestShareDoc)
            },
            onShareAllNotesAndMocOnly = { asZip ->
                onShareAllNotesAndMocOnly(latestShareDoc, docHighlights, asZip)
            }
        )
    }

    // 1b. Edit Book / Article Info, Tags & Cover Dialog
    bibliographyDocument?.let { bd -> BibliographyEditorDialog(bd) { bibliographyDocument = null } }

    val activeEditDoc = editInfoDocument
    if (activeEditDoc != null) {
        val latestEditDoc = documents.firstOrNull { it.id == activeEditDoc.id } ?: activeEditDoc
        EditDocumentInfoDialog(
            document = latestEditDoc,
            settings = settings,
            onDismiss = { editInfoDocument = null },
            onSave = { newTitle, newAuthor, newTags, newCoverBitmap, resetToDefaultCover ->
                onUpdateDocumentInfo(
                    latestEditDoc,
                    newTitle,
                    newAuthor,
                    newTags,
                    newCoverBitmap,
                    resetToDefaultCover
                )
                coverRefreshTrigger++
                editInfoDocument = null
            },
            onSaveWithSummary = { newTitle, newAuthor, newSummary, newTags, newCoverBitmap, resetToDefaultCover ->
                onUpdateDocumentInfo(
                    latestEditDoc.copy(summary = newSummary),
                    newTitle,
                    newAuthor,
                    newTags,
                    newCoverBitmap,
                    resetToDefaultCover
                )
                onUpdateDocumentSummary(latestEditDoc.id, newSummary)
                coverRefreshTrigger++
                editInfoDocument = null
            }
        )
    }

    // 2. Interactive Preview of "Notes on X.md" (Map of Content & Clickable Individual Notes)
    val activePreviewDoc = previewMocDocument
    if (activePreviewDoc != null) {
        val latestPreviewDoc = documents.firstOrNull { it.id == activePreviewDoc.id } ?: activePreviewDoc
        val docHighlights = remember(latestPreviewDoc.id, allHighlights) {
            allHighlights.filter { it.documentId == latestPreviewDoc.id }
        }
        InteractiveNotesMocPreviewDialog(
            document = latestPreviewDoc,
            highlights = docHighlights,
            settings = settings,
            onDismiss = { previewMocDocument = null },
            onOpenDocumentAtPage = { page, highlight ->
                val target = latestPreviewDoc
                previewMocDocument = null
                onOpenDocumentAtPage(target, page, highlight)
            },
            onExportSingleHighlight = { highlight ->
                onExportSingleHighlight(highlight, latestPreviewDoc)
            },
            onExportMoc = {
                onExportMoc(latestPreviewDoc, docHighlights)
            },
            onOpenCompleteExportDialog = {
                val target = latestPreviewDoc
                previewMocDocument = null
                exportBundleDocument = target
            }
        )
    }

    // 3. Complete Bundle Export Dialog (ZIP Archive or Regular Folder)
    val activeExportDoc = exportBundleDocument
    if (activeExportDoc != null) {
        val latestExportDoc = documents.firstOrNull { it.id == activeExportDoc.id } ?: activeExportDoc
        val docHighlights = remember(latestExportDoc.id, allHighlights) {
            allHighlights.filter { it.documentId == latestExportDoc.id }
        }
        CompleteBundleExportDialog(
            document = latestExportDoc,
            highlights = docHighlights,
            settings = settings,
            onDismiss = { exportBundleDocument = null },
            onExportAsZip = { onResult ->
                onExportCompleteBundleZip(latestExportDoc, docHighlights, onResult)
            },
            onExportAsFolder = { targetParentFolder, onResult ->
                onExportCompleteBundleFolder(latestExportDoc, docHighlights, targetParentFolder, onResult)
            }
        )
    }
}

@Composable
fun ContinueReadingBannerCard(
    document: DocumentEntity,
    filter: com.example.widget.ContinueFilter,
    horizontalPadding: Dp,
    onResumeClick: (DocumentEntity) -> Unit,
    onConfigureWidgetsClick: () -> Unit,
    onHideClick: () -> Unit = {}
) {
    val isArticle = document.category.equals("ARTICLE", ignoreCase = true)
    val badgeLabel = if (document.fileType.equals("EPUB", ignoreCase = true)) {
        document.epubVersion.ifBlank { "EPUB" }
    } else {
        document.fileType.uppercase()
    }
    val locusSummary = remember(document) {
        com.example.widget.ContinueFormatter.locus(document)
    }
    val progressRatio = remember(document) {
        com.example.widget.ContinueFormatter.computeProgressRatio(document)
    }
    val progressPct = (progressRatio * 100f).toInt().coerceIn(0, 100)
    val timeRemaining = remember(document) {
        com.example.widget.ContinueFormatter.formatTimeRemaining(document)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
            .border(1.5.dp, Color.Black, RoundedCornerShape(0.dp))
            .background(Color.White)
            .clickable { onResumeClick(document) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("home_continue_reading_banner")
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
                Surface(
                    color = Color.Black,
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text(
                        text = "CONTINUE READING",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.5.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, Color.Black)
                ) {
                    Text(
                        text = "${if (isArticle) "ARTICLE" else "BOOK"} · $badgeLabel",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$progressPct% · $timeRemaining",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = Color.Black
                )
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    modifier = Modifier
                        .clickable { onConfigureWidgetsClick() }
                        .testTag("home_continue_banner_widget_btn")
                ) {
                    Text(
                        text = "WIDGET",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    modifier = Modifier
                        .clickable { onHideClick() }
                        .testTag("home_continue_banner_hide_btn")
                ) {
                    Text(
                        text = "×",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = document.title,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        val authorLine = com.example.widget.ContinueFormatter.author(document)
                        val inVolume = com.example.reader.EditedVolumes.reading(document) != null
                        if (authorLine.isNotBlank()) {
                            append(authorLine)
                            append(" · ")
                        }
                        if (!inVolume && document.lastPositionChapter.isNotBlank()) {
                            append(document.lastPositionChapter)
                            append(" · ")
                        }
                        append(locusSummary)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = Color(0xFF27272A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("home_continue_banner_locus")
                )
            }

            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier
                    .clickable { onResumeClick(document) }
                    .testTag("home_continue_banner_resume_btn")
            ) {
                Text(
                    text = "RESUME →",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun FolderChip(
    title: String,
    isSelected: Boolean,
    accentFill: Color = KaleidoCobaltFill,
    accentInk: Color = KaleidoCobaltInk,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(0.dp),
        color = if (isSelected) accentFill else Color.White,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = Color.Black
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp)
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 13.sp
                ),
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = Color.Black
            )
        }
    }
}

/**
 * Clean portrait document card with paper border, title, and page info.
 * Automatically scales typography and cover proportions based on whether the user is on a compact
 * phone screen or a reading tablet / large display.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCardItem(
    document: DocumentEntity,
    coverRefreshKey: Int = 0,
    isTabletDisplay: Boolean = false,
    isCompactTile: Boolean = false,
    onClick: () -> Unit,
    onLongClickOrRightClick: () -> Unit = {},
    onToggleWidgetPin: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onCoverRatioResolved: (Float) -> Unit = {},
    onReadingTimeResolved: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var firstPageBitmap by remember(document.id) { mutableStateOf<Bitmap?>(null) }
    var resolvedReadingMinutes by remember(document.id, document.estimatedReadingMinutes) {
        mutableStateOf(document.estimatedReadingMinutes)
    }

    LaunchedEffect(document.filePath, document.coverUri, document.title, document.author, coverRefreshKey) {
        withContext(Dispatchers.IO) {
            try {
                var loadedBmp: Bitmap? = null

                // 1. If coverUri is set and file exists, load it directly
                if (!document.coverUri.isNullOrBlank()) {
                    val coverFile = File(document.coverUri)
                    if (coverFile.exists() && coverFile.length() > 0) {
                        loadedBmp = BitmapFactory.decodeFile(coverFile.absolutePath)
                    }
                }

                // 2. Check cached .covers thumbnail so we don't re-open PDFs/EPUBs on every launch
                if (loadedBmp == null) {
                    val rootDir = StorageManager.getRootDirectory(context)
                    val coversDir = File(rootDir, ".covers")
                    val cachedCoverCandidates = listOf(
                        File(coversDir, "pdf_${document.id}.jpg"),
                        File(coversDir, "epub_${document.id}.jpg"),
                        File(coversDir, "daisy_${document.id}.jpg"),
                        File(coversDir, "html_${document.id}.jpg"),
                        File(coversDir, "txt_${document.id}.jpg")
                    )
                    val cachedCover = cachedCoverCandidates.firstOrNull { it.exists() && it.length() > 0L }
                    if (cachedCover != null) {
                        loadedBmp = BitmapFactory.decodeFile(cachedCover.absolutePath)
                    }
                }

                // 3. Extract or generate cover if not cached yet
                if (loadedBmp == null) {
                    val file = File(document.filePath)
                    if (file.exists()) {
                        val isPdfFile = document.fileType.equals("PDF", ignoreCase = true) ||
                            document.filePath.endsWith(".pdf", ignoreCase = true)
                        val isDaisyFile = document.fileType.equals("DAISY", ignoreCase = true) || file.isDirectory
                        if (isPdfFile) {
                            val renderer = PdfDocumentRenderer(file, context)
                            val bmp = renderer.renderPage(0, targetWidth = 400)
                            renderer.close()
                            loadedBmp = bmp
                            if (bmp != null) {
                                StorageManager.saveCoverBitmap(context, "pdf_${document.id}", bmp)
                            }
                        } else if (isDaisyFile) {
                            val bmp = EpubParser.generateBookCoverBitmap(document.title, document.author)
                            loadedBmp = bmp
                            StorageManager.saveCoverBitmap(context, "daisy_${document.id}", bmp)
                        } else if (
                            document.fileType.equals("TXT", ignoreCase = true) ||
                            document.fileType.equals("HTML", ignoreCase = true) ||
                            document.filePath.endsWith(".txt", ignoreCase = true) ||
                            document.filePath.endsWith(".html", ignoreCase = true) ||
                            document.filePath.endsWith(".htm", ignoreCase = true) ||
                            document.filePath.endsWith(".xhtml", ignoreCase = true)
                        ) {
                            val bmp = EpubParser.generateBookCoverBitmap(document.title, document.author)
                            loadedBmp = bmp
                            val cachePrefix = if (document.fileType.equals("HTML", ignoreCase = true)) "html_${document.id}" else "txt_${document.id}"
                            StorageManager.saveCoverBitmap(context, cachePrefix, bmp)
                        } else {
                            // Extract cover image or generate front page cover for EPUB!
                            val bmp = EpubParser.extractCoverBitmap(file, document.title, document.author)
                            loadedBmp = bmp
                            StorageManager.saveCoverBitmap(context, "epub_${document.id}", bmp)
                        }
                    }
                }

                if (loadedBmp != null) {
                    firstPageBitmap = loadedBmp
                    if (loadedBmp.width > 0 && loadedBmp.height > 0) {
                        val intrinsicRatio = (loadedBmp.width.toFloat() / loadedBmp.height.toFloat()).coerceIn(0.2f, 5.0f)
                        if (document.coverAspectRatio <= 0f || kotlin.math.abs(document.coverAspectRatio - intrinsicRatio) > 0.01f) {
                            onCoverRatioResolved(intrinsicRatio)
                        }
                    }
                }

                if (document.estimatedReadingMinutes <= 0) {
                    val file = File(document.filePath)
                    val calcMins = ReadingTimeEstimator.calculateReadingTimeMinutes(
                        file = file,
                        fileType = document.fileType,
                        totalPages = document.totalPages,
                        category = document.category,
                        context = context
                    )
                    if (calcMins > 0) {
                        resolvedReadingMinutes = calcMins
                        onReadingTimeResolved(calcMins)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    val coverInnerPadding = when {
        isCompactTile -> 8.dp
        isTabletDisplay -> 12.dp
        else -> 10.dp
    }
    val titleFontSize = when {
        isCompactTile -> 13.sp
        isTabletDisplay -> 16.sp
        else -> 14.sp
    }
    val metaFontSize = when {
        isCompactTile -> 10.sp
        isTabletDisplay -> 12.sp
        else -> 11.sp
    }

    // Fixed uniform grid card height & landscape cover slot (5:4 aspect ratio, wider than tall)
    // with Adaptive Cover Fill so portrait/wide covers have no empty white side bands.
    val gridCardHeight = when {
        isCompactTile -> 264.dp
        isTabletDisplay -> 320.dp
        else -> 288.dp
    }

    val formatSwatch = remember(document.fileType) { kaleidoSwatchForFileType(document.fileType) }

    // Boxed Editorial Color E-Paper Card (sharp 1.5dp black outer frame around the entire card, fixed uniform height)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridCardHeight)
            .border(width = 1.5.dp, color = Color.Black)
            .background(Color.White)
            .pointerInput(document.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            onLongClickOrRightClick()
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClickOrRightClick() }
            )
            .testTag("doc_card_${document.id}")
    ) {
        // 1. Top Cover Compartment: fixed 5:4 landscape aspect ratio with Adaptive Cover Fill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(5f / 4f)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (firstPageBitmap != null) {
                AdaptiveGridCoverImage(
                    bitmap = firstPageBitmap!!,
                    cacheKey = "${document.id}_${coverRefreshKey}_${document.coverUri.orEmpty()}_${firstPageBitmap!!.width}x${firstPageBitmap!!.height}",
                    altText = document.title,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Clean framed e-paper fallback cover at 2:3 ratio centered in the cover compartment
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(DEFAULT_COVER_RATIO)
                        .padding(coverInnerPadding)
                        .border(width = 1.dp, color = Color(0xFF27272A))
                        .background(Color.White)
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = document.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompactTile) 12.sp else 14.sp,
                                lineHeight = if (isCompactTile) 15.sp else 18.sp
                            ),
                            color = Color.Black,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.65f)
                                .height(1.dp)
                                .background(Color(0xFF27272A))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = com.example.reader.EditedVolumes.displayAuthor(document),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (isCompactTile) 9.sp else 10.sp
                            ),
                            color = Color(0xFF27272A),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Full-width crisp black divider line between Cover and Info compartment
        HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

        // 2. Bottom Metadata Compartment inside the card border (fills remaining height with bottom-pinned progress)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = if (isCompactTile) 8.dp else 10.dp, vertical = if (isCompactTile) 8.dp else 10.dp)
        ) {
            // Book / Article Title (Bold Serif)
            Text(
                text = document.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = titleFontSize,
                    lineHeight = (titleFontSize.value + 3f).sp
                ),
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (document.author.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                // Author (Monospace, subtle dark ink)
                Text(
                    text = com.example.reader.EditedVolumes.displayAuthor(document),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = metaFontSize
                    ),
                    color = Color(0xFF3F3F46),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Horizontal scrollable #Tag boxes so multiple tags fit cleanly in the fixed-height metadata compartment
            val parsedDocTags = remember(document.tags) {
                NoteSuggestionEngine.parseTagsList(document.tags)
            }
            if (parsedDocTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    parsedDocTags.forEach { tag ->
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.clickable { onTagClick(tag) }
                        ) {
                            Text(
                                text = "#$tag",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = if (isCompactTile) 9.sp else 10.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Thin horizontal rule above the format badge & progress row
            HorizontalDivider(thickness = 1.dp, color = Color(0xFFD4D4D8))

            Spacer(modifier = Modifier.height(6.dp))

            // Format Badge ([PDF] / [EPUB] / [DAISY]) on left + Estimated Reading Time & Progress on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeLabel = if (document.fileType.equals("EPUB", ignoreCase = true)) {
                    document.epubVersion.ifBlank { "EPUB" }
                } else {
                    document.fileType.uppercase()
                }
                Surface(
                    color = formatSwatch.fill,
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, Color.Black)
                ) {
                    Text(
                        text = badgeLabel,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (isCompactTile) 8.sp else 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                val readingTimeText = ReadingTimeEstimator.formatReadingTimeForCard(
                    document = document,
                    resolvedTotalMinutes = resolvedReadingMinutes,
                    compact = isCompactTile
                )
                val percent = (document.readingProgress * 100).toInt()
                val progressText = if (document.totalPages > 1 && !isCompactTile) {
                    "$readingTimeText · ${document.currentPage}/${document.totalPages} · $percent%"
                } else {
                    "$readingTimeText · $percent%"
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (isCompactTile) 8.5.sp else 10.sp
                    ),
                    fontWeight = FontWeight.Normal,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("doc_card_reading_time_${document.id}")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Crisp E-Paper Horizontal Line with Left Tick Mark & Solid Black Progress Block
            val progressFraction = document.readingProgress.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            ) {
                // Full-width horizontal baseline at top of bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black)
                )
                // Left vertical tick mark when progress is 0% (or always visible at left edge)
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(6.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black)
                )
                // Solid black progress block extending downward from the top line
                if (progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction.coerceAtLeast(0.05f))
                            .height(6.dp)
                            .align(Alignment.TopStart)
                            .background(Color.Black)
                    )
                }
            }
        }
    }
}

/**
 * Horizontal Color E-Paper Book/Article Card:
 * 1. Fixed total height CARD_HEIGHT = 132.dp (12.dp colour tab + 120.dp content row).
 *    Card height is never derived from the cover, the text, or IntrinsicSize.
 * 2. Cover fills the 120.dp content row height and keeps its own aspect ratio:
 *    Width = minOf(120.dp * ratio, maxWidth * 0.45f). Uses ContentScale.Fit (never Crop, FillBounds, or FillHeight).
 * 3. Cover width varies per card so the text Column starts directly after the cover.
 * 4. If cover width is clamped to 45% of card width, ContentScale.Fit scales the image down and centres it vertically in the 120.dp row.
 * 5. 2.dp ink rule between the cover and the text column.
 * 6. Books without a cover use the placeholder at a 2:3 ratio and follow the same rules.
 * 7. Text column uses Column(Modifier.weight(1f).fillMaxHeight()) with Spacer(Modifier.weight(1f)) before the progress rule.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentHorizontalListItem(
    document: DocumentEntity,
    coverRefreshKey: Int = 0,
    highlightCount: Int,
    onClick: () -> Unit,
    onLongClickOrRightClick: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onCoverRatioResolved: (Float) -> Unit = {},
    onReadingTimeResolved: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var firstPageBitmap by remember(document.id) { mutableStateOf<Bitmap?>(null) }
    var resolvedReadingMinutes by remember(document.id, document.estimatedReadingMinutes) {
        mutableStateOf(document.estimatedReadingMinutes)
    }

    LaunchedEffect(document.filePath, document.coverUri, document.title, document.author, coverRefreshKey) {
        withContext(Dispatchers.IO) {
            try {
                var loadedBmp: Bitmap? = null

                // 1. If coverUri is set and file exists, load it directly
                if (!document.coverUri.isNullOrBlank()) {
                    val coverFile = File(document.coverUri)
                    if (coverFile.exists() && coverFile.length() > 0) {
                        loadedBmp = BitmapFactory.decodeFile(coverFile.absolutePath)
                    }
                }

                // 2. Check cached .covers thumbnail so we don't re-open PDFs/EPUBs on every launch
                if (loadedBmp == null) {
                    val rootDir = StorageManager.getRootDirectory(context)
                    val coversDir = File(rootDir, ".covers")
                    val cachedCoverCandidates = listOf(
                        File(coversDir, "pdf_${document.id}.jpg"),
                        File(coversDir, "epub_${document.id}.jpg"),
                        File(coversDir, "daisy_${document.id}.jpg"),
                        File(coversDir, "html_${document.id}.jpg"),
                        File(coversDir, "txt_${document.id}.jpg")
                    )
                    val cachedCover = cachedCoverCandidates.firstOrNull { it.exists() && it.length() > 0L }
                    if (cachedCover != null) {
                        loadedBmp = BitmapFactory.decodeFile(cachedCover.absolutePath)
                    }
                }

                // 3. Extract or generate cover if not cached yet
                if (loadedBmp == null) {
                    val file = File(document.filePath)
                    if (file.exists()) {
                        val isPdfFile = document.fileType.equals("PDF", ignoreCase = true) ||
                            document.filePath.endsWith(".pdf", ignoreCase = true)
                        val isDaisyFile = document.fileType.equals("DAISY", ignoreCase = true) || file.isDirectory
                        if (isPdfFile) {
                            val renderer = PdfDocumentRenderer(file, context)
                            val bmp = renderer.renderPage(0, targetWidth = 400)
                            renderer.close()
                            loadedBmp = bmp
                            if (bmp != null) {
                                StorageManager.saveCoverBitmap(context, "pdf_${document.id}", bmp)
                            }
                        } else if (isDaisyFile) {
                            val bmp = EpubParser.generateBookCoverBitmap(document.title, document.author)
                            loadedBmp = bmp
                            StorageManager.saveCoverBitmap(context, "daisy_${document.id}", bmp)
                        } else if (
                            document.fileType.equals("TXT", ignoreCase = true) ||
                            document.fileType.equals("HTML", ignoreCase = true) ||
                            document.filePath.endsWith(".txt", ignoreCase = true) ||
                            document.filePath.endsWith(".html", ignoreCase = true) ||
                            document.filePath.endsWith(".htm", ignoreCase = true) ||
                            document.filePath.endsWith(".xhtml", ignoreCase = true)
                        ) {
                            val bmp = EpubParser.generateBookCoverBitmap(document.title, document.author)
                            loadedBmp = bmp
                            val cachePrefix = if (document.fileType.equals("HTML", ignoreCase = true)) "html_${document.id}" else "txt_${document.id}"
                            StorageManager.saveCoverBitmap(context, cachePrefix, bmp)
                        } else {
                            // Extract cover image or generate front page cover for EPUB
                            val bmp = EpubParser.extractCoverBitmap(file, document.title, document.author)
                            loadedBmp = bmp
                            StorageManager.saveCoverBitmap(context, "epub_${document.id}", bmp)
                        }
                    }
                }

                if (loadedBmp != null) {
                    firstPageBitmap = loadedBmp
                    if (loadedBmp.width > 0 && loadedBmp.height > 0) {
                        val intrinsicRatio = (loadedBmp.width.toFloat() / loadedBmp.height.toFloat()).coerceIn(0.2f, 5.0f)
                        if (document.coverAspectRatio <= 0f || kotlin.math.abs(document.coverAspectRatio - intrinsicRatio) > 0.01f) {
                            onCoverRatioResolved(intrinsicRatio)
                        }
                    }
                }

                if (document.estimatedReadingMinutes <= 0) {
                    val file = File(document.filePath)
                    val calcMins = ReadingTimeEstimator.calculateReadingTimeMinutes(
                        file = file,
                        fileType = document.fileType,
                        totalPages = document.totalPages,
                        category = document.category,
                        context = context
                    )
                    if (calcMins > 0) {
                        resolvedReadingMinutes = calcMins
                        onReadingTimeResolved(calcMins)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    // Read stored ratio from the book record first; fall back to decoded bitmap ratio; until known, use 2f/3f as placeholder
    val ratio: Float = when {
        document.coverAspectRatio > 0f -> document.coverAspectRatio
        firstPageBitmap != null && firstPageBitmap!!.height > 0 ->
            firstPageBitmap!!.width.toFloat() / firstPageBitmap!!.height.toFloat()
        else -> DEFAULT_COVER_RATIO
    }

    val formatSwatch = remember(document.fileType) { kaleidoSwatchForFileType(document.fileType) }

    val parsedDocTags = remember(document.tags) {
        NoteSuggestionEngine.parseTagsList(document.tags)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .border(width = 2.dp, color = Color.Black)
            .background(Color.White)
            .pointerInput(document.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            onLongClickOrRightClick()
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClickOrRightClick() }
            )
            .testTag("doc_horizontal_card_${document.id}")
    ) {
        // Content Row: fixed 120.dp height (CARD_CONTENT_ROW_HEIGHT)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(CARD_CONTENT_ROW_HEIGHT)
        ) {
            val coverW = minOf(CARD_CONTENT_ROW_HEIGHT * ratio, maxWidth * 0.45f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CARD_CONTENT_ROW_HEIGHT)
            ) {
                // Cover column: width = minOf(120.dp * ratio, maxWidth * 0.45f), ContentScale.Fit, centered vertically
                Box(
                    modifier = Modifier
                        .width(coverW)
                        .fillMaxHeight()
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (firstPageBitmap != null) {
                        Image(
                            bitmap = firstPageBitmap!!.asImageBitmap(),
                            contentDescription = document.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Existing placeholder at 2:3 ratio following the same rules
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                                .border(width = 1.dp, color = Color(0xFF27272A))
                                .background(Color.White)
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = document.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    ),
                                    color = Color.Black,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (document.author.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.65f)
                                            .height(1.dp)
                                            .background(Color(0xFF27272A))
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = com.example.reader.EditedVolumes.displayAuthor(document),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp
                                        ),
                                        color = Color(0xFF27272A),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // 2.dp ink rule between the cover and the text column
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.Black)
                )

                // Text column: Column(Modifier.weight(1f).fillMaxHeight()) with Spacer(Modifier.weight(1f)) before progress rule
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.White)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    // Title (max 2 lines) with the colored file-type badge at the top right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = document.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                lineHeight = 18.sp
                            ),
                            color = Color.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )

                        val badgeLabel = if (document.fileType.equals("EPUB", ignoreCase = true)) {
                            document.epubVersion.ifBlank { "EPUB" }
                        } else {
                            document.fileType.uppercase()
                        }
                        Surface(
                            color = formatSwatch.fill,
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.padding(top = 1.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = Color.Black
                            )
                        }
                    }

                    // Author Subtitle
                    if (document.author.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = com.example.reader.EditedVolumes.displayAuthor(document),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                lineHeight = 14.sp
                            ),
                            color = Color(0xFF27272A),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Tag chips
                    if (parsedDocTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            parsedDocTags.forEach { tag ->
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(0.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    modifier = Modifier.clickable { onTagClick(tag) }
                                ) {
                                    Text(
                                        text = "#$tag",
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color.Black,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Progress rule and "% / hl" readout pinned to the bottom
                    val progressFraction = document.readingProgress.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.2.dp)
                                .background(Color.Black)
                        )
                        if (progressFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction.coerceAtLeast(0.05f))
                                    .height(5.dp)
                                    .background(Color.Black)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    val percent = (document.readingProgress * 100).toInt()
                    val readingTimeLabel = ReadingTimeEstimator.formatReadingTimeForCard(
                        document = document,
                        resolvedTotalMinutes = resolvedReadingMinutes,
                        compact = false
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$percent% · $readingTimeLabel",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("doc_horizontal_reading_time_${document.id}")
                        )
                        Text(
                            text = "$highlightCount hl",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyFilesState(
    onAddClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 80.dp)
                    .border(width = 1.5.dp, color = Color.Black, shape = RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NoteAlt,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = "No files yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Text(
                text = "Import EPUB and PDF books or articles to read, highlight, and export to Markdown.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF71717A),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onAddClick,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("empty_state_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Book or Article", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun EmptySearchResultsState(
    query: String,
    onClearSearch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
            .testTag("empty_search_results_state"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .border(width = 1.5.dp, color = Color.Black, shape = RoundedCornerShape(28.dp))
                    .background(Color(0xFFFAFAFA)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "No matches found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Text(
                text = "No books or articles matched \"${query.trim()}\" by title, author, or #tags.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF71717A),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedButton(
                onClick = onClearSearch,
                border = BorderStroke(1.dp, Color.Black),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("clear_search_results_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear Search Filter", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

