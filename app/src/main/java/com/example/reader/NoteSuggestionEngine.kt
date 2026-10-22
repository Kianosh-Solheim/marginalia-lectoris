package com.example.reader

import java.util.Locale

/**
 * Intelligent PKM Suggestion Engine that analyzes highlighted quotes, user notes, and chapter context
 * to suggest concise, meaningful note titles and relevant PKM/Obsidian tags.
 */
object NoteSuggestionEngine {

    private val STOP_WORDS = setOf(
        // English stop words
        "a", "an", "the", "and", "or", "but", "if", "then", "else", "when", "while", "where",
        "of", "in", "on", "at", "to", "for", "with", "by", "from", "up", "about", "into", "over",
        "after", "under", "between", "out", "against", "during", "without", "before", "around",
        "among", "through", "within", "along", "following", "across", "behind", "beyond", "plus",
        "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "having", "do",
        "does", "did", "doing", "will", "would", "shall", "should", "can", "could", "may", "might",
        "must", "ought", "it", "its", "this", "that", "these", "those", "i", "you", "he", "she",
        "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
        "mine", "yours", "hers", "ours", "theirs", "who", "whom", "whose", "which", "what",
        "not", "no", "nor", "only", "own", "same", "so", "than", "too", "very", "just", "also",
        "even", "more", "most", "other", "some", "such", "any", "all", "both", "each", "few",
        "many", "much", "several", "one", "two", "three", "first", "second", "new", "old",
        "however", "therefore", "thus", "hence", "furthermore", "moreover", "nevertheless",
        "although", "though", "because", "since", "unless", "until", "whether", "rather",
        "considered", "example", "e.g", "i.e", "etc", "et", "al", "make", "made", "use", "used",
        "using", "way", "ways", "part", "parts", "form", "forms", "mean", "means", "often",
        // Norwegian / Scandinavian common stop words
        "og", "i", "jeg", "det", "at", "en", "et", "den", "til", "er", "som", "på", "de", "med",
        "han", "av", "ikke", "ikkje", "der", "så", "var", "meg", "seg", "men", "ett", "har", "om",
        "vi", "min", "mitt", "ha", "hadde", "hun", "nå", "over", "da", "ved", "fra", "du", "ut",
        "sin", "dem", "oss", "opp", "man", "kan", "hans", "hvor", "eller", "hva", "skal", "selv",
        "sjøl", "her", "alle", "vil", "bli", "ble", "blitt", "kunne", "inn", "når", "være", "kom",
        "noen", "noe", "ville", "dere", "som", "deres", "kun", "ja", "etter", "ned", "skulle",
        "denne", "for", "deg", "si", "sine", "sitt", "mot", "å", "meget", "hvorfor", "dette",
        "disse", "uten", "hvordan", "ingen", "din", "ditt", "blir", "samme", "hvilken", "hvilke"
    )

