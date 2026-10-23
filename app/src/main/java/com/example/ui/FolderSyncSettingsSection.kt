package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.FolderSync
import java.text.DateFormat
import java.util.Date

/** Settings: the book folders are the library — what is kept in them, and when they were last checked. */
@Composable
fun FolderSyncSettingsSection() {
    val grey = Color(0xFF3F3F46)
    val last by FolderSync.lastSync.collectAsState()
    val imported by FolderSync.importedFromFiles.collectAsState()
    Box(Modifier.fillMaxWidth().border(1.5.dp, Color.Black, RoundedCornerShape(4.dp)).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Book folders", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), fontWeight = FontWeight.Bold, color = Color.Black)
            Text(
                "Each book's folder holds its overview note (title, authors, tags, bibliographic details and abstract), " +
                    "one note per highlight, and a .ris and .bib record. Changes in the app are written to these files at once. " +
                    "Edit the files in any text editor or Obsidian and the app picks the changes up when it opens, comes to the " +
                    "front, or within a minute. If the same detail changed in both places, the newest change wins.",
                style = MaterialTheme.typography.bodySmall, color = grey
            )
            Text(
                "A section headed “# My Notes” at the end of an overview note is yours: the app keeps it as you wrote it.",
                style = MaterialTheme.typography.bodySmall, color = grey
            )
            Text(
                if (last > 0) "Last checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last))}" +
                    (if (imported > 0) " · $imported change${if (imported == 1) "" else "s"} read from files since the app started" else "")
                else "Not checked yet",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black
            )
            InkButton("Check the folders now", onClick = { FolderSync.scanSoon() })
        }
    }
}
