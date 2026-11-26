package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.HighlightEntity
import com.example.ui.theme.Ink
import com.example.ui.theme.InkType

/**
 * The Notes panel, as in the web view's right panel (#notes): the notes in this book, title first,
 * then the quotation with its colour, then page and tags with "Go to ›". A field on top jumps to a
 * note by its title, words of the quotation, a page ("p. 183") or a tag ("#restraint").
 * Opens from the right over the text; the Contents panel uses the same place.
 */
@Composable
internal fun ReaderNotesPanel(
    highlights: List<HighlightEntity>,
    currentChapterTitle: String,
    onGoTo: (HighlightEntity) -> Unit,
    onEdit: (HighlightEntity) -> Unit,
    onShowContents: () -> Unit,
    onOpenExport: () -> Unit,
    onClose: () -> Unit
) {
    var thisChapter by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val width = minOf(380.dp, LocalConfiguration.current.screenWidthDp.dp * 0.9f)

    val shown = remember(highlights, thisChapter, query, currentChapterTitle) {
        val q = query.trim().lowercase()
        highlights
            .filter { h -> h.title.isNotBlank() || h.highlightedText.isNotBlank() }
            .filter { h -> !thisChapter || currentChapterTitle.isBlank() || h.chapterTitle.equals(currentChapterTitle, ignoreCase = true) }
            .filter { h ->
                if (q.isEmpty()) return@filter true
                val page = pageOf(h).lowercase()
                when {
                    q.startsWith("#") -> h.tags.lowercase().split(',').any { it.trim().removePrefix("#").startsWith(q.removePrefix("#")) }
                    q.startsWith("p.") || q.startsWith("s.") -> page.removePrefix("p. ").startsWith(q.drop(2).trim())
                    else -> h.title.lowercase().contains(q) || h.highlightedText.lowercase().contains(q) ||
                        h.userNotes.lowercase().contains(q) || h.tags.lowercase().contains(q)
                }
            }
            .sortedWith(compareBy({ it.pageNumber }, { it.createdTimestamp }))
    }

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(Ink.Paper)
            .border(Ink.OuterRule, Ink.Ink)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("reader_notes_panel")
    ) {
        // Contents | Notes, as the web view's segmented control
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            InkChip("Contents", false, onShowContents, minHeight = 40.dp)
            InkChip("Notes ${highlights.size}", true, {}, minHeight = 40.dp)
            Box(Modifier.weight(1f))
            InkButton(label = "×", onClick = onClose, minHeight = 40.dp, modifier = Modifier.testTag("reader_notes_close"))
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InkChip("This chapter", thisChapter, { thisChapter = true }, minHeight = 36.dp)
            InkChip("All", !thisChapter, { thisChapter = false }, minHeight = 36.dp)
        }

        // Jump to a note, a page or a tag
        Box(
            Modifier
                .padding(top = 8.dp, bottom = 4.dp)
                .fillMaxWidth()
                .height(44.dp)
                .border(Ink.InnerRule, Ink.Ink)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) Text("Jump to a note, a page or a tag…", style = InkType.Body.copy(color = Ink.Soft, fontSize = 14.sp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Ink.Ink),
                cursorBrush = SolidColor(Ink.Ink),
                modifier = Modifier.fillMaxWidth().testTag("reader_notes_jump")
            )
        }

        if (shown.isEmpty()) {
            Text(
                if (highlights.isEmpty()) "No notes in this book yet. Select a passage and tap Note…" else "No notes match.",
                style = InkType.Body.copy(color = Ink.Grey, fontSize = 14.sp),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { h ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(h) }
                        .padding(vertical = 10.dp)
                ) {
                    if (h.title.isNotBlank()) {
                        Text(
                            h.title,
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink.Ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Row {
                        Box(Modifier.width(4.dp).height(40.dp).background(colourOf(h.colorHex)))
                        Text(
                            h.highlightedText,
                            style = InkType.Quote.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val tags = h.tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
                        Text(
                            (listOf(pageOf(h)) + tags.map { "#$it" }).filter { it.isNotBlank() }.joinToString(" · "),
                            style = InkType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        InkButton(label = "Go to ›", onClick = { onGoTo(h) }, minHeight = 34.dp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Rule))
            }
        }

        Row(Modifier.padding(top = 8.dp)) {
            InkButton(label = "Export & Obsidian…", onClick = onOpenExport, minHeight = 40.dp)
        }
    }
}

private fun pageOf(h: HighlightEntity): String {
    val printed = h.printedPageLabel.trim().ifBlank { h.pageEquivalent.trim() }
    return if (printed.isNotBlank()) (if (printed.startsWith("p")) printed else "p. $printed") else "p. ${h.pageNumber}"
}

private fun colourOf(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Ink.Rule)
