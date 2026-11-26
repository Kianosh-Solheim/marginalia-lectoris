package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Whether full screen shows a running head (the book's title and the chapter at the top). On by default. */
object RunningHead {
    private const val PREFS = "reader_view"
    private const val KEY = "running_head"
    fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * The running head shown in full screen, as in a printed book: the title on the left and the
 * chapter on the right, in small mono capitals. Quiet, and drawn once per page (no animation).
 */
@Composable
internal fun ReaderRunningHead(title: String, chapter: String, theme: ReaderTheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.backgroundColor)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag("reader_running_head"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(Locale.getDefault()),
            fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, letterSpacing = 0.05.em,
            color = theme.secondaryTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (chapter.isNotBlank()) {
            Text(
                chapter.uppercase(Locale.getDefault()),
                fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, letterSpacing = 0.05.em,
                color = theme.secondaryTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
        }
    }
}
