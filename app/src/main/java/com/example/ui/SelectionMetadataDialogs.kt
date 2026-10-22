package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.reader.MarkdownExporter
import java.util.Locale

/**
 * Helper for parsing, normalising, deduplicating, and formatting document author and abstract/summary metadata.
 */
object DocumentMetadataHelper {

    private val INTENTIONAL_LOWERCASE_AUTHORS = mapOf(
        "e. e. cummings" to "e. e. cummings",
        "e.e. cummings" to "e. e. cummings",
        "bell hooks" to "bell hooks",
        "danah boyd" to "danah boyd"
    )

    private val MAC_CAMEL_CASE_PREFIXES = setOf(
        "macdonald",
        "macarthur",
        "macmillan",
        "macleod",
        "mackenzie",
        "macpherson",
        "macgregor",
        "macbride",
        "macfarlane",
        "maclachlan",
        "macintyre",
        "macdougall",
        "macculloch",
        "macneil",
        "maclean",
        "macallister"
    )

    private val LOWERCASE_NAME_PARTICLES = setOf(
        "van", "von", "de", "da", "del", "della", "di", "den", "der", "ten", "ter", "zu"
    )

    private val ROMAN_NUMERALS = setOf(
        "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"
    )

    /**
     * Parses a stored `document.author` string into a clean list of registered authors.
     * Returns an empty list if no real authors are registered (e.g. blank or "Unknown Author").
     */
    fun parseAuthors(rawAuthor: String): List<String> {
        return MarkdownExporter.parseAuthorNamesList(rawAuthor)
    }

    /**
     * Formats a list of individual author names into the canonical comma-separated string stored in `DocumentEntity.author`.
     */
    fun formatAuthorsForStorage(authors: List<String>): String {
        val deduplicated = mutableListOf<String>()
        for (author in authors) {
            val cleaned = cleanSelectedAuthorText(author)
            if (cleaned.isBlank() ||
                cleaned.equals("Unknown", ignoreCase = true) ||
                cleaned.equals("Unknown Author", ignoreCase = true)
            ) {
                continue
            }
            val existingIdx = deduplicated.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
            if (existingIdx < 0) {
                deduplicated.add(cleaned)
            }
        }
        return if (deduplicated.isEmpty()) "Unknown Author" else deduplicated.joinToString(", ")
    }

