package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import java.util.Locale

/**
 * RIS (the reference format used by Zotero, EndNote, Mendeley, library catalogues…): reading a
 * record into a document's bibliographic details, and writing a document's details as RIS.
 */
object Ris {

    /** One RIS record: its type (TY) and every tag with its values in order. */
    data class Entry(val type: String, val tags: Map<String, List<String>>) {
        fun all(vararg keys: String): List<String> = keys.flatMap { tags[it].orEmpty() }.map { it.trim() }.filter { it.isNotBlank() }
        fun first(vararg keys: String): String = all(*keys).firstOrNull().orEmpty()
    }

    /** What a RIS record says about a document. */
    data class Parsed(
        val record: BibRecord,
        val title: String,
        val authors: String,          // "First Last; First Last"
        val abstract: String,
        val keywords: List<String>,
        val category: String          // "BOOK", "ARTICLE" or "" when the type says nothing
    )

    private val line = Regex("^([A-Z][A-Z0-9])\\s{1,2}-\\s?(.*)$")

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var type = ""
        var tags = LinkedHashMap<String, MutableList<String>>()
        var lastKey = ""
        fun flush() {
            if (type.isNotBlank() || tags.isNotEmpty()) out += Entry(type.ifBlank { "GEN" }, tags)
            type = ""; tags = LinkedHashMap(); lastKey = ""
        }
        text.replace("﻿", "").lineSequence().forEach { raw ->
            val l = raw.trimEnd()
            val m = line.find(l)
            if (m == null) {
                // Continuation of a long value (some exporters wrap abstracts).
                if (l.isNotBlank() && lastKey.isNotBlank()) tags[lastKey]?.let { vs -> if (vs.isNotEmpty()) vs[vs.size - 1] = vs.last() + " " + l.trim() }
                return@forEach
            }
            val (key, value) = m.destructured
            when (key) {
                "TY" -> { if (type.isNotBlank() || tags.isNotEmpty()) flush(); type = value.trim().uppercase(Locale.ROOT) }
                "ER" -> flush()
                else -> { tags.getOrPut(key) { mutableListOf() }.add(value.trim()); lastKey = key }
            }
        }
        flush()
        return out.filter { it.tags.isNotEmpty() }
    }

    /** "Shafer-Landau, Russ" → "Russ Shafer-Landau"; "Hume, D." → "D. Hume". */
    fun naturalName(raw: String): String {
        val s = raw.trim().trim(',')
        val parts = s.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return when {
            parts.size >= 2 && parts[1].lowercase(Locale.ROOT) in setOf("jr", "jr.", "sr", "sr.", "ii", "iii") -> "${parts.getOrNull(2).orEmpty()} ${parts[0]} ${parts[1]}".trim()
            parts.size >= 2 -> "${parts.drop(1).joinToString(" ")} ${parts[0]}".trim()
            else -> s
        }.replace(Regex("\\s+"), " ")
    }

    fun toParsed(e: Entry): Parsed {
        val t = e.type
        val isArticleType = t in setOf("JOUR", "EJOUR", "MGZN", "NEWS", "ELEC", "INPR", "ABST", "BLOG", "CPAPER", "CONF")
        val isBookType = t in setOf("BOOK", "EBOOK", "EDBOOK", "CTLG", "ENCYC")
        val isChapter = t in setOf("CHAP", "ECHAP")
        var r = BibRecord()
        fun set(f: BibField, v: String) { val c = v.trim(); if (c.isNotBlank()) r = r.with(f, c) }

        val fullTitle = e.first("TI", "T1", "CT").ifBlank { if (t == "BOOK") e.first("BT") else "" }
        val (main, sub) = splitTitle(fullTitle)
        set(BibField.TITLE, main)
        set(BibField.SUBTITLE, sub)

        val primary = e.all("AU", "A1").map { naturalName(it) }
        val secondary = e.all("A2", "ED").map { naturalName(it) }
        if (t == "EDBOOK") set(BibField.EDITOR, (secondary.ifEmpty { primary }).joinToString("; "))
        else if (secondary.isNotEmpty() && (isChapter || isBookType)) set(BibField.EDITOR, secondary.joinToString("; "))
        set(BibField.TRANSLATOR, e.all("A4").map { naturalName(it) }.joinToString("; "))

        set(BibField.YEAR, BibFieldCleaner.clean(BibField.YEAR, e.first("PY", "Y1", "DA", "Y2")))
        set(BibField.PUBLISHER, e.first("PB"))
        set(BibField.PLACE, e.first("CY", "PP"))
        set(BibField.EDITION, e.first("ET"))
        set(BibField.VOLUME, e.first("VL"))
        set(BibField.ISSUE, e.first("IS", "CP"))
        val sp = e.first("SP"); val ep = e.first("EP")
        set(BibField.PAGES, when {
            sp.isNotBlank() && ep.isNotBlank() -> "$sp–$ep"
            else -> sp.replace("-", "–")
        })
        val container = e.first("T2", "JO", "JF", "JA", "J2", "BT").takeIf { it != fullTitle }.orEmpty()
        if (isChapter) set(BibField.BOOKTITLE, container) else if (!isBookType) set(BibField.JOURNAL, container)
        set(BibField.SERIES, e.first("T3").ifBlank { if (isBookType) e.first("T2") else "" })
        e.all("SN").forEach { sn ->
            val issn = Regex("^\\d{4}-?\\d{3}[\\dXx]$").find(sn.trim())?.value
            if (issn != null) { if (r[BibField.ISSN].isBlank()) set(BibField.ISSN, issn.uppercase(Locale.ROOT)) }
            else BibFieldCleaner.clean(BibField.ISBN, sn).takeIf { it.isNotBlank() && r[BibField.ISBN].isBlank() }?.let { set(BibField.ISBN, it) }
        }
        val doi = BibFieldCleaner.clean(BibField.DOI, e.first("DO", "M3").ifBlank { e.all("UR", "L2").firstOrNull { it.contains("doi.org", true) }.orEmpty() })
        set(BibField.DOI, doi)
        e.all("UR").firstOrNull { !it.contains("doi.org", true) }?.let { set(BibField.URL, it) }
        set(BibField.LANGUAGE, e.first("LA"))
        val entryType = when {
            isArticleType -> "article"
            isChapter -> "incollection"
            isBookType -> "book"
            t == "THES" -> "phdthesis"
            t == "RPRT" -> "techreport"
            t.isNotBlank() && t != "GEN" -> "misc"
            else -> ""
        }
        r = r.copy(entryType = entryType)
        val authors = (if (t == "EDBOOK" && secondary.isEmpty()) primary else primary).joinToString("; ")
        return Parsed(
            record = r,
            title = fullTitle.trim(),
            authors = authors,
            abstract = e.first("AB", "N2").trim(),
            keywords = e.all("KW").flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotBlank() },
            category = when { isArticleType || isChapter -> "ARTICLE"; isBookType -> "BOOK"; else -> "" }
        )
    }

    /** "Main: Sub" or "Main — Sub" → (Main, Sub). */
    fun splitTitle(full: String): Pair<String, String> {
        val s = full.trim()
        val m = Regex("^(.{3,}?)\\s*[:：]\\s+(.{2,})$").find(s) ?: return s to ""
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }

    // ------------------------------------------------------------------ writing

    fun export(document: DocumentEntity, rec: BibRecord): String {
        val sb = StringBuilder()
        fun tag(k: String, v: String) { v.lines().joinToString(" ").trim().takeIf { it.isNotBlank() }?.let { sb.append(k).append("  - ").append(it).append("\r\n") } }
        val type = rec.entryType.ifBlank {
            when {
                rec[BibField.BOOKTITLE].isNotBlank() -> "incollection"
                rec[BibField.JOURNAL].isNotBlank() || document.category.equals("ARTICLE", true) -> "article"
                else -> "book"
            }
        }
        val isEditedBook = type == "book" && rec[BibField.EDITOR].isNotBlank() && Citations.people(document.author).isEmpty()
        tag("TY", when (type) {
            "article" -> "JOUR"; "incollection", "inbook" -> "CHAP"; "phdthesis", "mastersthesis" -> "THES"; "techreport" -> "RPRT"
            "book" -> if (isEditedBook) "EDBOOK" else "BOOK"; else -> "GEN"
        })
        val main = rec[BibField.TITLE].ifBlank { document.title }
        val sub = rec[BibField.SUBTITLE]
        tag("TI", if (sub.isNotBlank() && !main.endsWith(sub, true)) "$main: $sub" else main)
        fun inverted(name: String): String {
            val p = Citations.people(name).firstOrNull() ?: return name
            return if (p.given.isBlank()) p.family else "${p.family}, ${p.given}"
        }
        MarkdownExporter.parseAuthorNamesList(document.author).forEach { tag("AU", inverted(it)) }
        rec[BibField.EDITOR].split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { tag(if (type == "book" && isEditedBook) "A2" else "A2", inverted(it)) }
        rec[BibField.TRANSLATOR].split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { tag("A4", inverted(it)) }
        tag("PY", rec[BibField.YEAR])
        when (type) {
            "article" -> tag("T2", rec[BibField.JOURNAL])
            "incollection", "inbook" -> tag("T2", rec[BibField.BOOKTITLE])
        }
        tag("T3", rec[BibField.SERIES])
        tag("VL", rec[BibField.VOLUME])
        tag("IS", rec[BibField.ISSUE])
        val pages = rec[BibField.PAGES].split(Regex("\\s*[–—-]\\s*"))
        tag("SP", pages.getOrNull(0).orEmpty())
        tag("EP", pages.getOrNull(1).orEmpty())
        tag("ET", rec[BibField.EDITION])
        tag("PB", rec[BibField.PUBLISHER])
        tag("CY", rec[BibField.PLACE])
        tag("SN", rec[BibField.ISBN].ifBlank { rec[BibField.ISSN] })
        val doi = BibFieldCleaner.clean(BibField.DOI, rec[BibField.DOI]).ifBlank { rec[BibField.DOI] }
        tag("DO", doi)
        tag("UR", rec[BibField.URL].ifBlank { doi.takeIf { it.isNotBlank() }?.let { "https://doi.org/$it" }.orEmpty() })
        tag("LA", rec[BibField.LANGUAGE])
        tag("AB", document.summary)
        NoteSuggestionEngine.parseTagsList(document.tags).forEach { tag("KW", it) }
        sb.append("ER  - \r\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ applying

    data class Applied(val document: DocumentEntity, val filled: Int)

    /**
     * Fills a document's details from RIS text: empty fields only, unless [overwrite]. Tags are
     * added to the existing ones. Returns the updated document and how many fields were filled.
     */
    suspend fun apply(context: Context, document: DocumentEntity, text: String, overwrite: Boolean): Applied {
        val entry = parse(text).firstOrNull() ?: throw IllegalArgumentException("No RIS record found (it should start with “TY  - ”).")
        val p = toParsed(entry)
        BibStore.init(context)
        var rec = BibStore.get(document.id)
        var filled = 0
        p.record.fields.forEach { (k, v) -> if (overwrite || rec[k].isBlank()) { if (rec[k] != v) filled++; rec = rec.with(k, v) } }
        if (p.record.entryType.isNotBlank() && (overwrite || rec.entryType.isBlank())) rec = rec.copy(entryType = p.record.entryType)
        BibStore.put(document.id, rec)

        val authorMissing = Citations.people(document.author).isEmpty() || document.author.equals("Unknown Author", true)
        val newAuthor = if (p.authors.isNotBlank() && (overwrite || authorMissing)) { filled++; p.authors.replace("; ", ", ") } else document.author
        val newSummary = if (p.abstract.isNotBlank() && (overwrite || document.summary.isBlank())) { filled++; p.abstract } else document.summary
        val oldTags = NoteSuggestionEngine.parseTagsList(document.tags)
        val newTags = NoteSuggestionEngine.joinTagsList(oldTags + p.keywords.filter { k -> oldTags.none { it.equals(k, true) } })
        // A title still taken from the file name is replaced; a title the reader typed is kept unless overwriting.
        val fileStem = java.io.File(document.filePath).nameWithoutExtension
        val titleLooksAuto = document.title.equals(fileStem, true) || document.title.contains(" -- ") || document.title.contains('_')
        val newTitle = if (p.title.isNotBlank() && (overwrite || titleLooksAuto) && p.title != document.title) { filled++; p.title } else document.title

        val updated = if (FolderBooks.isFolder(document)) {
            FolderBooks.updateInfo(context, document.id, newTitle, rec[BibField.EDITOR].ifBlank { EditedVolumes.editors(document) }, newTags, newSummary)
            com.example.data.local.AppDatabase.getInstance(context).documentDao().getDocumentById(document.id) ?: document
        } else {
            com.example.server.LibraryOps(context).updateDocumentInfo(
                document = document, newTitle = newTitle, newAuthor = newAuthor, newTags = newTags, newSummary = newSummary
            )
        }
        return Applied(updated, filled)
    }
}
