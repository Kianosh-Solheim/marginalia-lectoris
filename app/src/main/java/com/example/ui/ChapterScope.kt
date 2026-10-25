package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.BibStore
import com.example.reader.Citations
import com.example.reader.EditedVolume
import com.example.reader.EditedVolumes

/** What a notes preview shows: the whole book, or one chapter of an edited volume as its own source. */
class ChapterScope(
    val book: DocumentEntity,
    val bookHighlights: List<HighlightEntity>,
    val volume: EditedVolume?,
    selectedState: MutableState<Int>
) {
    var selected by selectedState
    val articles: List<Int> = volume?.chapters?.indices?.filter { volume.chapters[it].isArticle }.orEmpty()
    val isVolume: Boolean get() = volume != null && articles.isNotEmpty()

    val document: DocumentEntity by lazy { if (volume != null && selected >= 0) EditedVolumes.chapterAsDocument(book, volume, selected).first else book }

    val highlights: List<HighlightEntity> by lazy { if (volume != null && selected >= 0) EditedVolumes.highlightsIn(volume, selected, bookHighlights) else bookHighlights }
}

@Composable
fun rememberChapterScope(document: DocumentEntity, highlights: List<HighlightEntity>, startAtPage: Int? = null): ChapterScope {
    val context = LocalContext.current
    val volume = remember(document.id) {
        BibStore.init(context)
        EditedVolumes.get(context, document.id)?.takeIf { it.enabled && it.chapters.any { c -> c.isArticle } }
    }
    val initial = remember(document.id, startAtPage) {
        if (volume != null && startAtPage != null) EditedVolumes.chapterIndexFor(volume, startAtPage, document.lastPositionChapter) else -1
    }
    val selected = remember(document.id) { mutableStateOf(initial) }
    return remember(document, highlights, volume, selected.value) { ChapterScope(document, highlights, volume, selected) }
}

/** "Whole book / chapter" picker plus a card with the chapter's title, author(s), book, editors and citation. */
@Composable
fun ChapterScopeBar(scope: ChapterScope) {
    if (!scope.isVolume) return
    val v = scope.volume ?: return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InkChip("Whole book", scope.selected < 0, { scope.selected = -1 })
            Box {
                val sel = v.chapters.getOrNull(scope.selected)
                InkChip(
                    if (sel != null) "Chapter: ${chapterLine(v, scope.selected).take(36)}" else "Choose a chapter ▾",
                    scope.selected >= 0, { open = true }
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    scope.articles.forEach { i ->
                        val c = v.chapters[i]
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(chapterLine(v, i), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(c.authors.replace("; ", ", ").ifBlank { "Unknown author" }, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 12.sp,
                                        color = if (c.authors.isBlank()) Color(0xFF71717A) else Color.Unspecified)
                                }
                            },
                            onClick = { scope.selected = i; open = false }
                        )
                    }
                }
            }
        }
        val c = v.chapters.getOrNull(scope.selected)
        if (c != null) {
            val doc = scope.document
            val rec = remember(doc.id, c) { BibStore.get(doc.id) }
            val editors = EditedVolumes.editors(scope.book)
            Column(
                Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(2.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(chapterLine(v, scope.selected), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black)
                Text(c.authors.replace("; ", ", ").ifBlank { "Author not set — add it in the chapter list or with Set as → Chapter author" },
                    fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, color = if (c.authors.isBlank()) Color(0xFF52525B) else Color.Black)
                Text(
                    "In ${scope.book.title}" + (if (editors.isNotBlank()) ", edited by ${editors.replace("; ", ", ")}" else "") +
                        (if (c.printedPages.isNotBlank()) " · pp. ${c.printedPages}" else ""),
                    fontSize = 12.sp, color = Color.Black
                )
                Text("${scope.highlights.size} note${if (scope.highlights.size == 1) "" else "s"} in this chapter", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF52525B))
                Text(Citations.reference(Citations.defaultStyle(), doc, rec).replace("*", ""), fontFamily = FontFamily.Serif, fontSize = 12.sp, color = Color(0xFF27272A))
            }
        }
    }
}

private fun chapterLine(v: EditedVolume, i: Int): String {
    val c = v.chapters[i]
    val n = EditedVolumes.numberOf(v.chapters, i)
    return if (n.isNotBlank()) "$n. ${c.title}" else c.title
}
