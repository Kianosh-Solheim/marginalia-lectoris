package com.example.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppDatabase
import com.example.reader.NoteSuggestionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/** Every tag used so far, most used first: on books/articles and on notes. */
object TagIndex {
    data class Tags(val documents: List<String>, val notes: List<String>)

    suspend fun load(context: Context): Tags = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        fun rank(all: List<String>): List<String> {
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            all.flatMap { NoteSuggestionEngine.parseTagsList(it) }.forEach { t ->
                val k = t.lowercase(Locale.ROOT)
                counts[k] = (counts[k]?.first ?: t) to ((counts[k]?.second ?: 0) + 1)
            }
            return counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase(Locale.ROOT) }).map { it.first }
        }
        Tags(
            documents = rank(db.documentDao().getAllDocumentsSnapshot().map { it.tags }),
            notes = rank(db.highlightDao().getAllHighlights().first().map { it.tags })
        )
    }
}

/**
 * “Your tags”: the tags used before, as chips to tap. [selected] are left out; [typed] (what is
 * being typed in the tag field) narrows the list. [forNotes] puts note tags first, else book tags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviousTagsPicker(selected: List<String>, typed: String = "", forNotes: Boolean = false, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var tags by remember { mutableStateOf<TagIndex.Tags?>(null) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tags = runCatching { TagIndex.load(context) }.getOrNull() }
    val t = tags ?: return
    val ordered = (if (forNotes) t.notes + t.documents else t.documents + t.notes).distinctBy { it.lowercase(Locale.ROOT) }
    val chosen = selected.map { it.lowercase(Locale.ROOT) }.toSet()
    val partial = typed.substringAfterLast(',').trim().removePrefix("#").lowercase(Locale.ROOT)
    val list = ordered.filter { it.lowercase(Locale.ROOT) !in chosen && (partial.isBlank() || it.lowercase(Locale.ROOT).contains(partial)) }
    if (list.isEmpty()) return
    val shown = if (showAll || partial.isNotBlank()) list.take(80) else list.take(16)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Your tags (tap to add)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF52525B))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { tag -> InkChip("#$tag", false, { onPick(tag) }) }
            if (!showAll && partial.isBlank() && list.size > shown.size) {
                TextButton(onClick = { showAll = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("All ${list.size}…", fontSize = 12.sp, color = Color.Black)
                }
            }
        }
    }
}
