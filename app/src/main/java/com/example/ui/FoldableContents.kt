package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.SearchIndex

/**
 * Folding contents lists (the book overview and the reader's contents): an entry with deeper entries
 * after it can be folded; a filter shows the matching entries and what they are under.
 */
object Fold {
    /** How many entries are under each entry. */
    fun kids(depth: List<Int>): IntArray = IntArray(depth.size) { i -> var j = i + 1; while (j < depth.size && depth[j] > depth[i]) j++; j - i - 1 }

    /** The entries shown, in order. [titles] is used for the filter. */
    fun visible(depth: List<Int>, folded: Set<Int>, filter: String, titles: List<String>): List<Int> {
        val n = depth.size
        val words = SearchIndex.tokens(filter)
        if (words.isNotEmpty()) {
            val show = BooleanArray(n)
            for (i in 0 until n) {
                val t = SearchIndex.tokens(titles[i]).joinToString(" ")
                if (!words.all { t.contains(it) }) continue
                show[i] = true
                var d = depth[i]; var j = i - 1
                while (j >= 0 && d > 0) { if (depth[j] < d) { show[j] = true; d = depth[j] }; j-- }
            }
            return (0 until n).filter { show[it] }
        }
        val kids = kids(depth)
        val out = ArrayList<Int>(n)
        var foldAt = Int.MAX_VALUE
        for (i in 0 until n) {
            if (depth[i] <= foldAt) foldAt = Int.MAX_VALUE
            if (depth[i] > foldAt) continue
            out += i
            if (i in folded && kids[i] > 0) foldAt = depth[i]
        }
        return out
    }
}

/** Filter box with Expand all / Collapse all above a contents list. */
@Composable
fun FoldBar(filter: String, onFilter: (String) -> Unit, onExpandAll: () -> Unit, onCollapseAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InkField(filter, onFilter, "Find in the contents…", Modifier.weight(1f))
        InkChip("Expand all", false, onExpandAll)
        InkChip("Collapse all", false, onCollapseAll)
    }
}

/** ▾ / ▸ before an entry that has entries under it; when folded, how many. */
@Composable
fun FoldToggle(hasKids: Boolean, folded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!hasKids) { Spacer(modifier.width(26.dp)); return }
    Box(modifier.width(26.dp).clickable(onClick = onClick).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(if (folded) "▸" else "▾", fontSize = 16.sp, color = Color(0xFF52525B))
    }
}

/** "6 sections" next to a folded entry. */
@Composable
fun FoldCount(folded: Boolean, count: Int, noun: String) {
    if (folded && count > 0) Text("▸ $count $noun", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF71717A))
}
