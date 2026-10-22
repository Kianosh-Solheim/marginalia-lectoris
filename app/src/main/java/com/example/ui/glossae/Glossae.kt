package com.example.ui.glossae

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.HighlightEntity
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

/**
 * Glossae: a display-only reading mode that lays the reader's own notes ("glosses") over the
 * passages they annotate. Tapping a gloss card flips it to reveal the original text, tapping again
 * flips it back. Stored highlights and the Markdown export are never changed by this mode.
 */
object Glossae {

    private const val PREFS = "glossae_prefs"

    /** A highlight gets a gloss card only if it carries a title or notes of its own. */
    fun hasGloss(h: HighlightEntity): Boolean = h.title.isNotBlank() || h.userNotes.isNotBlank()

    /** The Glossae switch is remembered per book. */
    fun isEnabled(context: Context, documentId: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("doc_$documentId", false)

    fun setEnabled(context: Context, documentId: Long, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("doc_$documentId", enabled).apply()
    }

    /**
     * Rough estimate of whether a note fits on a card of [widthPx] x [heightPx]. Used to decide whether a
     * tap in the card's bottom-right corner means "more" (open the full note) rather than "flip".
     */
    fun noteLikelyOverflows(h: HighlightEntity, widthPx: Float, heightPx: Float, baseFontPx: Float): Boolean {
        val note = h.userNotes.trim()
        if (note.isEmpty()) return false
        val notePx = (baseFontPx * 0.86f).coerceAtLeast(6f)
        val titleLinePx = if (h.title.isNotBlank()) baseFontPx * 1.05f * 1.25f else 0f
        val usableH = (heightPx - titleLinePx - baseFontPx * 0.3f).coerceAtLeast(0f)
        val lines = (usableH / (notePx * 1.3f)).toInt()
        if (lines <= 0) return true
        val charsPerLine = (widthPx / (notePx * 0.5f)).coerceAtLeast(1f)
        val explicitLines = note.count { it == '\n' }
        return note.length > (charsPerLine * lines * 0.92f) || explicitLines >= lines
    }

    /** True if [x],[y] falls in the "… more" corner of a card spanning [left]..[right], [top]..[bottom]. */
    fun isInMoreZone(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, density: Float): Boolean {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return false
        val zoneW = (88f * density).coerceAtMost(w * 0.45f)
        val zoneH = (40f * density).coerceAtMost(h * 0.6f)
        return x >= right - zoneW && y >= bottom - zoneH
    }

    internal fun escapeHtml(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> if (c.code < 0x20 && c != '\n' && c != '\t') append(' ') else append(c)
            }
        }
    }

    private fun css(@ColorInt c: Int, alpha: Float? = null): String {
        val a = alpha ?: (((c ushr 24) and 0xFF) / 255f)
        return "rgba(${(c shr 16) and 0xFF}, ${(c shr 8) and 0xFF}, ${c and 0xFF}, $a)"
    }

    // ------------------------------------------------------------------ Readium (EPUB)

    /**
     * Readium decoration template for [GlossCardStyle]. One element covers the bounding box of the
     * highlighted passage. Critical styles are inline with !important so ReadiumCSS theme rules
     * (sepia / night make every element transparent) cannot make the card see-through.
     */
    fun readiumTemplate(): HtmlDecorationTemplate = HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        width = HtmlDecorationTemplate.Width.WRAP,
        element = { decoration -> readiumElement(decoration) },
        stylesheet = """
            @keyframes mlGlossFlip {
              from { transform: perspective(900px) rotateY(-88deg); opacity: 0.35; }
              to { transform: perspective(900px) rotateY(0deg); opacity: 1; }
            }
            .ml-gloss-anim { animation: mlGlossFlip 260ms ease-out both; transform-origin: 50% 50%; }
            .ml-gloss-n::after {
              content: ""; display: block; height: 4000px; position: relative; z-index: 2; margin: 0 -2em;
              background-color: var(--mlbg) !important;
            }
        """.trimIndent()
    )

    private fun readiumElement(decoration: Decoration): String {
        val s = decoration.style as? GlossCardStyle ?: return "<div></div>"
        val anim = if (s.animate) " ml-gloss-anim" else ""
        val bg = css(s.background, 1f)
        val fg = css(s.foreground, 1f)
        val rule = css(s.foreground, 0.55f)
        if (s.flipped) {
            return "<div class=\"ml-gloss ml-gloss-back$anim\" data-activable=\"1\" style=\"box-sizing: border-box !important; " +
                "background-color: transparent !important; border: 1px dashed $rule !important; border-radius: 3px !important;\"></div>"
        }
        val title = escapeHtml(s.title.trim())
        val note = escapeHtml(s.note.trim())
        val sb = StringBuilder()
        sb.append("<div class=\"ml-gloss ml-gloss-front$anim\" data-activable=\"1\" style=\"--mlbg: $bg; ")
            .append("background-color: $bg !important; color: $fg !important; border: 1px solid $rule !important; ")
            .append("box-sizing: border-box !important; overflow: hidden !important; isolation: isolate; border-radius: 3px !important; ")
            .append("padding: 0 0.45em !important; margin: 0 !important; text-align: left !important; text-indent: 0 !important; ")
            .append("font-family: Georgia, 'Noto Serif', 'Droid Serif', serif !important; line-height: 1.25 !important; ")
            .append("-webkit-user-select: none; user-select: none;\">")
        if (title.isNotEmpty()) {
            sb.append("<span class=\"ml-gloss-t\" style=\"display: block !important; font-weight: 700 !important; font-size: 1.05em !important; ")
                .append("line-height: 1.25 !important; margin: 0 !important; color: $fg !important; ")
                .append("white-space: nowrap !important; overflow: hidden !important; text-overflow: ellipsis !important;\">")
                .append(title).append("</span>")
        }
        if (note.isNotEmpty()) {
            sb.append("<span class=\"ml-gloss-n\" style=\"display: block !important; position: relative; font-size: 0.86em !important; ")
                .append("line-height: 1.3 !important; margin: 0 !important; color: $fg !important; white-space: pre-wrap !important;\">")
                .append(note).append("</span>")
            sb.append("<span class=\"ml-gloss-more\" style=\"position: absolute !important; right: 0.3em !important; bottom: 0.15em !important; ")
                .append("z-index: 1; font-size: 0.7em !important; line-height: 1.45 !important; padding: 0 0.5em !important; ")
                .append("border: 1px solid $fg !important; border-radius: 999px !important; background-color: $bg !important; color: $fg !important; ")
                .append("font-family: sans-serif !important; font-weight: 600 !important;\">… more</span>")
        }
        sb.append("</div>")
        return sb.toString()
    }
}