    private val DOMAIN_TAG_RULES: List<Pair<String, Set<String>>> = listOf(
        "methodology" to setOf("method", "methods", "methodology", "qualitative", "quantitative", "empirical", "ethnography", "ethnographic", "fieldwork", "interview", "survey", "sampling", "observation"),
        "epistemology" to setOf("epistemology", "epistemological", "knowledge", "truth", "belief", "justification", "rationality", "objectivity", "subjectivity", "paradigm"),
        "research-design" to setOf("hypothesis", "hypotheses", "research", "question", "questions", "variable", "variables", "validity", "reliability", "framework", "analysis", "data"),
        "philosophy" to setOf("philosophy", "philosophical", "ontology", "ontological", "metaphysics", "ethics", "ethical", "moral", "morality", "virtue", "existential", "phenomenology"),
        "sociology" to setOf("social", "society", "sociology", "sociological", "culture", "cultural", "institution", "institutions", "norm", "norms", "class", "hierarchy", "hierarchical", "community"),
        "politics" to setOf("political", "politics", "state", "government", "governance", "power", "authority", "legitimation", "legitimacy", "democracy", "policy", "ideology", "sovereignty"),
        "security-studies" to setOf("terrorism", "terrorist", "violence", "conflict", "war", "warfare", "weapon", "weapons", "nuclear", "biological", "chemical", "radiological", "lethal", "suicide", "bombers"),
        "religion" to setOf("religion", "religious", "sacred", "theology", "theological", "secular", "fundamentalism", "ritual", "spiritual", "faith", "church"),
        "psychology" to setOf("psychology", "psychological", "cognitive", "cognition", "emotion", "behavior", "behaviour", "perception", "memory", "consciousness", "mind", "mental"),
        "history" to setOf("history", "historical", "century", "era", "colonial", "modernity", "modernization", "tradition", "archive", "chronology", "origin", "origins"),
        "economics" to setOf("economic", "economics", "economy", "market", "capital", "capitalism", "labor", "trade", "finance", "financial", "wealth", "value"),
        "technology" to setOf("technology", "technological", "digital", "algorithm", "network", "networked", "software", "system", "systems", "automation", "computing", "artificial"),
        "literature" to setOf("narrative", "metaphor", "rhetoric", "poetics", "discourse", "text", "textual", "author", "reader", "reading", "interpretation", "hermeneutics"),
        "key-definition" to setOf("defined", "definition", "means", "refers", "called", "known", "term", "concept", "distinction", "classified"),
        "argument-claim" to setOf("argues", "argued", "claim", "claims", "evidence", "therefore", "consequently", "demonstrates", "proves", "conclusion", "thesis")
    )

    /**
     * Parses a comma-separated tag string into a clean, deduplicated list of normalized tags.
     */
    fun parseTagsList(rawTags: String): List<String> {
        if (rawTags.isBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        rawTags.split(Regex("[,;\\n]+"))
            .map { normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .forEach { tag ->
                val lower = tag.lowercase(Locale.ROOT)
                if (seen.add(lower)) {
                    result.add(tag)
                }
            }
        return result
    }

    /**
     * Formats a list of tags into a comma-separated string for persistence.
     */
    fun joinTagsList(tags: List<String>): String {
        return parseTagsList(tags.joinToString(", ")).joinToString(", ")
    }

    /**
     * Cleans a single tag string (strips leading `#`, trims whitespace, converts spaces inside a tag to `-`).
     */
    fun normalizeTag(rawTag: String): String {
        val cleaned = rawTag.trim()
            .removePrefix("#")
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}_/\\- ]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        return cleaned
    }

    /**
     * Suggests 3 to 5 distinct, concise Note Titles based on the highlighted text, user notes, and chapter context.
     * These titles are safe and descriptive for Markdown filenames (`[title].md`).
     */
    fun suggestTitles(
        highlightedText: String,
        userNotes: String = "",
        chapterTitle: String = "",
        pageNumber: Int = 1
    ): List<String> {
        val cleanQuote = highlightedText
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '“', '”', '\'', '‘', '’', '«', '»')

        if (cleanQuote.isBlank()) {
            val fallback = if (chapterTitle.isNotBlank()) {
                "$chapterTitle - Page $pageNumber"
            } else {
                "Note - Page $pageNumber"
            }
            return listOf(fallback)
        }

        val suggestions = LinkedHashSet<String>()

        // 1. Key Concept Noun-Phrase / Theme Synthesis (e.g., "Religious Forms of Terrorism & Lethal Means")
        val topKeywords = extractTopKeywords(cleanQuote, maxCount = 4)
        if (topKeywords.size >= 2) {
            val conceptTitle = topKeywords.take(3).joinToString(" ") { capitalizeWord(it) }
            val extendedConcept = if (topKeywords.size >= 4) {
                "${capitalizeWord(topKeywords[0])} ${capitalizeWord(topKeywords[1])} & ${capitalizeWord(topKeywords[2])} ${capitalizeWord(topKeywords[3])}"
            } else {
                "On $conceptTitle"
            }
            addCleanTitleCandidate(suggestions, conceptTitle)
            addCleanTitleCandidate(suggestions, extendedConcept)
        }

