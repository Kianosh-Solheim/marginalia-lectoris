package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// ============================================================================
// Color E-Ink (Kaleido 3) & Editorial E-Paper Design System Palette
// ============================================================================

// Pure Paper & Ink Foundation
val PaperWhite = Color(0xFFFFFFFF)
val PaperBackground = Color(0xFFFFFFFF)
val PaperLightGray = Color(0xFFF7F7F5)
val PaperBorder = Color(0xFF18181B)
val PaperDarkBorder = Color(0xFF000000)
val PaperSubtleRule = Color(0xFFD4D4D8)

val InkBlack = Color(0xFF000000)
val InkCharcoal = Color(0xFF18181B)
val InkMutedText = Color(0xFF3F3F46)

// Muted Kaleido 3 Color E-Ink Accents (Matching the Editorial Color E-Paper Reference)
// 1. Kaleido Sage Green (DAISY / Books / Nature / Completed)
val KaleidoSageFill = Color(0xFF7B9E87)
val KaleidoSageInk = Color(0xFF000000)
val KaleidoSageBorder = Color(0xFF000000)

// 2. Kaleido Cobalt Slate Blue (EPUB / Active Filter / Primary Accent)
val KaleidoCobaltFill = Color(0xFF6B8DA9)
val KaleidoCobaltInk = Color(0xFF000000)
val KaleidoCobaltBorder = Color(0xFF000000)

// 3. Kaleido Terracotta Rust (PDF / Articles / Editorial Stamp)
val KaleidoTerracottaFill = Color(0xFFC26D5E)
val KaleidoTerracottaInk = Color(0xFF000000)
val KaleidoTerracottaBorder = Color(0xFF000000)

// 4. Kaleido Amber Ochre (AI Spark / Highlights / Notes)
val KaleidoAmberFill = Color(0xFFD1B35C)
val KaleidoAmberInk = Color(0xFF000000)
val KaleidoAmberBorder = Color(0xFF000000)

// 5. Kaleido Plum (Obsidian / MOC / PKM Vault)
val KaleidoPlumFill = Color(0xFFE8E8E6)
val KaleidoPlumInk = Color(0xFF000000)
val KaleidoPlumBorder = Color(0xFF000000)

// 6. Kaleido Teal Slate (DAISY / Audio / Secondary Badges)
val KaleidoTealFill = Color(0xFF7B9E87)
val KaleidoTealInk = Color(0xFF000000)
val KaleidoTealBorder = Color(0xFF000000)

data class KaleidoTagSwatch(
    val fill: Color,
    val ink: Color,
    val border: Color
)

/**
 * Returns a crisp pure-white e-paper tag swatch with a sharp 1dp black border and monospace black ink,
 * matching the `[ #Politics ]` / `[ #Arbeiderpartiet ]` style in the reference design.
 */
fun kaleidoSwatchForTag(tag: String): KaleidoTagSwatch {
    return KaleidoTagSwatch(
        fill = Color.White,
        ink = Color.Black,
        border = Color.Black
    )
}

/**
 * Returns a Color E-Ink (Kaleido) swatch tailored to document format:
 * - Red for PDF
 * - Yellow for EPUB
 * - Green for DAISY
 * - Blue for HTML
 * - Purple for MD
 */
fun kaleidoSwatchForFileType(fileType: String): KaleidoTagSwatch {
    return when (fileType.trim().uppercase()) {
        "PDF" -> KaleidoTagSwatch(Color(0xFFC26D5E), Color.Black, Color.Black) // Red
        "EPUB" -> KaleidoTagSwatch(Color(0xFFD1B35C), Color.Black, Color.Black) // Yellow
        "DAISY" -> KaleidoTagSwatch(Color(0xFF7B9E87), Color.Black, Color.Black) // Green
        "HTML", "HTM", "XHTML" -> KaleidoTagSwatch(Color(0xFF6B8DA9), Color.Black, Color.Black) // Blue
        "MD", "MARKDOWN" -> KaleidoTagSwatch(Color(0xFF9C7CB8), Color.Black, Color.Black) // Purple
        "TXT" -> KaleidoTagSwatch(Color(0xFFE8E8E6), Color.Black, Color.Black)
        "ZOTERO" -> KaleidoTagSwatch(Color.White, Color.Black, Color.Black) // No file yet: an outlined badge
        else -> KaleidoTagSwatch(Color(0xFF6B8DA9), Color.Black, Color.Black)
    }
}

// MaterialTheme ColorScheme bindings (Pure White Paper, Solid Ink, Zero Gray Darkening)
val MonoPrimary = Color(0xFF000000)
val MonoOnPrimary = Color(0xFFFFFFFF)
val MonoPrimaryContainer = Color(0xFFEAF2FA)
val MonoOnPrimaryContainer = Color(0xFF000000)

val MonoSecondary = Color(0xFF1B4332)
val MonoOnSecondary = Color(0xFFFFFFFF)
val MonoSecondaryContainer = Color(0xFFEBF4EE)
val MonoOnSecondaryContainer = Color(0xFF000000)

val MonoBackground = Color(0xFFFFFFFF)
val MonoOnBackground = Color(0xFF000000)
val MonoSurface = Color(0xFFFFFFFF)
val MonoOnSurface = Color(0xFF000000)
val MonoSurfaceVariant = Color(0xFFFFFFFF)
val MonoOnSurfaceVariant = Color(0xFF18181B)
val MonoOutline = Color(0xFF000000)
