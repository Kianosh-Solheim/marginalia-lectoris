package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppDatabase
import com.example.reader.PageNumbering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SectionRow(from: String, start: String, roman: Boolean) {
    var from by mutableStateOf(from)
    var start by mutableStateOf(start)
    var roman by mutableStateOf(roman)
}

/**
 * The printed page numbers of a PDF, laid down by hand: "PDF page 4 is printed page 183", and for
 * a book further sections (roman numerals first, then starting again at 1). The notes of the
 * document are renumbered to match, after asking.
 */
@Composable
fun PageNumbersDialog(documentId: Long, currentPage: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember {
        mutableStateListOf<SectionRow>().apply {
            val saved = PageNumbering.sections(context, documentId)
            if (saved.isEmpty()) add(SectionRow(currentPage.coerceAtLeast(1).toString(), "", false))
            else saved.forEach { add(SectionRow(it.from.toString(), it.start.toString(), it.roman)) }
        }
    }
    var pending by remember { mutableStateOf<List<PageNumbering.Change>?>(null) }
    var pendingSections by remember { mutableStateOf<List<PageNumbering.Section>>(emptyList()) }
    var problem by remember { mutableStateOf("") }

    fun parsed(): List<PageNumbering.Section>? {
        val out = rows.filter { it.from.isNotBlank() || it.start.isNotBlank() }.map {
            val f = it.from.trim().toIntOrNull(); val s = it.start.trim().toIntOrNull()
            if (f == null || s == null || f < 1 || s < 1) return null
            PageNumbering.Section(f, s, it.roman)
        }
        return out
    }
    fun finish(secs: List<PageNumbering.Section>, renumber: List<PageNumbering.Change>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                PageNumbering.setSections(context, documentId, secs)
                val dao = AppDatabase.getInstance(context).highlightDao()
                renumber.forEach { runCatching { dao.updateHighlight(it.note.copy(printedPageLabel = it.newLabel)) } }
            }
            onDismiss()
        }
    }

    val ask = pending
    if (ask != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Renumber ${ask.size} note${if (ask.size == 1) "" else "s"}?", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("These notes get a new page number. Notes whose page you typed in yourself are left alone.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    ask.take(40).forEach { c ->
                        val old = c.note.printedPageLabel.substringBefore("|PDF:").ifBlank { "—" }
                        Text("${c.note.title.take(40)}:  $old  →  ${c.newLabel.substringBefore("|PDF:").ifBlank { "—" }}", fontSize = 12.sp)
                    }
                    if (ask.size > 40) Text("… and ${ask.size - 40} more", fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { finish(pendingSections, ask) }) { Text("Renumber", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { finish(pendingSections, emptyList()) }) { Text("Leave the notes", color = Color.Black) } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page numbers", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Say which printed page a PDF page is; the rest follows by counting. For a book, add a section where the numbering starts again (roman numerals first, then 1). Leave it empty to read the numbers off the pages automatically.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                rows.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = r.from, onValueChange = { v -> r.from = v.filter { it.isDigit() } }, label = { Text("PDF page", fontSize = 11.sp) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                        OutlinedTextField(value = r.start, onValueChange = { v -> r.start = v.filter { it.isDigit() } }, label = { Text("is page", fontSize = 11.sp) },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                        Checkbox(checked = r.roman, onCheckedChange = { r.roman = it })
                        Text("i, ii, iii", fontSize = 12.sp)
                        if (rows.size > 1) TextButton(onClick = { rows.removeAt(i) }) { Text("×", color = Color.Black) }
                    }
                }
                TextButton(onClick = { rows.add(SectionRow("", "1", false)) }) { Text("+ Add a section", color = Color.Black) }
                if (problem.isNotBlank()) Text(problem, color = Color(0xFFB91C1C), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val secs = parsed()
                if (secs == null) { problem = "Each section needs a PDF page and a page number (whole numbers; roman numerals are entered as their number, iv = 4)."; return@TextButton }
                scope.launch {
                    val changes = withContext(Dispatchers.IO) {
                        PageNumbering.changes(context, documentId, secs, AppDatabase.getInstance(context).highlightDao().getHighlightsListForDocument(documentId))
                    }
                    if (changes.isEmpty()) finish(secs, emptyList()) else { pendingSections = secs; pending = changes }
                }
            }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { finish(emptyList(), emptyList()) }) { Text("Automatic", color = Color.Black) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Black) }
            }
        }
    )
}
