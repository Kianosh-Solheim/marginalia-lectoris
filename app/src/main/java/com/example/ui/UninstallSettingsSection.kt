package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's own uninstall dialog cannot ask the app anything, so the choice lives here:
 * remove only the app and keep the library folders, or delete the library too. Both end in
 * the system uninstall screen.
 */
@Composable
fun UninstallSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grey = Color(0xFF3F3F46)
    var step by remember { mutableStateOf(0) } // 0 closed, 1 choose, 2 confirm delete-all
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var root by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        root = withContext(Dispatchers.IO) {
            val s = AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot() ?: SettingsEntity()
            StorageManager.getRootDirectory(context, s.customStoragePath, s.storageLocationType)
        }
    }
    val inAppStorage = root?.let { Uninstall.isInsideAppStorage(context, it) } ?: true

    Box(Modifier.fillMaxWidth().border(1.5.dp, Color.Black, RoundedCornerShape(4.dp)).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Uninstall", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), fontWeight = FontWeight.Bold, color = Color.Black)
            Text(
                "Remove the app and choose whether your library — books, overview notes, highlight notes, covers, " +
                    ".ris and .bib files — stays on the tablet or is deleted too.",
                style = MaterialTheme.typography.bodySmall, color = grey
            )
            root?.let { Text("Library folder: ${it.absolutePath}", style = MaterialTheme.typography.bodySmall, color = grey) }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = Color.Black)
            InkButton("Uninstall Marginalia…", enabled = !busy, onClick = { step = 1 })
        }
    }

    if (step == 1) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            containerColor = Color.White,
            shape = RoundedCornerShape(4.dp),
            title = { Text("Uninstall Marginalia", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Choice(
                        "Remove the app only",
                        if (inAppStorage)
                            "Your library is kept. It lives in the app's own storage, so in the next screen tick " +
                                "“Keep app data”" + (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) " (not offered on this Android version — back up first)" else "") +
                                ". Reinstalling brings everything back."
                        else "Your library folder stays where it is. Reinstall and point the app at it to carry on."
                    ) { step = 0; Uninstall.openSystemUninstall(context) }
                    Choice(
                        "Delete everything",
                        "Books, notes, covers, exports and settings are deleted, then the app is removed. Backups you saved elsewhere are kept."
                    ) { step = 2 }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel", color = grey) } }
        )
    }

    if (step == 2) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            containerColor = Color.White,
            shape = RoundedCornerShape(4.dp),
            title = { Text("Delete the whole library?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text("This cannot be undone. Every book file and note in the library folder is deleted first; then Android asks to remove the app.") },
            confirmButton = {
                TextButton(onClick = {
                    step = 0; busy = true; status = "Deleting the library…"
                    scope.launch {
                        val r = root
                        runCatching { withContext(Dispatchers.IO) { Uninstall.deleteEverything(context, r) } }
                            .onSuccess { status = "Library deleted."; Uninstall.openSystemUninstall(context) }
                            .onFailure { status = "Could not delete everything: ${it.message}" }
                        busy = false
                    }
                }) { Text("Delete and uninstall", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel", color = grey) } }
        )
    }
}

@Composable
private fun Choice(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Color.Black)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Color(0xFF3F3F46))
    }
}

object Uninstall {
    fun isInsideAppStorage(context: Context, dir: File): Boolean {
        val path = dir.canonicalPath
        val bases = listOfNotNull(context.getExternalFilesDir(null)?.parentFile, context.filesDir.parentFile)
        return bases.any { path.startsWith(it.canonicalPath) }
    }

    fun openSystemUninstall(context: Context) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Deletes the library. A folder the user chose keeps everything that is not the app's
     * (only Books, Articles and Exports go); the app's own folders are emptied entirely.
     */
    fun deleteEverything(context: Context, root: File?) {
        if (root != null) {
            if (isInsideAppStorage(context, root)) root.deleteRecursively()
            else {
                listOf("Books", "Articles", "Exports").forEach { File(root, it).deleteRecursively() }
                if (root.listFiles().isNullOrEmpty()) root.delete()
            }
        }
        context.getExternalFilesDir(null)?.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Databases and preferences go with the app itself; keeping them out of the way here
        // lets the uninstall screen still work if it is cancelled.
    }
}