/**
 * Readium decoration style for a gloss card. [flipped] shows the original text (a dashed outline
 * stays so the card can be tapped again); [animate] plays the card-turn animation once.
 */
data class GlossCardStyle(
    val title: String,
    val note: String,
    val flipped: Boolean,
    val animate: Boolean,
    @ColorInt val background: Int,
    @ColorInt val foreground: Int
) : Decoration.Style {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(title)
        dest.writeString(note)
        dest.writeInt(if (flipped) 1 else 0)
        dest.writeInt(if (animate) 1 else 0)
        dest.writeInt(background)
        dest.writeInt(foreground)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GlossCardStyle> = object : Parcelable.Creator<GlossCardStyle> {
            override fun createFromParcel(source: Parcel): GlossCardStyle = GlossCardStyle(
                title = source.readString().orEmpty(),
                note = source.readString().orEmpty(),
                flipped = source.readInt() == 1,
                animate = source.readInt() == 1,
                background = source.readInt(),
                foreground = source.readInt()
            )

            override fun newArray(size: Int): Array<GlossCardStyle?> = arrayOfNulls(size)
        }
    }
}

// ---------------------------------------------------------------------- shared UI

/** Preview of the whole note, opened from the card's "… more" button. */
@Composable
fun GlossNotePreviewDialog(
    highlight: HighlightEntity,
    onDismiss: () -> Unit,
    onEdit: (HighlightEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onEdit(highlight)
            }) { Text("Edit note") }
        },
        title = {
            Text(
                text = highlight.title.ifBlank { "Note" },
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = highlight.userNotes.ifBlank { "(No notes)" },
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
            }
        }
    )
}

