package com.example.model

enum class DocumentCategory(val displayName: String) {
    BOOK("Book"),
    ARTICLE("Article")
}

enum class DocumentType(val extension: String) {
    EPUB("epub"),
    PDF("pdf"),
    DAISY("daisy")
}

enum class ReadingTheme(val displayName: String, val bgHex: Long, val textHex: Long, val surfaceHex: Long) {
    EINK("E-Paper Mono", 0xFFFFFFFF, 0xFF000000, 0xFFFAFAFA),
    SOFT("Soft E-Paper", 0xFFF7F7F8, 0xFF18181B, 0xFFEEEEF0),
    HIGH_CONTRAST("High Contrast", 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF),
    DARK("Inverted Dark", 0xFF121214, 0xFFF4F4F5, 0xFF1E2024)
}

data class HighlightColorOption(
    val name: String,
    val hex: String,
    val colorValue: Long
)

// Yellow & E-Ink shading palette for note highlights (Yellow visible on both color and monochrome e-ink screens)
val HighlightPalette = listOf(
    HighlightColorOption("Classic Yellow", "#FFEB3B", 0xFFFFEB3B),
    HighlightColorOption("Soft Yellow", "#FFF59D", 0xFFFFF59D),
    HighlightColorOption("Amber Gold", "#FFE082", 0xFFFFE082),
    HighlightColorOption("Orange", "#FF9800", 0xFFFF9800),
    HighlightColorOption("Green", "#81C784", 0xFF81C784),
    HighlightColorOption("Blue", "#64B5F6", 0xFF64B5F6),
    HighlightColorOption("Purple", "#BA68C8", 0xFFBA68C8),
    HighlightColorOption("Coral Red", "#EF5350", 0xFFEF5350),
    HighlightColorOption("Paper Wash", "#E4E4E7", 0xFFE4E4E7),
    HighlightColorOption("Pencil Gray", "#D4D4D8", 0xFFD4D4D8)
)

fun parseHighlightColor(hex: String?): Long {
    if (hex.isNullOrBlank()) return 0xFFFFEB3B
    val match = HighlightPalette.firstOrNull { it.hex.equals(hex.trim(), ignoreCase = true) }
    if (match != null) return match.colorValue
    return try {
        val clean = hex.trim().removePrefix("#")
        when (clean.length) {
            6 -> (0xFF000000L or clean.toLong(16))
            8 -> clean.toLong(16)
            else -> 0xFFFFEB3B
        }
    } catch (_: Exception) {
        0xFFFFEB3B
    }
}
