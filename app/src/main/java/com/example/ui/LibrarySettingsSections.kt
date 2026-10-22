package com.example.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.BackupManager
import com.example.reader.CitationStyle
import com.example.reader.Citations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val SectionGrey = Color(0xFF3F3F46)

@Composable
private fun SectionBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            content()
        }
    }
}

// ----------------------------------------------------------------- Backup

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var autoTarget by remember { mutableStateOf(BackupManager.autoTarget(context)) }
    var lastBackup by remember { mutableStateOf(BackupManager.lastBackupTime(context)) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val backupNow = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "Backing up… (large libraries can take a few minutes)"
        scope.launch {
            runCatching { BackupManager.backupToUri(context, uri) }
                .onSuccess { n -> status = "Backup saved, including $n book files."; lastBackup = BackupManager.lastBackupTime(context) }
                .onFailure { status = "Backup failed: ${it.message}" }
            busy = false
        }
    }
    val chooseAuto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        BackupManager.enableAuto(context, uri)
        autoTarget = uri
        busy = true; status = "Automatic backup on. Making the first backup now…"
        scope.launch {
            runCatching { BackupManager.backupToUri(context, uri) }
                .onSuccess { status = "Automatic daily backup is on."; lastBackup = BackupManager.lastBackupTime(context) }
                .onFailure { status = "First backup failed: ${it.message}" }
            busy = false
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(4.dp),
            title = { Text("Restore this backup?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text("Your current library, notes and settings will be replaced by the backup. The app restarts when it's done.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    busy = true; status = "Restoring…"
                    scope.launch {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { BackupManager.restore(context, it) }
                                ?: error("Could not open the backup file.")
                        }.onSuccess { BackupManager.restartApp(context) }
                            .onFailure { status = "Restore failed: ${it.message}"; busy = false }
                    }
                }) { Text("Replace and restart", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel", color = SectionGrey) } }
        )
    }

    SectionBox("Backup") {
        Text(
            "Backs up everything: books, notes, highlights, bibliographic details, sketches and settings, as one .zip. " +
                "In the file picker, choose Google Drive, OneDrive, Dropbox or a folder on the tablet " +
                "(the cloud app must be installed on the tablet). API keys are not included.",
            style = MaterialTheme.typography.bodySmall, color = SectionGrey
        )
        if (lastBackup > 0) {
            Text(
                "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastBackup))}",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Back up now", icon = Icons.Default.CloudUpload, filled = true, enabled = !busy,
                onClick = { backupNow.launch(BackupManager.suggestedFileName()) })
            InkButton("Restore from backup", icon = Icons.Default.Restore, enabled = !busy,
                onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) })
        }
        HorizontalDivider(color = Color(0xFFE4E4E7))
        Text("Automatic daily backup", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
        Text(
            if (autoTarget != null) "On. Once a day the backup file you chose is updated, but only if something changed."
            else "Choose a backup file once (for example in Google Drive or OneDrive); it is then updated once a day.",
            style = MaterialTheme.typography.bodySmall, color = SectionGrey
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(if (autoTarget == null) "Turn on…" else "Change backup file…", enabled = !busy,
                onClick = { chooseAuto.launch("Marginalia-Lectoris-auto-backup.zip") })
            if (autoTarget != null) InkButton("Turn off", enabled = !busy, onClick = {
                BackupManager.disableAuto(context); autoTarget = null; status = "Automatic backup is off."
            })
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.Black, trackColor = Color(0xFFE4E4E7))
        if (status.isNotBlank()) Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
    }
}

// ----------------------------------------------------------------- Citations

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CitationSettingsSection() {
    val context = LocalContext.current
    var style by remember { mutableStateOf(Citations.defaultStyle(context)) }
    SectionBox("Citations") {
        Text("Default style for “Copy as citation” and the citation in exported notes", style = MaterialTheme.typography.bodySmall, color = SectionGrey)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CitationStyle.entries.forEach { s ->
                InkChip(s.label, style == s, { style = s; Citations.setDefaultStyle(context, s) })
            }
        }
    }
}

// ----------------------------------------------------------------- E-ink

object EinkPrefs {
    private const val PREFS = "eink_prefs"
    fun refreshEvery(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("refreshEvery", 10)
    fun setRefreshEvery(context: Context, pages: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("refreshEvery", pages).apply()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EinkSettingsSection() {
    val context = LocalContext.current
    var every by remember { mutableIntStateOf(EinkPrefs.refreshEvery(context)) }
    SectionBox("E-ink screen") {
        Text(
            "Clean refresh: briefly flashes the page black to clear ghosting left by earlier pages. " +
                "Choose how often it happens while you turn pages.",
            style = MaterialTheme.typography.bodySmall, color = SectionGrey
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 1 to "Every page", 5 to "Every 5", 10 to "Every 10", 20 to "Every 20").forEach { (n, label) ->
                InkChip(label, every == n, { every = n; EinkPrefs.setRefreshEvery(context, n) })
            }
        }
    }
}

/**
 * Full-screen black flash that forces a clean e-ink refresh every N page changes, and whenever
 * [forceKey] changes (e.g. after Glossae cards flip). Draws nothing the rest of the time.
 */
@Composable
fun EinkRefreshOverlay(pageKey: Any?, forceKey: Any? = null) {
    val context = LocalContext.current
    var turns by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    var lastPage by remember { mutableStateOf(pageKey) }
    LaunchedEffect(pageKey) {
        if (pageKey == lastPage) return@LaunchedEffect
        lastPage = pageKey
        val every = EinkPrefs.refreshEvery(context)
        if (every <= 0) return@LaunchedEffect
        turns++
        if (turns >= every) {
            turns = 0
            delay(120) // let the new page draw first
            flash = true
            delay(110)
            flash = false
        }
    }
    var lastForce by remember { mutableStateOf(forceKey) }
    LaunchedEffect(forceKey) {
        if (forceKey == null || forceKey == lastForce) return@LaunchedEffect
        lastForce = forceKey
        if (EinkPrefs.refreshEvery(context) <= 0) return@LaunchedEffect
        delay(300)
        flash = true
        delay(110)
        flash = false
    }
    if (flash) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}