        // 2. First Clause / Core Opening Statement (trimmed cleanly at word and clause boundaries)
        val firstClause = cleanQuote
            .split(Regex("[.!?;:—–\\(\\)]"))
            .map { it.trim() }
            .firstOrNull { it.length >= 10 }
            ?: cleanQuote

        val clauseWords = firstClause
            .replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        // Strip leading filler words ("However,", "Furthermore,", "And", "In addition")
        val strippedClauseWords = clauseWords.dropWhile {
            it.lowercase(Locale.ROOT) in setOf("however", "therefore", "thus", "moreover", "furthermore", "and", "but", "so", "also", "yet", "still")
        }.ifEmpty { clauseWords }

        if (strippedClauseWords.isNotEmpty()) {
            val shortOpening = strippedClauseWords.take(6).joinToString(" ")
            addCleanTitleCandidate(suggestions, toTitleCaseHeadline(shortOpening))
        }

        // 3. Definition / Claim Extraction (if quote contains "is", "are", "refers to", "means", "defined as")
        val claimMatch = Regex(
            "\\b([\\p{L}\\s-]{4,32}?)\\s+(?:is|are|was|were|refers to|means|signifies|represents|requires)\\s+([\\p{L}\\s-]{5,32})",
            RegexOption.IGNORE_CASE
        ).find(cleanQuote)
        if (claimMatch != null) {
            val subjectWords = claimMatch.groupValues[1].trim().split(Regex("\\s+"))
                .dropWhile { it.lowercase(Locale.ROOT) in STOP_WORDS }
                .takeLast(3)
            val predicateWords = claimMatch.groupValues[2].trim().split(Regex("\\s+"))
                .filter { it.lowercase(Locale.ROOT) !in STOP_WORDS || it.length > 3 }
                .take(3)
            if (subjectWords.isNotEmpty() && predicateWords.isNotEmpty()) {
                val claimTitle = (subjectWords + predicateWords).joinToString(" ") { capitalizeWord(it) }
                addCleanTitleCandidate(suggestions, claimTitle)
            }
        }

        // 4. Chapter-Contextualized Title (if chapterTitle is meaningful)
        val cleanChapter = chapterTitle
            .replace(Regex("^(Chapter|Section|Part)\\s+\\d+[:.-]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanChapter.isNotBlank() && topKeywords.isNotEmpty()) {
            val focusWord = topKeywords.firstOrNull { !cleanChapter.contains(it, ignoreCase = true) }
                ?: topKeywords.first()
            addCleanTitleCandidate(
                suggestions,
                "${cleanChapter.take(26).trim()} - ${capitalizeWord(focusWord)}"
            )
        }

        // 5. User Notes Reflection Title (if the user also typed notes)
        if (userNotes.isNotBlank()) {
            val noteWords = userNotes
                .replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(6)
            if (noteWords.size >= 2) {
                addCleanTitleCandidate(suggestions, toTitleCaseHeadline(noteWords.joinToString(" ")))
            }
        }

        // Ensure at least one clean fallback
        if (suggestions.isEmpty()) {
            val fallback = cleanQuote.take(40).trim().replace(Regex("[^\\p{L}\\p{N} -]"), "").trim()
            if (fallback.isNotBlank()) {
                suggestions.add(fallback)
            } else {
                suggestions.add("Note - Page $pageNumber")
            }
        }

        return suggestions.take(5).toList()
    }

    /**
     * Suggests relevant PKM/Obsidian tags (kebab-case, without `#`) based on the highlighted text,
     * user notes, chapter title, and document metadata.
     */
    fun suggestTags(
        highlightedText: String,
        userNotes: String = "",
        chapterTitle: String = "",
        documentTitle: String = "",
        existingTags: List<String> = emptyList()
    ): List<String> {
        val combinedText = "$highlightedText $userNotes $chapterTitle"
        val lowerTokens = combinedText
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }

        val tokenSet = lowerTokens.toSet()
        val existingLower = existingTags.map { normalizeTag(it).lowercase(Locale.ROOT) }.toSet()
        val suggested = LinkedHashSet<String>()

