package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.*

private val SubtleInk = Color(0xFF3F3F46)

@Composable
private fun inkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    focusedBorderColor = Color.Black,
    unfocusedBorderColor = Color.Black,
    focusedLabelColor = Color.Black,
    cursorColor = Color.Black
)

/**
 * Settings → AI. Provider, model, title language and prompts are saved as soon as they change (they
 * don't depend on the screen's main Save button). The API key is saved only with "Save key", stored
 * encrypted on this device and excluded from backups.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsSection() {
    val context = LocalContext.current
    val prefs = remember { AiPreferences(context) }
    var settings by remember { mutableStateOf(prefs.load()) }
    var key by remember(settings.provider) { mutableStateOf("") }
    var hasKey by remember(settings.provider) { mutableStateOf(prefs.hasKey(settings.provider)) }
    var status by remember { mutableStateOf("") }
    var showPrompts by remember { mutableStateOf(false) }

    // Auto-save everything except the key.
    LaunchedEffect(settings) {
        runCatching { prefs.save(settings) }.onFailure { status = "Could not save AI settings." }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "AI",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                "Used only when you tap an AI button: titles, summaries, tags and word explanations. " +
                    "The highlight and your note are sent to the provider you choose, using your own API key.",
                style = MaterialTheme.typography.bodySmall,
                color = SubtleInk
            )

            // Provider
            Text("Provider", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProvider.entries.forEach { provider ->
                    InkChip(provider.label, settings.provider == provider, {
                        settings = settings.copy(provider = provider, model = prefs.model(provider))
                        status = ""
                    })
                }
            }

            // API key
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (hasKey) "${settings.provider.label} key saved · paste to replace" else "${settings.provider.label} API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(4.dp),
                colors = inkFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InkButton(
                    label = "Save key",
                    icon = Icons.Default.Key,
                    filled = true,
                    enabled = key.isNotBlank(),
                    onClick = {
                        runCatching { prefs.saveKey(settings.provider, key) }
                            .onSuccess { key = ""; hasKey = true; status = "${settings.provider.label} key saved on this tablet." }
                            .onFailure { status = "Could not save the key. Please try again." }
                    }
                )
                if (hasKey) {
                    InkButton(
                        label = "Remove key",
                        onClick = {
                            runCatching { prefs.saveKey(settings.provider, "") }
                                .onSuccess { hasKey = false; status = "Key removed." }
                                .onFailure { status = "Could not remove the key." }
                        }
                    )
                }
            }

            // Model / endpoint
            OutlinedTextField(
                value = settings.model,
                onValueChange = { settings = settings.copy(model = it) },
                label = { Text("Model") },
                supportingText = {
                    Text(
                        if (settings.provider.defaultModel.isNotBlank()) "Default: ${settings.provider.defaultModel}" else "The model ID your service expects",
                        color = SubtleInk
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(4.dp),
                colors = inkFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (settings.provider == AiProvider.CUSTOM) {
                OutlinedTextField(
                    value = settings.endpoint,
                    onValueChange = { settings = settings.copy(endpoint = it) },
                    label = { Text("HTTPS chat completions URL") },
                    supportingText = { Text("Full endpoint, including /v1/chat/completions", color = SubtleInk) },
                    singleLine = true,
                    shape = RoundedCornerShape(4.dp),
                    colors = inkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Title language
            Text("Title language", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("BOOK" to "Follow book", "ENGLISH" to "English", "NORWEGIAN" to "Norsk").forEach { (value, label) ->
                    InkChip(label, settings.titleLanguage == value, { settings = settings.copy(titleLanguage = value) })
                }
            }

            // Dictionary AI
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Explain words in context", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
                    Text("Adds an AI option to the Dictionary that explains the word as used in the passage", style = MaterialTheme.typography.bodySmall, color = SubtleInk)
                }
                Switch(
                    checked = settings.dictionaryAi,
                    onCheckedChange = { settings = settings.copy(dictionaryAi = it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.Black,
                        uncheckedThumbColor = Color.Black,
                        uncheckedTrackColor = Color(0xFFE4E4E7)
                    )
                )
            }

            // Prompts (collapsed by default)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPrompts = !showPrompts }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edit prompts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black, modifier = Modifier.weight(1f))
                Icon(if (showPrompts) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = Color.Black)
            }
            if (showPrompts) {
                listOf(
                    Triple("Title prompt", settings.titlePrompt) { v: String -> settings = settings.copy(titlePrompt = v) },
                    Triple("Summary prompt", settings.summaryPrompt) { v: String -> settings = settings.copy(summaryPrompt = v) },
                    Triple("Tags prompt", settings.tagsPrompt) { v: String -> settings = settings.copy(tagsPrompt = v) },
                    Triple("Word explanation prompt", settings.dictionaryPrompt) { v: String -> settings = settings.copy(dictionaryPrompt = v) }
                ).forEach { (label, value, update) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = update,
                        label = { Text(label) },
                        minLines = 2,
                        shape = RoundedCornerShape(4.dp),
                        colors = inkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                InkButton(
                    label = "Reset prompts to defaults",
                    onClick = {
                        val d = AiSettings()
                        settings = settings.copy(
                            titlePrompt = d.titlePrompt,
                            summaryPrompt = d.summaryPrompt,
                            tagsPrompt = d.tagsPrompt,
                            dictionaryPrompt = d.dictionaryPrompt
                        )
                    }
                )
            }

            if (status.isNotBlank()) {
                Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
            }
        }
    }
}
