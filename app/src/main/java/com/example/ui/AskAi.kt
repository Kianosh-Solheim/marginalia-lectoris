package com.example.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity

/** AI chat apps the passage can be sent to (only installed ones are offered). */
object AskAiApps {
    data class App(val label: String, val packageName: String)

    val known = listOf(
        App("ChatGPT", "com.openai.chatgpt"),
        App("Claude", "com.anthropic.claude"),
        App("Gemini", "com.google.android.apps.bard"),
        App("Perplexity", "ai.perplexity.app.android"),
        App("Copilot", "com.microsoft.copilot"),
        App("Le Chat", "ai.mistral.chat"),
        App("DeepSeek", "com.deepseek.chat")
    )

    private const val PREFS = "ask_ai_prefs"

    fun installed(context: Context): List<App> = known.filter { app ->
        runCatching { context.packageManager.getLaunchIntentForPackage(app.packageName) != null }.getOrDefault(false)
    }

    fun remembered(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("package", "").orEmpty()

    fun remember(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("package", packageName).apply()
    }

    /** The text sent to the AI app: where the passage is from, the passage, and the question. */
    fun prompt(document: DocumentEntity?, locus: String, quote: String, question: String): String = buildString {
        val source = listOfNotNull(
            document?.title?.takeIf { it.isNotBlank() }?.let { "“$it”" },
            document?.let { com.example.reader.EditedVolumes.displayAuthor(it) }?.takeIf { it.isNotBlank() }?.let { "by $it" }
        ).joinToString(" ")
        if (source.isNotBlank() || locus.isNotBlank()) {
            append("From ").append(source.ifBlank { "a text I'm reading" })
            if (locus.isNotBlank()) append(" (").append(locus).append(")")
            append(":\n\n")
        }
        append("“").append(quote.trim()).append("”\n\n")
        if (question.isNotBlank()) append(question.trim()) else append("")
    }.trimEnd() + "\n"

    /** Opens [packageName] (or the share sheet when blank or not installed) with the text. */
    fun send(context: Context, packageName: String, text: String) {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (packageName.isNotBlank()) {
            val direct = Intent(base).setPackage(packageName)
            val ok = runCatching { context.startActivity(direct); true }.getOrDefault(false)
            if (ok) return
            Toast.makeText(context, "That app couldn't take the passage; choose another.", Toast.LENGTH_SHORT).show()
        }
        context.startActivity(Intent.createChooser(base, "Ask about this passage").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * "Ask AI": sends the selected passage (with the book, author and page) to an AI chat app of the
 * reader's choosing, optionally with a question typed here. The chosen app can be remembered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAiDialog(quote: String, locus: String, document: DocumentEntity?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { AskAiApps.installed(context) }
    var chosen by remember {
        mutableStateOf(AskAiApps.remembered(context).takeIf { p -> p.isBlank() || apps.any { it.packageName == p } } ?: (apps.firstOrNull()?.packageName ?: ""))
    }
    var rememberChoice by remember { mutableStateOf(AskAiApps.remembered(context).isNotBlank()) }
    var question by remember { mutableStateOf("") }
    val grey = Color(0xFF3F3F46)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, focusedLabelColor = Color.Black,
        cursorColor = Color.Black, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black
    )
    val appLabel = apps.firstOrNull { it.packageName == chosen }?.label ?: "another app"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Ask AI about this passage", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "“${quote.trim().take(220)}${if (quote.length > 220) "…" else ""}”",
                    fontFamily = FontFamily.Serif, fontSize = 13.sp, color = grey,
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(2.dp)).padding(8.dp)
                )
                OutlinedTextField(
                    value = question, onValueChange = { question = it },
                    label = { Text("Your question (optional)") },
                    placeholder = { Text("e.g. Explain this argument in plain words") },
                    minLines = 2, maxLines = 5, colors = fieldColors, shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Explain this", "Summarise", "What is the argument?", "Counter-arguments?", "Forklar på norsk").forEach { q ->
                        InkChip(q, question == q, { question = if (question == q) "" else q })
                    }
                }
                Text("Open in", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = grey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    apps.forEach { a -> InkChip(a.label, chosen == a.packageName, { chosen = a.packageName }) }
                    InkChip("Other app…", chosen.isBlank(), { chosen = "" })
                }
                if (apps.isEmpty()) {
                    Text("No AI app found on the tablet (ChatGPT, Claude, Gemini, Perplexity …). “Other app…” lets you pick any app.", fontSize = 12.sp, color = grey)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color.Black, checkmarkColor = Color.White))
                    Text("Remember this app", fontSize = 13.sp, color = Color.Black)
                }
                Text("The passage is sent with the book, author and page, so you can keep asking in the app.", fontSize = 11.sp, color = grey)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AskAiApps.remember(context, if (rememberChoice) chosen else "")
                AskAiApps.send(context, chosen, AskAiApps.prompt(document, locus, quote, question))
                onDismiss()
            }) { Text(if (chosen.isBlank()) "Choose app…" else "Open in $appLabel", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = grey) } }
    )
}
