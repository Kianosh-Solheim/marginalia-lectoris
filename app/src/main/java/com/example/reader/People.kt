package com.example.reader

import java.util.Locale

/** Lists of people (authors, editors, translators) stored as one text: "Name One; Name Two". */
object People {
    /** Splits a stored or typed list: at ; or new lines; a single entry also at "and", "&", "og", or at commas between full names. */
    fun split(raw: String): List<String> {
        val s = raw.trim()
        if (s.isBlank()) return emptyList()
        var parts = s.split(Regex("\\s*[;\\n]\\s*"))
        if (parts.size == 1) {
            if (Regex("\\s(?:and|&|og)\\s", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
                parts = s.split(Regex("\\s*,?\\s+(?:and|og)\\s+|\\s*&\\s*", RegexOption.IGNORE_CASE))
            } else if (s.contains(',')) {
                val c = s.split(Regex("\\s*,\\s*"))
                if (c.size > 2 || c.all { it.trim().contains(' ') }) parts = c
            }
        }
        return parts.map { it.trim().trim(',', '.').trim() }.filter { it.isNotBlank() }
    }

    fun join(names: List<String>): String = names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.joinToString("; ")

    /** [existing] with [more] added at the end (names already there are not repeated). */
    fun add(existing: String, more: String): String = join(split(existing) + split(more))
}