    /**
     * Cleans selected text for an author name by removing accidental leading/trailing whitespace
     * and collapsing internal line breaks or multiple spaces into a single space while preserving capitalisation.
     */
    fun cleanSelectedAuthorText(rawSelection: String): String {
        var cleaned = rawSelection
            .replace(Regex("[\\r\\n\\t\\u00A0]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’')
            .trim()
        if (cleaned.startsWith("by ", ignoreCase = true) && cleaned.length > 3) {
            cleaned = cleaned.substring(3).trim()
        }
        return cleaned
    }

    /**
     * Cleans selected text for an abstract/summary while accurately preserving paragraph breaks (`\n\n`).
     */
    fun cleanSelectedAbstractText(rawSelection: String): String {
        val normalizedNewlines = rawSelection
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalizedNewlines.isEmpty()) return ""

        // Preserve paragraph structure while cleaning accidental trailing spaces on individual lines
        return normalizedNewlines
            .split(Regex("\\n\\s*\\n"))
            .map { paragraph ->
                paragraph
                    .lines()
                    .map { line -> line.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    /**
     * Checks if `candidateAuthor` is already present in `existingAuthors` (case-insensitively).
     */
    fun isDuplicateAuthor(existingAuthors: List<String>, candidateAuthor: String): Boolean {
        val cleaned = cleanSelectedAuthorText(candidateAuthor)
        if (cleaned.isBlank()) return false
        return existingAuthors.any { it.equals(cleaned, ignoreCase = true) }
    }

    /**
     * Checks if `candidateAuthor` matches an existing author case-insensitively, and whether its casing differs.
     */
    fun hasExactCaseDuplicate(existingAuthors: List<String>, candidateAuthor: String): Boolean {
        val cleaned = cleanSelectedAuthorText(candidateAuthor)
        if (cleaned.isBlank()) return false
        return existingAuthors.any { it == cleaned }
    }

    /**
     * Adds `newAuthorName` to `existingAuthors` without overwriting existing entries.
     * If an entry with the same name but different capitalisation already exists, updates its capitalisation in place.
     */
    fun addAuthorToList(existingAuthors: List<String>, newAuthorName: String): List<String> {
        val cleaned = cleanSelectedAuthorText(newAuthorName)
        if (cleaned.isBlank() ||
            cleaned.equals("Unknown", ignoreCase = true) ||
            cleaned.equals("Unknown Author", ignoreCase = true)
        ) {
            return existingAuthors
        }
        val existingIdx = existingAuthors.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
        return if (existingIdx >= 0) {
            existingAuthors.toMutableList().apply {
                this[existingIdx] = cleaned
            }
        } else {
            existingAuthors + cleaned
        }
    }

    /**
     * Converts an author's name into standard name capitalisation while accounting for
     * non-standard capitalisation patterns such as:
     * - `GEORGE ORWELL`, `george orwell`, `GeOrGe OrWeLl` -> `George Orwell`
     * - `McDonald`, `mcdonald`, `MCDONALD`, `McLuhan` -> `McDonald`, `McLuhan`
     * - `MacDonald`, `MacArthur` -> `MacDonald`, `MacArthur`
     * - `O'Connor`, `o'connor`, `O'CONNOR` -> `O'Connor`
     * - `d'Artagnan`, `d'Alembert` -> `d'Artagnan`, `d'Alembert`
     * - `e. e. cummings`, `bell hooks`, `danah boyd` -> preserved lowercase stylistic form
     * - `Vincent van Gogh`, `John von Neumann`, `Simone de Beauvoir` -> lowercase internal particles
     * - `Jean-Paul Sartre` -> hyphenated segments capitalised
     * - `J. R. R. Tolkien`, `J.R.R. Tolkien` -> initials capitalised
     */
    fun normalizeAuthorName(rawName: String): String {
        val cleaned = cleanSelectedAuthorText(rawName)
        if (cleaned.isEmpty()) return ""

        val lowerKey = cleaned.lowercase(Locale.ROOT)
        INTENTIONAL_LOWERCASE_AUTHORS[lowerKey]?.let { return it }

        // Expand tight initials like "j.r.r. tolkien" or preserve spacing while capitalising initials
        val words = cleaned.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return ""

        return words.mapIndexed { index, word ->
            normalizeWordToken(
                word = word,
                isFirstWord = index == 0,
                isLastWord = index == words.lastIndex,
                totalWords = words.size
            )
        }.joinToString(" ")
    }

    private fun normalizeWordToken(
        word: String,
        isFirstWord: Boolean,
        isLastWord: Boolean,
        totalWords: Int
    ): String {
        if (word.isEmpty()) return word

        // Handle hyphenated names like "JEAN-PAUL" or "anne-marie"
        if (word.contains('-') && word.length > 1) {
            return word.split('-').mapIndexed { idx, part ->
                val partLower = part.lowercase(Locale.ROOT)
                if (idx > 0 && partLower in LOWERCASE_NAME_PARTICLES) {
                    partLower
                } else {
                    normalizeSingleSegment(
                        segment = part,
                        isFirstWord = isFirstWord && idx == 0,
                        isLastWord = isLastWord,
                        totalWords = totalWords
                    )
                }
            }.joinToString("-")
        }

        return normalizeSingleSegment(
            segment = word,
            isFirstWord = isFirstWord,
            isLastWord = isLastWord,
            totalWords = totalWords
        )
    }

    private fun normalizeSingleSegment(
        segment: String,
        isFirstWord: Boolean,
        isLastWord: Boolean,
        totalWords: Int
    ): String {
        if (segment.isEmpty()) return segment

        val lower = segment.lowercase(Locale.ROOT)
        val strippedAlpha = lower.trimEnd('.', ',')

        // 1. Multi-dot initials like "j.r.r." or "h.g."
        if (Regex("^(?:[a-zA-Z]\\.){2,}$").matches(segment)) {
            return segment.uppercase(Locale.ROOT)
        }

        // 2. Single initial with period like "j." or "e."
        if (Regex("^[a-zA-Z]\\.$").matches(segment)) {
            return segment.uppercase(Locale.ROOT)
        }

        // 3. Roman numerals at the end of a name (e.g. "III", "IV")
        if (isLastWord && totalWords >= 2 && strippedAlpha in ROMAN_NUMERALS && !isFirstWord) {
            val suffixPunct = segment.substring(strippedAlpha.length)
            return strippedAlpha.uppercase(Locale.ROOT) + suffixPunct
        }

        // 4. Nobiliary particles in the middle of a multi-word name (e.g. "van", "von", "de", "da")
        if (!isFirstWord && !isLastWord && totalWords >= 3 && lower in LOWERCASE_NAME_PARTICLES) {
            return lower
        }

        // 5. Mc prefix (e.g. McDonald, mcdonald, MCDONALD, McLuhan)
        if (lower.startsWith("mc") && lower.length >= 4 && lower[2].isLetter()) {
            val rest = lower.substring(2)
            return "Mc" + rest.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        // 6. Known Mac prefixes (e.g. MacDonald, MacArthur, MacMillan)
        val matchedMac = MAC_CAMEL_CASE_PREFIXES.firstOrNull { lower == it || strippedAlpha == it }
        if (matchedMac != null && lower.length >= 5) {
            val rest = lower.substring(3)
            return "Mac" + rest.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        // 7. O' prefix (e.g. O'Connor, o'connor, O'CONNOR, O'Neill)
        val oApostropheMatch = Regex("^([oO])(['’])([a-zA-Z])(.*)$").matchEntire(segment)
        if (oApostropheMatch != null) {
            val apos = oApostropheMatch.groupValues[2]
            val thirdChar = oApostropheMatch.groupValues[3].uppercase(Locale.ROOT)
            val tail = oApostropheMatch.groupValues[4].lowercase(Locale.ROOT)
            return "O$apos$thirdChar$tail"
        }

        // 8. d' or l' prefix (e.g. d'Artagnan, d'Alembert, L'Engle)
        val dOrLApostropheMatch = Regex("^([dDlL])(['’])([a-zA-Z])(.*)$").matchEntire(segment)
        if (dOrLApostropheMatch != null) {
            val prefixLetter = dOrLApostropheMatch.groupValues[1].let {
                if (it.equals("d", ignoreCase = true) && !isFirstWord) "d" else it.uppercase(Locale.ROOT)
            }
            val apos = dOrLApostropheMatch.groupValues[2]
            val nextChar = dOrLApostropheMatch.groupValues[3].uppercase(Locale.ROOT)
            val tail = dOrLApostropheMatch.groupValues[4].lowercase(Locale.ROOT)
            return "$prefixLetter$apos$nextChar$tail"
        }

        // 9. Standard name capitalisation: First letter uppercase, remaining letters lowercase
        val firstLetterIdx = lower.indexOfFirst { it.isLetter() }
        if (firstLetterIdx < 0) return segment
        val prefix = lower.substring(0, firstLetterIdx)
        val firstLetter = lower[firstLetterIdx].titlecase(Locale.ROOT)
        val remainder = lower.substring(firstLetterIdx + 1)
        return "$prefix$firstLetter$remainder"
    }
}

/**
 * Dialog displayed when selecting **More → Add as Author** from the text-selection menu.
 *
 * Implements:
 * 1. Displays currently registered authors (if any), with support for multiple authors without overwriting.
 * 2. Clearly labelled action: `Add "[selected text]" as Author`.
 * 3. Editable author name field so the user can review and manually edit before confirming.
 * 4. Optional **Normalise Capitalisation** toggle/button with live preview (`GEORGE ORWELL` -> `George Orwell`,
 *    `McDonald`, `e. e. cummings`, etc.) that can be applied or dismissed.
 * 5. Duplicate author detection and prevention.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddAuthorFromSelectionDialog(
    selectedText: String,
    currentRawAuthor: String,
    documentTitle: String,
    onDismiss: () -> Unit,
    onConfirmAuthors: (updatedRawAuthor: String, addedAuthorName: String) -> Unit
) {
    val originalCleanedSelection = remember(selectedText) {
        DocumentMetadataHelper.cleanSelectedAuthorText(selectedText)
    }
    var existingAuthors by remember(currentRawAuthor) {
        mutableStateOf(DocumentMetadataHelper.parseAuthors(currentRawAuthor))
    }
    var authorNameInput by remember(originalCleanedSelection) {
        mutableStateOf(originalCleanedSelection)
    }
    var isNormalisationApplied by remember(originalCleanedSelection) {
        mutableStateOf(false)
    }
    var isNormalisationDismissed by remember(originalCleanedSelection) {
        mutableStateOf(false)
    }

    val normalisedPreview = remember(originalCleanedSelection, authorNameInput, isNormalisationApplied) {
        val sourceForPreview = if (isNormalisationApplied) originalCleanedSelection else authorNameInput.ifBlank { originalCleanedSelection }
        DocumentMetadataHelper.normalizeAuthorName(sourceForPreview)
    }

    val trimmedCurrentInput = remember(authorNameInput) {
        DocumentMetadataHelper.cleanSelectedAuthorText(authorNameInput)
    }

    val isExactDuplicate = remember(existingAuthors, trimmedCurrentInput) {
        DocumentMetadataHelper.hasExactCaseDuplicate(existingAuthors, trimmedCurrentInput)
    }
    val isCaseInsensitiveDuplicate = remember(existingAuthors, trimmedCurrentInput) {
        DocumentMetadataHelper.isDuplicateAuthor(existingAuthors, trimmedCurrentInput)
    }

    // Check if the name has non-standard capitalisation alternatives (e.g. e. e. cummings vs E. E. Cummings)
    val isEECummings = remember(originalCleanedSelection) {
        originalCleanedSelection.equals("e. e. cummings", ignoreCase = true) ||
            originalCleanedSelection.equals("e.e. cummings", ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .border(BorderStroke(2.dp, Color.Black), RoundedCornerShape(0.dp))
                .testTag("add_author_from_selection_dialog"),
            shape = RoundedCornerShape(0.dp),
            color = Color.White,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F7F5))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "ADD AS AUTHOR",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = Color.Black
                            )
                            if (documentTitle.isNotBlank()) {
                                Text(
                                    text = documentTitle,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic
                                    ),
                                    color = Color(0xFF52525B),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
                            .background(Color.White)
                            .testTag("close_add_author_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Currently Registered Authors Section
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Black)
                            .background(Color(0xFFFAFAFA))
                            .padding(12.dp)
                            .testTag("registered_authors_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CURRENTLY REGISTERED AUTHORS (${existingAuthors.size})",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.Black
                            )
                            if (existingAuthors.isNotEmpty()) {
                                Text(
                                    text = "Kept when adding",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    color = Color(0xFF52525B)
                                )
                            }
                        }

                        if (existingAuthors.isEmpty()) {
                            Text(
                                text = "No authors currently registered. You can add the selected text directly below.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = FontStyle.Italic
                                ),
                                color = Color(0xFF52525B),
                                modifier = Modifier.testTag("no_registered_authors_text")
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                existingAuthors.forEachIndexed { index, author ->
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(0.dp),
                                        border = BorderStroke(1.dp, Color.Black),
                                        modifier = Modifier.testTag("registered_author_chip_$index")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = author,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Review & Edit Selected Author Name Field
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NEW AUTHOR NAME (REVIEW OR EDIT)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.Black
                            )
                            if (authorNameInput != originalCleanedSelection) {
                                Text(
                                    text = "Reset to selected text",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = Color.Black,
                                    modifier = Modifier
                                        .clickable {
                                            authorNameInput = originalCleanedSelection
                                            isNormalisationApplied = false
                                        }
                                        .padding(2.dp)
                                        .testTag("reset_author_to_original_button")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = authorNameInput,
                            onValueChange = {
                                authorNameInput = it
                            },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = "Enter author name…",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            },
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black,
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("author_name_input")
                        )
                    }

                    // 3. Optional Normalise Capitalisation Control & Live Preview
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Black)
                            .background(Color(0xFFF7F7F5))
                            .padding(12.dp)
                            .testTag("author_normalisation_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Normalise Capitalisation",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.Black
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    if (isNormalisationApplied) {
                                        authorNameInput = originalCleanedSelection
                                        isNormalisationApplied = false
                                    } else {
                                        authorNameInput = normalisedPreview
                                        isNormalisationApplied = true
                                        isNormalisationDismissed = false
                                    }
                                },
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isNormalisationApplied) Color.Black else Color.White,
                                    contentColor = if (isNormalisationApplied) Color.White else Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("normalise_capitalisation_button")
                            ) {
                                Text(
                                    text = if (isNormalisationApplied) "Normalised ✓" else "Normalise Capitalisation",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (!isNormalisationDismissed) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFFD4D4D8))
                                    .background(Color.White)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Normalised preview:",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        ),
                                        color = Color(0xFF52525B)
                                    )
                                    Text(
                                        text = normalisedPreview.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.Black,
                                        modifier = Modifier.testTag("normalised_author_preview")
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            authorNameInput = normalisedPreview
                                            isNormalisationApplied = true
                                        },
                                        enabled = normalisedPreview.isNotBlank() && authorNameInput != normalisedPreview,
                                        shape = RoundedCornerShape(0.dp),
                                        border = BorderStroke(1.dp, Color.Black),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("apply_normalisation_button")
                                    ) {
                                        Text(
                                            text = "Apply",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            if (isNormalisationApplied) {
                                                authorNameInput = originalCleanedSelection
                                                isNormalisationApplied = false
                                            } else {
                                                isNormalisationDismissed = true
                                            }
                                        },
                                        shape = RoundedCornerShape(0.dp),
                                        border = BorderStroke(1.dp, Color.Black),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("dismiss_normalisation_button")
                                    ) {
                                        Text(
                                            text = if (isNormalisationApplied) "Revert" else "Dismiss",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }

                        if (isEECummings) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Presets:",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF52525B)
                                )
                                OutlinedButton(
                                    onClick = { authorNameInput = "e. e. cummings" },
                                    shape = RoundedCornerShape(0.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("e. e. cummings", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
                                }
                                OutlinedButton(
                                    onClick = { authorNameInput = "E. E. Cummings" },
                                    shape = RoundedCornerShape(0.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("E. E. Cummings", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
                                }
                            }
                        }
                    }

                    // 4. Duplicate Detection Banner
                    if (isCaseInsensitiveDuplicate) {
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("duplicate_author_warning")
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isExactDuplicate) {
                                        "“$trimmedCurrentInput” is already in this document's author list."
                                    } else {
                                        "An author matching “$trimmedCurrentInput” already exists. Confirming will update its capitalisation without creating a duplicate."
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = Color.Black)

                // 5. Pinned Action Buttons Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier
                                .weight(0.35f)
                                .height(46.dp)
                                .testTag("cancel_add_author_button")
                        ) {
                            Text(
                                text = "Cancel",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                        }

                        Button(
                            onClick = {
                                if (trimmedCurrentInput.isNotBlank() && !isExactDuplicate) {
                                    val updatedList = DocumentMetadataHelper.addAuthorToList(
                                        existingAuthors = existingAuthors,
                                        newAuthorName = trimmedCurrentInput
                                    )
                                    val formatted = DocumentMetadataHelper.formatAuthorsForStorage(updatedList)
                                    onConfirmAuthors(formatted, trimmedCurrentInput)
                                }
                            },
                            enabled = trimmedCurrentInput.isNotBlank() && !isExactDuplicate,
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFD4D4D8),
                                disabledContentColor = Color(0xFF71717A)
                            ),
                            modifier = Modifier
                                .weight(0.65f)
                                .height(46.dp)
                                .testTag("confirm_add_author_button")
                        ) {
                            val labelText = if (trimmedCurrentInput.isBlank()) {
                                "Add as Author"
                            } else {
                                "Add \"$trimmedCurrentInput\" as Author"
                            }
                            Text(
                                text = labelText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
}

/**
 * Dialog displayed when selecting **More → Set as Abstract/Summary** from the text-selection menu.
 *
 * Implements:
 * - Preserves selected passage accurately including paragraph structure.
 * - If no abstract existed before, confirms that the selected passage has been saved to metadata and allows optional editing.
 * - If an abstract or summary already exists, does NOT silently overwrite it: displays the existing abstract,
 *   asks whether to replace it, append to it, or edit it before saving.
 */
@Composable
fun SetAbstractFromSelectionDialog(
    selectedPassage: String,
    existingAbstract: String,
    documentTitle: String,
    wasAutoSavedWhenEmpty: Boolean,
    onDismiss: () -> Unit,
    onConfirmSaveAbstract: (newAbstract: String) -> Unit
) {
    val cleanedSelection = remember(selectedPassage) {
        DocumentMetadataHelper.cleanSelectedAbstractText(selectedPassage)
    }
    val cleanedExisting = remember(existingAbstract) {
        existingAbstract.trim()
    }
    val hasExistingConflict = remember(cleanedExisting, wasAutoSavedWhenEmpty) {
        !wasAutoSavedWhenEmpty && cleanedExisting.isNotBlank()
    }

    var abstractTextInput by remember(cleanedSelection) {
        mutableStateOf(cleanedSelection)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .border(BorderStroke(2.dp, Color.Black), RoundedCornerShape(0.dp))
                .testTag("set_abstract_from_selection_dialog"),
            shape = RoundedCornerShape(0.dp),
            color = Color.White,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F7F5))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "SET AS ABSTRACT / SUMMARY",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = Color.Black
                            )
                            if (documentTitle.isNotBlank()) {
                                Text(
                                    text = documentTitle,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic
                                    ),
                                    color = Color(0xFF52525B),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .border(1.dp, Color.Black, RoundedCornerShape(0.dp))
                            .background(Color.White)
                            .testTag("close_set_abstract_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (hasExistingConflict) {
                        // Conflict Banner: Existing Abstract/Summary Present
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("abstract_overwrite_warning_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "An Abstract/Summary already exists for this document. Review the existing text below and choose whether to replace it or edit before saving.",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.Black
                                )
                            }
                        }

                        // Current / Existing Abstract Box
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color.Black)
                                .background(Color(0xFFFAFAFA))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "EXISTING ABSTRACT / SUMMARY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFF52525B)
                            )
                            Text(
                                text = cleanedExisting,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Serif
                                ),
                                color = Color.Black,
                                modifier = Modifier.testTag("existing_abstract_preview")
                            )
                        }

