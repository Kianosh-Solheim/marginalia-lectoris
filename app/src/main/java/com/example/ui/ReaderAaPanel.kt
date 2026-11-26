package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.ui.theme.Ink
import com.example.ui.theme.InkType
import com.example.ui.theme.inkFrame
import com.example.ui.theme.inkShadow
import kotlin.math.roundToInt

/**
 * The reader's Aa panel, as in the web view (readerSettingsButton in app.js): a pop-up under the
 * bar with the settings changed while reading, in the same order and words. Everything else
 * (top and bottom margins, page turns, the engine, PDF tools) stays under "More formatting".
 *
 * Settings take effect at once; the caller saves them when the panel closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderAaPanel(
    topOffset: Dp,
    isPdfDocument: Boolean,
    isFixedLayoutBook: Boolean,
    hasPrintedPageList: Boolean,
    readerThemeSetting: String,
    onReaderThemeChange: (String) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    readerViewModeSetting: String,
    onReaderViewModeChange: (String) -> Unit,
    columnCount: Int,
    onColumnCountChange: (Int) -> Unit,
    showPageNumbers: Boolean,
    onShowPageNumbersChange: (Boolean) -> Unit,
    likePrintedBook: Boolean,
    onLikePrintedBookChange: (Boolean) -> Unit,
    marginDp: Int,
    onMarginDpChange: (Int) -> Unit,
    publisherStylesEnabled: Boolean,
    onPublisherStylesEnabledChange: (Boolean) -> Unit,
    fontFamilySetting: String,
    onFontFamilyChange: (String) -> Unit,
    lineSpacingMultiplier: Float,
    onLineSpacingChange: (Float) -> Unit,
    textAlignSetting: String,
    onTextAlignChange: (String) -> Unit,
    runningHead: Boolean,
    onRunningHeadChange: (Boolean) -> Unit,
    onMoreFormatting: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val edge = 12.dp
    val chip = 40.dp

    Popup(
        alignment = Alignment.TopEnd,
        offset = with(density) { IntOffset(-edge.roundToPx(), topOffset.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = minOf(520.dp, screenWidth - edge * 2))
                .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp - topOffset - 24.dp).coerceAtLeast(240.dp))
                .inkShadow()
                .background(Ink.Paper)
                .inkFrame()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("reader_aa_panel")
        ) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Text("Reading settings", style = InkType.Heading, modifier = Modifier.weight(1f))
                Text("SAME AS THE WEB VIEW", style = InkType.Meta.copy(fontSize = 10.sp))
            }

            AaLine("Page colour") {
                InkChip("White", readerThemeSetting.equals("WHITE", true), { onReaderThemeChange("WHITE") }, minHeight = chip)
                InkChip("Black", readerThemeSetting.equals("DARK", true), { onReaderThemeChange("DARK") }, minHeight = chip)
            }

            if (isPdfDocument) {
                AaNote("PDFs keep their own pages; Black turns them dark. Text settings apply to EPUBs.")
            } else {
                AaLine("Text size") {
                    InkButton(label = "A−", enabled = !isFixedLayoutBook, minHeight = chip,
                        onClick = { onFontSizeChange((fontSizeSp - 2f).coerceIn(12f, 76f)) })
                    Text(
                        "${fontSizeSp.roundToInt()}",
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.widthIn(min = 44.dp).align(Alignment.CenterVertically).padding(horizontal = 6.dp)
                    )
                    InkButton(label = "A+", enabled = !isFixedLayoutBook, minHeight = chip,
                        onClick = { onFontSizeChange((fontSizeSp + 2f).coerceIn(12f, 76f)) })
                }

                if (!isFixedLayoutBook) {
                    val scroll = readerViewModeSetting == "VERTICAL_SCROLL"
                    AaLine("View") {
                        InkChip("One page", !scroll && columnCount <= 1, {
                            onReaderViewModeChange("PAGINATED"); onColumnCountChange(1)
                        }, minHeight = chip)
                        InkChip("Two pages", !scroll && columnCount >= 2, {
                            onReaderViewModeChange("PAGINATED"); onColumnCountChange(2)
                        }, minHeight = chip)
                        InkChip("Scroll", scroll, { onReaderViewModeChange("VERTICAL_SCROLL") }, minHeight = chip)
                    }
                }

                if (hasPrintedPageList) {
                    AaLine("Page numbers") {
                        InkChip("Show", showPageNumbers, { onShowPageNumbersChange(!showPageNumbers) }, minHeight = chip)
                        InkChip("Like the printed book", showPageNumbers && likePrintedBook,
                            { onLikePrintedBookChange(!likePrintedBook) }, enabled = showPageNumbers, minHeight = chip)
                    }
                }

                AaLine("Margins") {
                    InkButton(label = "−", minHeight = chip, enabled = !isFixedLayoutBook,
                        onClick = { onMarginDpChange((marginDp - 4).coerceIn(4, 120)) })
                    InkSlider(
                        value = marginDp.coerceIn(4, 120), range = 4..120, step = 4,
                        enabled = !isFixedLayoutBook,
                        onChange = onMarginDpChange,
                        modifier = Modifier.width(170.dp).align(Alignment.CenterVertically)
                    )
                    InkButton(label = "+", minHeight = chip, enabled = !isFixedLayoutBook,
                        onClick = { onMarginDpChange((marginDp + 4).coerceIn(4, 120)) })
                    Text(
                        "$marginDp dp", style = InkType.Meta.copy(color = Ink.Ink, fontWeight = FontWeight.Bold),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                AaLine("Book’s own styles") {
                    InkChip("On", publisherStylesEnabled, { onPublisherStylesEnabledChange(true) }, minHeight = chip)
                    InkChip("Off", !publisherStylesEnabled, { onPublisherStylesEnabledChange(false) }, minHeight = chip)
                }
                val own = publisherStylesEnabled
                if (own) AaNote("The book’s fonts and spacing are kept. Turn off to use your font, spacing and alignment.")
                AaLine("Font") {
                    listOf("SERIF" to "Serif", "SANS" to "Sans", "MONO" to "Mono").forEach { (id, label) ->
                        InkChip(label, !own && fontFamilySetting == id, { onFontFamilyChange(id) }, enabled = !own, minHeight = chip)
                    }
                }
                AaLine("Line spacing") {
                    listOf(1.25f to "Tight", 1.55f to "Normal", 1.9f to "Loose").forEach { (m, label) ->
                        InkChip(label, !own && kotlin.math.abs(lineSpacingMultiplier - m) < 0.1f, { onLineSpacingChange(m) }, enabled = !own, minHeight = chip)
                    }
                }
                AaLine("Alignment") {
                    InkChip("Justified", !own && textAlignSetting == "JUSTIFY", { onTextAlignChange("JUSTIFY") }, enabled = !own, minHeight = chip)
                    InkChip("Left", !own && textAlignSetting == "LEFT", { onTextAlignChange("LEFT") }, enabled = !own, minHeight = chip)
                }
            }

            AaLine("Running head") {
                InkChip("On", runningHead, { onRunningHeadChange(true) }, minHeight = chip)
                InkChip("Off", !runningHead, { onRunningHeadChange(false) }, minHeight = chip)
            }
            AaNote("In full screen: the title and chapter in small capitals at the top.")

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text("Changes show at once.", style = InkType.Meta, modifier = Modifier.weight(1f))
                InkButton(label = "More formatting ›", minHeight = chip, onClick = onMoreFormatting,
                    modifier = Modifier.testTag("reader_aa_more_formatting"))
            }
        }
    }
}

/** One line of the Aa panel: a mono label on the left, its controls on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AaLine(label: String, content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Ink))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = InkType.Label.copy(color = Ink.Ink), modifier = Modifier.width(124.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) { content() }
    }
}

@Composable
private fun AaNote(text: String) {
    Text(text, style = InkType.Meta.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp), modifier = Modifier.padding(start = 124.dp, bottom = 8.dp))
}

/**
 * A square slider for e-ink: a ruled track that fills with black, and a tall square handle.
 * Tap anywhere on the track or drag; the value snaps to [step].
 */
@Composable
internal fun InkSlider(
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    val frac = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    val ink = if (enabled) Ink.Ink else Ink.Soft
    BoxWithConstraints(
        modifier = modifier
            .height(36.dp)
            .semantics { contentDescription = "Slider"; stateDescription = "$value" }
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val thumbPx = with(LocalDensity.current) { 16.dp.toPx() }
        fun at(x: Float): Int {
            val f = ((x - thumbPx / 2) / (widthPx - thumbPx)).coerceIn(0f, 1f)
            val raw = range.first + f * span
            return (range.first + ((raw - range.first) / step).roundToInt() * step).coerceIn(range.first, range.last)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(enabled, widthPx) { if (enabled) detectTapGestures { onChange(at(it.x)) } }
                .pointerInput(enabled, widthPx) { if (enabled) detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x)) } }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(14.dp)
                    .border(1.5.dp, ink)
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(ink))
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((frac * (widthPx - thumbPx)).roundToInt(), 0) }
                    .size(width = 16.dp, height = 32.dp)
                    .background(Ink.Paper)
                    .border(2.dp, ink)
                    .padding(4.dp)
                    .background(ink)
            )
        }
    }
}
