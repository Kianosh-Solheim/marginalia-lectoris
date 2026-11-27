package com.example.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Ink
import androidx.compose.ui.unit.sp
import com.example.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val LocalBookPath = staticCompositionLocalOf { "" }

private val InkGrey = Color(0xFF3F3F46)
private val PaperShape = Ink.Shape

@Composable
fun rememberBookLanguage(path: String = LocalBookPath.current, sample: String = ""): String {
    val lang by produceState("", path) {
        value = withContext(Dispatchers.IO) {
            if (path.endsWith(".epub", true)) runCatching { EpubParser.detectEpubSpecification(File(path)).language }.getOrDefault("") else ""
        }
    }
    return lang.ifBlank { NoteAiAssistant.detectNorwegianOrEnglish(sample) }
}

// ----------------------------------------------------------------- editorial controls

/**
 * Square, black-ruled toggle matching the web view's .chip (selected = inverted).
 * [minHeight]: leave unset for the compact size; pass [Ink.TouchTarget] where a finger is expected.
 */
@Composable
fun InkChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = Dp.Unspecified
) {
    val c = com.example.ui.theme.LocalInkColors.current
    val fg = when {
        !enabled -> Ink.Soft
        selected -> c.paper
        else -> c.ink
    }
    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .border(Ink.InnerRule, if (enabled) c.ink else Ink.Soft, PaperShape)
            .background(if (selected && enabled) c.ink else c.paper, PaperShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1
        )
    }
}

/**
 * Action button in the web view's .btn style: square, 1 dp ink rule.
 * [filled] = the main action (black), [quiet] = no rule until pressed (web: .btn.quiet),
 * [danger] = red text for Delete and the like.
 * [minHeight]: leave unset for the compact size; pass [Ink.TouchTarget] where a finger is expected.
 */