        fun addCandidate(tag: String) {
            val norm = normalizeTag(tag).lowercase(Locale.ROOT)
            if (norm.length in 3..28 && norm !in existingLower && norm !in STOP_WORDS) {
                suggested.add(norm)
            }
        }

        // 1. Match domain/thematic rules
        for ((domainTag, triggerWords) in DOMAIN_TAG_RULES) {
            val matchCount = triggerWords.count { it in tokenSet }
            if (matchCount >= 1) {
                addCandidate(domainTag)
            }
        }

        // 2. Detect structural / academic patterns in the quote
        if (Regex("\\(\\d{4}\\)|\\[\\d+\\]|et al\\.", RegexOption.IGNORE_CASE).containsMatchIn(highlightedText)) {
            addCandidate("citation")
        }
        if (highlightedText.contains("?")) {
            addCandidate("research-question")
        }
        if (Regex("\\b(first|second|third|1\\.|2\\.|3\\.)\\b", RegexOption.IGNORE_CASE).containsMatchIn(highlightedText)) {
            addCandidate("taxonomy")
        }
        if (Regex("\\b(vs\\.?|versus|rather than|in contrast|unlike|on the other hand)\\b", RegexOption.IGNORE_CASE).containsMatchIn(highlightedText)) {
            addCandidate("comparison")
        }

        // 3. Extract salient multi-word bigrams or high-information single keywords from the quote
        val topKeywords = extractTopKeywords(highlightedText, maxCount = 6)
        for (kw in topKeywords) {
            addCandidate(kw)
        }

        // 4. Add a chapter-derived tag if chapterTitle has a clear topic word
        if (chapterTitle.isNotBlank()) {
            val chapterKeywords = extractTopKeywords(chapterTitle, maxCount = 2)
                .filter { it !in setOf("chapter", "section", "part", "introduction", "conclusion", "general", "notes") }
            chapterKeywords.firstOrNull()?.let { addCandidate(it) }
        }

        // 5. Fallback general PKM literature tags so the user always has helpful suggestions
        val fallbackPkmTags = listOf("key-concept", "quote", "synthesis", "follow-up", "critique")
        for (fb in fallbackPkmTags) {
            if (suggested.size >= 8) break
            addCandidate(fb)
        }

        return suggested.take(8).toList()
    }

    private fun extractTopKeywords(text: String, maxCount: Int): List<String> {
        val words = text
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 4 && it !in STOP_WORDS }

        if (words.isEmpty()) return emptyList()

        // Score words by frequency + length bonus (academic/concept nouns tend to be longer)
        val freqMap = LinkedHashMap<String, Int>()
        for (w in words) {
            freqMap[w] = (freqMap[w] ?: 0) + 1
        }

        return freqMap.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { entry ->
                    val lengthBonus = (entry.key.length.coerceAtMost(12) - 4)
                    entry.value * 4 + lengthBonus
                }
            )
            .map { it.key }
            .take(maxCount)
    }

    private fun addCleanTitleCandidate(target: MutableSet<String>, raw: String) {
        val cleaned = raw
            .replace(Regex("[/\\\\?%*:|\"<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', ',', '.', ';', ':')
            .take(54)
            .trim()
        if (cleaned.length >= 4 && cleaned.none { it == '\n' }) {
            // Avoid adding near-duplicates that only differ by case
            if (target.none { it.equals(cleaned, ignoreCase = true) }) {
                target.add(cleaned)
            }
        }
    }

    private fun capitalizeWord(word: String): String {
        if (word.isEmpty()) return word
        return word.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    private fun toTitleCaseHeadline(phrase: String): String {
        val minorWords = setOf("a", "an", "the", "and", "or", "but", "of", "in", "on", "at", "to", "for", "with", "by", "from")
        val parts = phrase.trim().split(Regex("\\s+"))
        return parts.mapIndexed { index, w ->
            val lower = w.lowercase(Locale.ROOT)
            if (index > 0 && index < parts.lastIndex && lower in minorWords) {
                lower
            } else {
                capitalizeWord(w)
            }
        }.joinToString(" ")
    }
}