                        // Quick Merge / Edit Helpers
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    abstractTextInput = cleanedSelection
                                },
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("use_new_selection_abstract_button")
                            ) {
                                Text(
                                    text = "Use Selected Passage",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    abstractTextInput = "$cleanedExisting\n\n$cleanedSelection".trim()
                                },
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("append_to_existing_abstract_button")
                            ) {
                                Text(
                                    text = "Append to Existing",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    abstractTextInput = cleanedExisting
                                },
                                shape = RoundedCornerShape(0.dp),
                                border = BorderStroke(1.dp, Color.Black),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("load_existing_abstract_button")
                            ) {
                                Text(
                                    text = "Edit Existing",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                        }
                    } else {
                        // Confirmation Banner when no prior abstract existed
                        Surface(
                            color = Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(0.dp),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("abstract_saved_confirmation_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Saved to document Abstract/Summary metadata! You can review or edit the passage below.",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.Black
                                )
                            }
                        }
                    }

                    // Editable Multiline Abstract / Summary Field (preserves paragraph breaks)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (hasExistingConflict) "NEW / EDITED ABSTRACT OR SUMMARY" else "ABSTRACT / SUMMARY CONTENT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.Black
                        )

                        OutlinedTextField(
                            value = abstractTextInput,
                            onValueChange = { abstractTextInput = it },
                            minLines = 4,
                            maxLines = 10,
                            placeholder = {
                                Text(
                                    text = "Enter document abstract or summary…",
                                    fontFamily = FontFamily.Serif,
                                    fontSize = 13.sp
                                )
                            },
                            shape = RoundedCornerShape(0.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black,
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("abstract_text_input")
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = Color.Black)

                // Pinned Action Buttons Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(0.dp),
                        border = BorderStroke(1.dp, Color.Black),
                        modifier = Modifier
                            .weight(0.4f)
                            .height(46.dp)
                            .testTag("cancel_abstract_dialog_button")
                    ) {
                        Text(
                            text = if (hasExistingConflict) "Keep Existing" else "Done",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }

                    Button(
                        onClick = {
                            val finalAbstract = DocumentMetadataHelper.cleanSelectedAbstractText(abstractTextInput)
                            onConfirmSaveAbstract(finalAbstract)
                        },
                        enabled = abstractTextInput.isNotBlank(),
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFD4D4D8),
                            disabledContentColor = Color(0xFF71717A)
                        ),
                        modifier = Modifier
                            .weight(0.6f)
                            .height(46.dp)
                            .testTag("confirm_save_abstract_button")
                    ) {
                        Text(
                            text = if (hasExistingConflict) "Replace Abstract/Summary" else "Save Abstract/Summary",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
