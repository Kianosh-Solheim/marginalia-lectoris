package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.reader.Lexicon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** The lexicon is open (from the drawer). */
val lexiconOpen = mutableStateOf(false)

private val LGrey = Color(0xFF52525B)

@Composable
fun LexiconHost() {
    if (!lexiconOpen.value) return
    Dialog(onDismissRequest = { lexiconOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Surface(Modifier.fillMaxSize(), color = Color.White) { LexiconScreen(onClose = { lexiconOpen.value = false }) }
    }
}

private val inkFieldColors @Composable get() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black,
    focusedLabelColor = Color.Black, cursorColor = Color.Black,
    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
)

/** The words, searchable; tap one to see or change it. */
@Composable
fun LexiconScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val version by Lexicon.version.collectAsState()
    var words by remember { mutableStateOf<List<Lexicon.Word>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var undefinedOnly by remember { mutableStateOf(false) }
    var newest by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Lexicon.Word?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(version) { words = withContext(Dispatchers.IO) { Lexicon.all(context) } }
    BackHandler { onClose() }

    val shown = remember(words, query, undefinedOnly, newest) {
        val q = query.trim().lowercase(Locale.ROOT)
        words.filter { w -> (!undefinedOnly || w.definition.isBlank()) &&
            (q.isEmpty() || listOf(w.word, w.definition, w.tags, w.source, w.note).any { it.lowercase(Locale.ROOT).contains(q) }) }
            .let { l -> if (newest) l.sortedByDescending { it.added } else l }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lexicon", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.Black)
            Text("  ${words.size} word${if (words.size == 1) "" else "s"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = LGrey)
            Spacer(Modifier.weight(1f))
            InkButton("Add word", icon = Icons.Default.Add, filled = true, onClick = { adding = true })
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search words, definitions, tags, sources") },
            shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkChip("All", !undefinedOnly, { undefinedOnly = false })
            InkChip("Without a definition (${words.count { it.definition.isBlank() }})", undefinedOnly, { undefinedOnly = true })
            Spacer(Modifier.width(12.dp))
            InkChip("A–Z", !newest, { newest = false })
            InkChip("Newest first", newest, { newest = true })
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
        if (shown.isEmpty()) {
            Text(
                if (words.isEmpty()) "No words yet. Select a word while reading, tap Define (or Dictionary in a note), and choose “Save to lexicon”." else "Nothing matches.",
                color = LGrey, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { w ->
                Column(
                    Modifier.fillMaxWidth().clickable { editing = w }.padding(vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(w.word, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Color.Black)
                        if (w.partOfSpeech.isNotBlank()) Text("  ${w.partOfSpeech}", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, color = LGrey)
                    }
                    Text(
                        w.definition.ifBlank { "No definition yet — tap to add one." },
                        fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 21.sp,
                        color = if (w.definition.isBlank()) LGrey else Color.Black,
                        fontStyle = if (w.definition.isBlank()) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOf(w.source.let { if (it.isNotBlank() && w.page.isNotBlank()) "$it, p. ${w.page}" else it }, w.tags.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ") { "#$it" })
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(thickness = 1.dp, color = Color(0xFFD4D4D8))
            }
        }
    }

    if (adding) LexiconEditDialog(null, onDone = { adding = false })
    editing?.let { w -> LexiconEditDialog(w, onDone = { editing = null }) }
}

/** One word: see, change, define with AI, or delete. [word] null: a new word. */
@Composable
fun LexiconEditDialog(word: Lexicon.Word?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(word?.word.orEmpty()) }
    var pos by remember { mutableStateOf(word?.partOfSpeech.orEmpty()) }
    var definition by remember { mutableStateOf(word?.definition.orEmpty()) }
    var example by remember { mutableStateOf(word?.example.orEmpty()) }
    var note by remember { mutableStateOf(word?.note.orEmpty()) }
    var tags by remember { mutableStateOf(word?.tags.orEmpty()) }
    var insteadOf by remember { mutableStateOf(word?.insteadOf.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        busy = true; message = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (word == null) {
                        val (_, made) = Lexicon.add(context, Lexicon.Word(id = "", word = text, definition = definition.trim(), partOfSpeech = pos.trim(),
                            example = example.trim(), note = note.trim(), tags = tags.trim(), insteadOf = insteadOf.trim()))
                        check(made) { "“${text.trim()}” is already in the lexicon." }
                    } else {
                        Lexicon.update(context, word.id, JSONObject().put("word", text).put("partOfSpeech", pos).put("definition", definition)
                            .put("example", example).put("note", note).put("tags", tags).put("insteadOf", insteadOf))
                    }
                }
                onDone()
            } catch (e: Exception) { message = e.message ?: "Could not save it." } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text(if (word == null) "New word" else word.word, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, label = { Text("Word") }, singleLine = true, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.weight(2f))
                    OutlinedTextField(pos, { pos = it }, label = { Text("Part of speech") }, singleLine = true, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(definition, { definition = it }, label = { Text("Definition") }, minLines = 3, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.fillMaxWidth())
                InkButton(
                    label = if (busy) "Asking the AI…" else "Define with AI",
                    icon = Icons.Default.AutoAwesome,
                    enabled = !busy && text.isNotBlank(),
                    onClick = {
                        busy = true; message = ""
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) { Lexicon.define(context, text, example) }
                                definition = r.definition; if (pos.isBlank()) pos = r.partOfSpeech; if (insteadOf.isBlank()) insteadOf = r.insteadOf
                                message = "The AI's definition is filled in. Change it if you like, then save."
                            } catch (e: Exception) { message = e.message ?: "The AI could not define it." } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(insteadOf, { insteadOf = it }, label = { Text("Use instead of (everyday words, separated by commas)") }, singleLine = true, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(example, { example = it }, label = { Text("Example (the sentence it was found in)") }, minLines = 2, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Your note") }, minLines = 2, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags, separated by commas") }, singleLine = true, shape = RoundedCornerShape(4.dp), colors = inkFieldColors, modifier = Modifier.fillMaxWidth())
                if (word != null && word.source.isNotBlank()) {
                    Text("From: ${word.source}${if (word.page.isNotBlank()) ", p. ${word.page}" else ""}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LGrey)
                }
                if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = Color.Black)
                if (word != null) {
                    if (!confirmDelete) TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete this word", color = Color(0xFFB91C1C)) }
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Delete “${word.word}” for good?", fontSize = 13.sp, color = Color.Black, modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = false }) { Text("No", color = Color.Black) }
                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { Lexicon.delete(context, word.id) }; onDone() }
                        }) { Text("Delete", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = !busy && text.isNotBlank()) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel", color = Color.Black) } }
    )
}
