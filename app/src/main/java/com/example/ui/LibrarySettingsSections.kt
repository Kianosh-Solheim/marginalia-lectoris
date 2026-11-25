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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.reader.BackupJobs
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

@Composable
private fun inkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Black, unfocusedBorderColor = Color(0xFFD4D4D8), cursorColor = Color.Black,
    focusedLabelColor = Color.Black
)

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(), colors = inkFieldColors()
    )
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Color.Black, uncheckedColor = SectionGrey))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Black else Color(0xFF9CA3AF))
    }
}

/** Asks how to make the backup: optional password, optional API keys. */
@Composable
private fun BackupOptionsDialog(onDismiss: () -> Unit, onContinue: (BackupManager.Options) -> Unit) {
    var encrypt by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var keys by remember { mutableStateOf(false) }
    val problem = when {
        !encrypt -> null
        pw.length < 8 -> "Use at least 8 characters."
        pw != pw2 -> "The passwords don't match."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Back up", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(encrypt, { encrypt = it; if (!it) keys = false }, "Encrypt with a password")
                if (encrypt) {
                    PasswordField(pw, { pw = it }, "Password")
                    PasswordField(pw2, { pw2 = it }, "Repeat password")
                    Text("The password is not saved anywhere. If you forget it, the backup cannot be opened.",
                        style = MaterialTheme.typography.bodySmall, color = SectionGrey)
                    if (pw.isNotEmpty() || pw2.isNotEmpty()) problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB91C1C)) }
                }
                CheckRow(keys, { keys = it }, "Include API keys", enabled = encrypt)
                if (!encrypt) Text("API keys can only go into an encrypted backup.", style = MaterialTheme.typography.bodySmall, color = SectionGrey)
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = {
                onContinue(BackupManager.Options(if (encrypt) pw.toCharArray() else null, includeKeys = encrypt && keys))
            }) { Text("Choose where to save…", color = if (problem == null) Color.Black else Color(0xFF9CA3AF), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SectionGrey) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by BackupJobs.state.collectAsState()
    var note by remember { mutableStateOf("") }
    var autoTarget by remember { mutableStateOf(BackupManager.autoTarget(context)) }
    var lastBackup by remember { mutableStateOf(BackupManager.lastBackupTime(context)) }
    var askOptions by remember { mutableStateOf(false) }
    var pendingOptions by remember { mutableStateOf<BackupManager.Options?>(null) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreEncrypted by remember { mutableStateOf(false) }
    var restorePw by remember { mutableStateOf("") }
    var restoreKeys by remember { mutableStateOf(true) }
    val busy = job.running || job.restarting
    LaunchedEffect(job.finishedAt) { lastBackup = BackupManager.lastBackupTime(context) }

    val backupNow = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val options = pendingOptions ?: BackupManager.Options()
        pendingOptions = null
        if (uri == null) { options.password?.fill('\u0000'); return@rememberLauncherForActivityResult }
        note = ""
        if (!BackupJobs.backup(context, uri, options)) note = "A backup or restore is already running."
    }
    val chooseAuto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        BackupManager.enableAuto(context, uri)
        autoTarget = uri
        note = "Automatic daily backup is on. Making the first backup now…"
        BackupJobs.backup(context, uri, BackupManager.Options())
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            restoreEncrypted = runCatching { BackupManager.isEncrypted(context, uri) }.getOrDefault(false)
            restorePw = ""; restoreKeys = true
            confirmRestore = uri
        }
    }

    if (askOptions) BackupOptionsDialog(onDismiss = { askOptions = false }) { options ->
        askOptions = false
        pendingOptions = options
        backupNow.launch(BackupManager.suggestedFileName())
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(4.dp),
            title = { Text("Restore this backup?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your current library, notes, audiobooks, recordings and settings will be replaced by the backup. " +
                            "The backup is checked first; if anything goes wrong, your library is left as it was. " +
                            "Files in the library folder are written back there, so Syncthing will pass them on to your other devices. " +
                            "The app restarts when it's done."
                    )
                    if (restoreEncrypted) {
                        PasswordField(restorePw, { restorePw = it }, "Backup password")
                        CheckRow(restoreKeys, { restoreKeys = it }, "Restore API keys, if the backup has them")
                    }
                }
            },
            confirmButton = {
                val ready = !restoreEncrypted || restorePw.isNotEmpty()
                TextButton(enabled = ready, onClick = {
                    confirmRestore = null
                    note = ""
                    val pw = if (restoreEncrypted) restorePw.toCharArray() else null
                    restorePw = ""
                    if (!BackupJobs.restore(context, uri, pw, restoreKeys)) note = "A backup or restore is already running."
                }) { Text("Replace and restart", color = if (ready) Color.Black else Color(0xFF9CA3AF), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null; restorePw = "" }) { Text("Cancel", color = SectionGrey) } }
        )
    }

    SectionBox("Backup") {
        Text(
            "Backs up everything as one .zip: books and articles, highlights, Glossae and notes, audiobooks with their sync maps, " +
                "recordings and transcripts, presentations, courses, sketches, bibliographic details and settings. " +
                "In the file picker, choose Google Drive, OneDrive, Dropbox or a folder on the tablet " +
                "(the cloud app must be installed on the tablet). It can be encrypted with a password, and only then include API keys.",
            style = MaterialTheme.typography.bodySmall, color = SectionGrey
        )
        if (lastBackup > 0) {
            val size = BackupManager.lastBackupBytes(context)
            Text(
                "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastBackup))}" +
                    (if (size > 0) " · ${formatBytes(size)}" else ""),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Back up now", icon = Icons.Default.CloudUpload, filled = true, enabled = !busy,
                onClick = { askOptions = true })
            InkButton("Restore from backup", icon = Icons.Default.Restore, enabled = !busy,
                onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) })
        }
        HorizontalDivider(color = Color(0xFFE4E4E7))
        Text("Automatic daily backup", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
        Text(
            if (autoTarget != null) "On. Once a day the backup file you chose is updated, but only if something changed. Automatic backups are not encrypted and leave out API keys."
            else "Choose a backup file once (for example in Google Drive or OneDrive); it is then updated once a day. Automatic backups are not encrypted.",
            style = MaterialTheme.typography.bodySmall, color = SectionGrey
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(if (autoTarget == null) "Turn on…" else "Change backup file…", enabled = !busy,
                onClick = { chooseAuto.launch("Marginalia-Lectoris-auto-backup.zip") })
            if (autoTarget != null) InkButton("Turn off", enabled = !busy, onClick = {
                BackupManager.disableAuto(context); autoTarget = null; note = "Automatic backup is off."
            })
        }
        if (job.running) {
            val p = job.progress
            if (p != null && p.total > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth(), color = Color.Black, trackColor = Color(0xFFE4E4E7))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.Black, trackColor = Color(0xFFE4E4E7))
        }
        val status = note.ifBlank { job.message }
        if (status.isNotBlank()) Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = if (job.error && note.isBlank()) Color(0xFFB91C1C) else Color.Black)
    }
}

/** Shown anywhere in the app when a restore is asked for from the web view. */
@Composable
fun WebRestoreRequestDialog() {
    val context = LocalContext.current
    val pending by BackupJobs.pendingWebRestore.collectAsState()
    val p = pending ?: return
    AlertDialog(
        onDismissRequest = {},
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Restore requested from the browser", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "“${p.name}” (${formatBytes(p.size)}${if (p.encrypted) ", encrypted" else ""}) was uploaded from the web view. " +
                    "Restoring it replaces your current library, notes and settings, and the app restarts. " +
                    "Only continue if you started this yourself."
            )
        },
        confirmButton = {
            TextButton(onClick = { BackupJobs.confirmWebRestore(context) }) {
                Text("Replace and restart", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { BackupJobs.declineWebRestore(context) }) { Text("Decline", color = SectionGrey) } }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
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