/** Card geometry on a PDF page, as fractions (0..1) of the rendered page image. */
data class GlossPdfCard(
    val highlight: HighlightEntity,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Draws gloss cards over a rendered PDF page. Purely visual: taps are routed by the page's own
 * gesture handler (see PdfReaderContent) so they never conflict with selection and page turns.
 *
 * @param onOverflowChanged reports which cards cannot show their whole note (they get "… more").
 */
@Composable
fun GlossPdfOverlay(
    cards: List<GlossPdfCard>,
    flipped: Set<Long>,
    background: Color,
    foreground: Color,
    onOverflowChanged: (highlightId: Long, overflows: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val pageW = maxWidth
        val pageH = maxHeight
        for (card in cards) {
            val isFlipped = card.highlight.id in flipped
            val rotation by animateFloatAsState(
                targetValue = if (isFlipped) 180f else 0f,
                animationSpec = tween(durationMillis = 260),
                label = "glossFlip"
            )
            val cardModifier = Modifier
                .offset(x = pageW * card.left, y = pageH * card.top)
                .size(
                    width = (pageW * (card.right - card.left)).coerceAtLeast(24.dp),
                    height = (pageH * (card.bottom - card.top)).coerceAtLeast(14.dp)
                )
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 14f * density
                }
            if (rotation > 90f) {
                // Back: original text visible, dashed-looking outline so it can be tapped back.
                Box(
                    modifier = cardModifier
                        .graphicsLayer { rotationY = 180f }
                        .border(BorderStroke(1.dp, foreground.copy(alpha = 0.5f)), RoundedCornerShape(3.dp))
                )
            } else {
                GlossPdfCardFront(card, background, foreground, onOverflowChanged, cardModifier)
            }
        }
    }
}

@Composable
private fun GlossPdfCardFront(
    card: GlossPdfCard,
    background: Color,
    foreground: Color,
    onOverflowChanged: (Long, Boolean) -> Unit,
    modifier: Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .background(background, RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, foreground.copy(alpha = 0.55f)), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        // Scale the type to the card: a one-line highlight gets small type, a paragraph normal type.
        val heightPx = with(density) { maxHeight.toPx() }
        val baseSp = with(density) { (heightPx / 2.6f).coerceIn(9.dp.toPx(), 18.dp.toPx()).toSp() }
        var maxNoteLines by remember(card.highlight.id, card.highlight.userNotes, heightPx) { mutableIntStateOf(Int.MAX_VALUE) }
        val overflows = maxNoteLines != Int.MAX_VALUE
        LaunchedEffect(overflows) { onOverflowChanged(card.highlight.id, overflows) }

        Column(modifier = Modifier.fillMaxSize()) {
            if (card.highlight.title.isNotBlank()) {
                Text(
                    text = card.highlight.title.trim(),
                    color = foreground,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = baseSp * 1.05f,
                    lineHeight = baseSp * 1.3f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (card.highlight.userNotes.isNotBlank()) {
                Text(
                    text = card.highlight.userNotes.trim(),
                    color = foreground,
                    fontFamily = FontFamily.Serif,
                    fontSize = baseSp * 0.86f,
                    lineHeight = baseSp * 1.15f,
                    maxLines = maxNoteLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    onTextLayout = { layout ->
                        if (maxNoteLines == Int.MAX_VALUE && layout.hasVisualOverflow) {
                            val visibleH = layout.size.height.toFloat()
                            var fit = 0
                            for (i in 0 until layout.lineCount) {
                                if (layout.getLineBottom(i) <= visibleH + 0.5f) fit = i + 1 else break
                            }
                            maxNoteLines = fit.coerceAtLeast(1)
                        }
                    }
                )
            }
        }
        if (overflows) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(background, RoundedCornerShape(50))
                    .border(BorderStroke(1.dp, foreground), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp)
            ) {
                Text(text = "… more", color = foreground, fontSize = (baseSp * 0.7f), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