@Composable
fun InkButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    danger: Boolean = false,
    minHeight: Dp = Dp.Unspecified
) {
    val c = com.example.ui.theme.LocalInkColors.current
    val fg = when {
        !enabled -> Color(0xFF9CA3AF)
        filled -> c.paper
        danger -> Ink.Danger
        else -> c.ink
    }
    val rule = when {
        quiet && !filled -> Color.Transparent
        !enabled -> Color(0xFF9CA3AF)
        else -> c.ink
    }
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .border(Ink.InnerRule, rule, PaperShape)
            .background(if (filled && enabled) c.ink else c.paper, PaperShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

// ----------------------------------------------------------------- dictionary

/** Small "Dictionary" button that opens [DictionaryLookupDialog]. */
@Composable
fun DictionaryButton(passage: String, language: String = rememberBookLanguage(sample = passage), contextPassage: String = passage) {
    var open by remember { mutableStateOf(false) }
    InkButton("Dictionary", icon = Icons.Default.MenuBook, enabled = passage.isNotBlank(), onClick = { open = true })
    if (open) DictionaryLookupDialog(passage, language, contextPassage) { open = false }
}

/** Shortens a selection to the word(s) worth looking up (most selections for a dictionary are 1–3 words). */
private fun lookupTerm(passage: String): String {
    val words = passage.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val core = if (words.size <= 3) words.joinToString(" ") else words.first()
    return core.trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryLookupDialog(passage: String, language: String, contextPassage: String = passage, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var word by remember { mutableStateOf(lookupTerm(passage)) }
    var norwegian by remember(language) { mutableStateOf(NoteAiAssistant.language(language, contextPassage) == "Norwegian") }
    var result by remember { mutableStateOf("") }
    var resultSource by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val aiEnabled = remember { AiPreferences(context).load().dictionaryAi }
    // The lexicon: the word can be kept, with the definition found here (if any) and the sentence it stands in.
    var resultOk by remember { mutableStateOf(false) }
    var lexState by remember { mutableStateOf("") }
    val readerDoc = LocalReaderDocument.current

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color.White,
        shape = Ink.Shape,
        title = {
            Text("Dictionary", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black)
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text("Close", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it; result = ""; resultOk = false; lexState = "" },
                    label = { Text("Word") },
                    singleLine = true,
                    shape = Ink.Shape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black,
                        focusedLabelColor = Color.Black, cursorColor = Color.Black,
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("OFFLINE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkGrey)
                InkButton(
                    label = if (busy && resultSource == "offline") "Looking up…" else "Tablet dictionary",
                    icon = Icons.Default.PhoneAndroid,
                    enabled = !busy && word.isNotBlank(),
                    onClick = {
                        busy = true; resultSource = "offline"; result = ""
                        scope.launch {
                            try {
                                OfflineDictionary.lookup(context, word.trim())
                                    .onSuccess { result = it; resultOk = true }
                                    .onFailure { result = it.message ?: "Offline lookup failed."; resultOk = false }
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("ONLINE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkGrey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkChip("Norsk · Ordbøkene", norwegian, { norwegian = true })
                    InkChip("English · Wiktionary", !norwegian, { norwegian = false })
                }
                InkButton(
                    label = if (norwegian) "Open in Ordbøkene" else "Open in Wiktionary",
                    icon = Icons.Default.OpenInNew,
                    enabled = word.isNotBlank(),
                    onClick = {
                        val term = word.trim()
                        val url = if (norwegian) "https://ordbokene.no/nob/bm,nn/${Uri.encode(term)}"
                        else "https://en.wiktionary.org/wiki/${Uri.encode(term.replace(' ', '_'))}#English"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            resultSource = "online"; result = "No browser is available to open the online dictionary."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (aiEnabled) {
                    Text("AI", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkGrey)
                    InkButton(
                        label = if (busy && resultSource == "ai") "Explaining…" else "Explain in this context",
                        icon = Icons.Default.AutoAwesome,
                        enabled = !busy && word.isNotBlank(),
                        onClick = {
                            busy = true; resultSource = "ai"; result = ""
                            scope.launch {
                                try {
                                    NoteAiAssistant.explain(context, word.trim(), contextPassage, if (norwegian) "nb" else "en")
                                        .onSuccess { result = it; resultOk = true }
                                        .onFailure { result = it.message ?: "AI explanation failed."; resultOk = false }
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (result.isNotBlank()) {
                    HorizontalDivider(thickness = 1.dp, color = Color.Black)
                    Text(result, fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 21.sp, color = Color.Black)
                }

                Text("LEXICON", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkGrey)
                InkButton(
                    label = when {
                        lexState.isNotBlank() -> lexState
                        resultOk && result.isNotBlank() -> "Save to lexicon, with this definition"
                        else -> "Save to lexicon (define it later)"
                    },
                    icon = Icons.Default.MenuBook,
                    enabled = word.isNotBlank() && lexState.isBlank(),
                    onClick = {
                        val w = word.trim()
                        scope.launch {
                            lexState = try {
                                val (_, made) = withContext(Dispatchers.IO) {
                                    Lexicon.add(context, Lexicon.Word(
                                        id = "", word = w,
                                        definition = if (resultOk) result.trim().take(2000) else "",
                                        example = Lexicon.sentenceWith(contextPassage, w),
                                        docId = readerDoc?.id ?: 0L, source = readerDoc?.title.orEmpty()))
                                }
                                if (made) "Saved to the lexicon" else "Already in the lexicon"
                            } catch (e: Exception) { e.message ?: "Could not save it." }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ----------------------------------------------------------------- dictation + AI summary

/**
 * Compact row placed directly above the reflections field: language switch, microphone, and the
 * AI summary button. Dictated text and accepted summaries are appended to the reflections.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteAssistance(quote: String, language: String, onAppend: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val append by rememberUpdatedState(onAppend)
    var norwegian by rememberSaveable(language) { mutableStateOf(NoteAiAssistant.language(language, quote) == "Norwegian") }
    var preferOffline by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(false) }

    if (showPad) {
        HandwritingPadDialog(
            norwegian = norwegian,
            onDismiss = { showPad = false },
            onResult = { text, embed ->
                append(listOf(text.trim(), embed).filter { it.isNotBlank() }.joinToString("\n\n"))
            }
        )
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        status = if (result.resultCode == Activity.RESULT_OK && text.isNotBlank()) {
            append(text); ""
        } else if (preferOffline) {
            "Nothing was recognised. If offline speech isn't installed for this language, turn off \"Offline\" and try again."
        } else {
            "Nothing was recognised."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InkChip("NO", norwegian, { norwegian = true })
            InkChip("EN", !norwegian, { norwegian = false })
            InkChip("Offline", preferOffline, { preferOffline = !preferOffline })
            InkButton(
                label = "Dictate",
                icon = Icons.Default.Mic,
                filled = true,
                onClick = {
                    status = ""
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (norwegian) "nb-NO" else "en-GB")
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, if (norwegian) "Dikter notatet ditt" else "Dictate your note")
                    try {
                        speech.launch(intent)
                    } catch (_: Exception) {
                        status = "No speech recognition service is installed on this tablet."
                    }
                }
            )
            InkButton(
                label = "Handwrite",
                icon = Icons.Default.Draw,
                onClick = { showPad = true }
            )
            InkButton(
                label = if (busy) "Summarising…" else "AI summary",
                icon = Icons.Default.AutoAwesome,
                enabled = !busy && quote.isNotBlank(),
                onClick = {
                    busy = true; status = ""
                    scope.launch {
                        try {
                            NoteAiAssistant.summarise(context, quote, if (norwegian) "nb" else "en")
                                .onSuccess { summary = it }
                                .onFailure { status = it.message ?: "Summary failed." }
                        } finally { busy = false }
                    }
                }
            )
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = InkGrey)
        }
        if (summary.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.Black), PaperShape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("AI SUMMARY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = InkGrey)
                Text(summary, fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 20.sp, color = Color.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Add to reflections", filled = true, onClick = { append(summary); summary = "" })
                    InkButton("Dismiss", onClick = { summary = "" })
                }
            }
        }
    }
}
