package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.People

/**
 * Several people (authors, editors, translators): one field per person, a × to remove one, ↑ to
 * move one up and "+ Add author" for another. [value] and [onValueChange] use the stored form
 * "Name One; Name Two".
 */
@Composable
fun PeopleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Author(s)",
    addLabel: String = "Add author",
    modifier: Modifier = Modifier
) {
    // The rows are kept while editing (an empty row stays until you type in it or leave it).
    var rows by remember { mutableStateOf(People.split(value).ifEmpty { listOf("") }) }
    LaunchedEffect(value) { if (People.join(rows) != People.join(People.split(value))) rows = People.split(value).ifEmpty { listOf("") } }
    var focusLast by remember { mutableStateOf(false) }
    val last = remember { FocusRequester() }
    fun update(next: List<String>) { rows = next; onValueChange(People.join(next)) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = Color(0xFFD4D4D8), focusedLabelColor = Color.Black, cursorColor = Color.Black)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF52525B))
        rows.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v ->
                        // Typing ; or pasting a list splits it into several people.
                        if (v.contains(';') || v.contains('\n')) update(rows.take(i) + People.split(v) + rows.drop(i + 1))
                        else update(rows.toMutableList().also { it[i] = v })
                    },
                    placeholder = { Text(if (i == 0) "Name Nameson" else "Another person") },
                    singleLine = true, colors = colors, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f).let { if (i == rows.lastIndex) it.focusRequester(last) else it }
                )
                if (i > 0) IconButton(onClick = { update(rows.toMutableList().also { val x = it.removeAt(i); it.add(i - 1, x) }) }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", tint = Color.Black)
                }
                IconButton(onClick = { update(rows.filterIndexed { k, _ -> k != i }.ifEmpty { listOf("") }) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Black)
                }
            }
        }
        TextButton(onClick = { rows = rows + ""; focusLast = true }) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(addLabel, color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
    LaunchedEffect(focusLast, rows.size) { if (focusLast) { runCatching { last.requestFocus() }; focusLast = false } }
}
