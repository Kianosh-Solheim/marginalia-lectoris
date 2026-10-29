package com.example.reader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * “Look up details”: fetches a record online from the identifiers already filled in — DOI
 * (Crossref), ISBN (Open Library, then Google Books for what is still missing), ISSN (Crossref
 * journals) — or, with no identifier, searches by title and author so the right match can be
 * picked. Runs only when the user presses the button.
 *
 * The result is never written by itself: [compare] lists what differs from the current details,
 * and the user chooses, field by field, what to keep.
 */
object MetadataLookup {

    /** What a lookup found. [authors] are “Given Family; Given Family”. */
    data class Found(
        val record: BibRecord,
        val authors: String = "",
        val abstract: String = "",
        val sources: List<String> = emptyList()
    )

    /** One search hit to choose from when there is no identifier. */
    data class Candidate(val label: String, val detail: String, val found: Found)

    /** The outcome of pressing the button: a record, or several to choose from. */
    data class Outcome(val found: Found? = null, val candidates: List<Candidate> = emptyList(), val note: String = "")

    /** One line of the comparison. [key] is a [BibField] name, or AUTHOR, ABSTRACT or TYPE. */
    data class Row(val key: String, val label: String, val current: String, val found: String) {
        val same: Boolean get() = sameValue(key, current, found)
        val isNew: Boolean get() = current.isBlank() && found.isNotBlank()
    }

    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private const val UA = "MarginaliaLectoris/1.0 (Android reading app)"

    private fun get(url: String): JSONObject? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            check(resp.isSuccessful) { "The lookup service answered HTTP ${resp.code}." }
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    // ------------------------------------------------------------------------- the button

    /**
     * Uses the identifiers in [rec]; when there are none, or they find nothing, searches by
     * [title] and [author] instead.
     */
    fun run(rec: BibRecord, title: String, author: String): Result<Outcome> = runCatching {
        val hasIds = listOf(BibField.DOI, BibField.ISBN, BibField.ISSN).any { rec[it].isNotBlank() }
        var note = ""
        if (hasIds) {
            val r = byIdentifiers(rec)
            r.getOrNull()?.let { return@runCatching Outcome(found = it) }
            note = r.exceptionOrNull()?.message.orEmpty()
        }
        val q = title.ifBlank { rec[BibField.TITLE] }
        require(q.isNotBlank()) { note.ifBlank { "Add a DOI, ISBN or ISSN, or a title, to look up." } }
        val hits = search(q, author).getOrThrow()
        check(hits.isNotEmpty()) { (if (note.isNotBlank()) "$note " else "") + "No matches for “${q.take(80)}”." }
        Outcome(candidates = hits, note = if (hasIds) "${note.ifBlank { "The identifiers found nothing." }} Matches by title:" else "No DOI, ISBN or ISSN yet. Matches by title:")
    }

    fun byIdentifiers(rec: BibRecord): Result<Found> = runCatching {
        val parts = mutableListOf<Found>()
        val problems = mutableListOf<String>()
        val doi = BibFieldCleaner.clean(BibField.DOI, rec[BibField.DOI])
        if (doi.isNotBlank()) {
            runCatching { byDoi(doi) }.onSuccess { if (it != null) parts += it else problems += "DOI $doi was not found." }
                .onFailure { problems += "DOI: ${it.message}" }
        }
        val isbn = BibFieldCleaner.clean(BibField.ISBN, rec[BibField.ISBN]).ifBlank { parts.firstOrNull()?.record?.get(BibField.ISBN).orEmpty() }
        if (isbn.isNotBlank()) {
            var any = false
            runCatching { openLibrary(isbn) }.onSuccess { it?.let { f -> parts += f; any = true } }.onFailure { problems += "Open Library: ${it.message}" }
            runCatching { googleBooks(isbn) }.onSuccess { it?.let { f -> parts += f; any = true } }.onFailure { problems += "Google Books: ${it.message}" }
            if (!any && rec[BibField.ISBN].isNotBlank()) problems += "ISBN $isbn was not found."
        }
        val issn = BibFieldCleaner.clean(BibField.ISSN, rec[BibField.ISSN])
        if (issn.isNotBlank() && parts.none { it.record[BibField.JOURNAL].isNotBlank() }) {
            runCatching { byIssn(issn) }.onSuccess { if (it != null) parts += it else problems += "ISSN $issn was not found." }
                .onFailure { problems += "ISSN: ${it.message}" }
        }
        check(parts.isNotEmpty()) { problems.joinToString(" ").ifBlank { "Nothing was found." } }
        merge(parts)
    }

    /** Earlier sources win; later ones only fill what is still empty. */
    fun merge(parts: List<Found>): Found {
        var r = BibRecord()
        parts.forEach { p ->
            p.record.fields.forEach { (k, v) -> if (r[k].isBlank() && v.isNotBlank()) r = r.with(k, v) }
            if (r.entryType.isBlank() && p.record.entryType.isNotBlank()) r = r.copy(entryType = p.record.entryType)
        }
        return Found(
            r,
            parts.firstOrNull { it.authors.isNotBlank() }?.authors.orEmpty(),
            parts.firstOrNull { it.abstract.isNotBlank() }?.abstract.orEmpty(),
            parts.flatMap { it.sources }.distinct()
        )
    }

    // ------------------------------------------------------------------------- Crossref

    /** Crossref first; DOIs Crossref does not know (datasets, theses, many reports) are tried at DataCite, as Zotero does. */
    fun byDoi(doi: String): Found? = get("https://api.crossref.org/works/${enc(doi)}")?.optJSONObject("message")?.let { crossrefItem(it) }
        ?: runCatching { dataCite(doi) }.getOrNull()

    fun dataCite(doi: String): Found? {
        val a = get("https://api.datacite.org/dois/${enc(doi)}")?.optJSONObject("data")?.optJSONObject("attributes") ?: return null
        var r = BibRecord().with(BibField.DOI, doi)
        fun put(f: BibField, v: String?) { if (!v.isNullOrBlank()) r = r.with(f, stripTags(v)) }
        put(BibField.TITLE, a.optJSONArray("titles")?.optJSONObject(0)?.optString("title"))
        put(BibField.PUBLISHER, a.opt("publisher").let { if (it is JSONObject) it.optString("name") else it?.toString() })
        put(BibField.YEAR, a.optInt("publicationYear").takeIf { it > 0 }?.toString())
        put(BibField.URL, a.optString("url"))
        put(BibField.LANGUAGE, a.optString("language"))
        val creators = a.optJSONArray("creators")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.map { p ->
            "${p.optString("givenName")} ${p.optString("familyName")}".trim().ifBlank { p.optString("name") } } }.orEmpty().filter { it.isNotBlank() }.joinToString("; ")
        val kind = a.optJSONObject("types")?.optString("resourceTypeGeneral").orEmpty()
        r = r.copy(entryType = when (kind) { "JournalArticle" -> "article"; "Book" -> "book"; "BookChapter" -> "incollection"; "Dissertation" -> "phdthesis"; "Report" -> "techreport"; else -> "misc" })
        val abs = a.optJSONArray("descriptions")?.optJSONObject(0)?.optString("description").orEmpty()
        return Found(r, creators, stripTags(abs), listOf("DataCite"))
    }

    /** PubMed by PMID (NCBI E-utilities). */
    fun byPmid(pmid: String): Found? {
        val root = get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi?db=pubmed&retmode=json&id=${enc(pmid)}") ?: return null
        val m = root.optJSONObject("result")?.optJSONObject(pmid) ?: return null
        var r = BibRecord(entryType = "article")
        fun put(f: BibField, v: String?) { if (!v.isNullOrBlank()) r = r.with(f, v.trim().trimEnd('.')) }
        put(BibField.TITLE, m.optString("title"))
        put(BibField.JOURNAL, m.optString("fulljournalname").ifBlank { m.optString("source") })
        put(BibField.VOLUME, m.optString("volume"))
        put(BibField.ISSUE, m.optString("issue"))
        put(BibField.PAGES, m.optString("pages").replace("-", "–"))
        put(BibField.YEAR, BibFieldCleaner.clean(BibField.YEAR, m.optString("pubdate")))
        put(BibField.ISSN, m.optString("issn").ifBlank { m.optString("essn") })
        m.optJSONArray("articleids")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("idtype") == "doi" }?.optString("value") }?.let { put(BibField.DOI, it) }
        val authors = m.optJSONArray("authors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
            .map { n -> val parts = n.trim().split(' '); if (parts.size >= 2) parts.last() + " " + parts.dropLast(1).joinToString(" ") else n }
            .filter { it.isNotBlank() }.joinToString("; ")
        return Found(r, authors, "", listOf("PubMed"))
    }

    /** arXiv by its identifier (e.g. 2101.01234). */
    fun byArxiv(id: String): Found? {
        val req = Request.Builder().url("https://export.arxiv.org/api/query?id_list=${enc(id)}").header("User-Agent", UA).build()
        val xml = client.newCall(req).execute().use { resp -> if (!resp.isSuccessful) return null; resp.body?.string().orEmpty() }
        val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        fun tag(n: String) = Regex("<$n[^>]*>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val title = tag("title")
        if (title.isBlank() || title == "Error") return null
        var r = BibRecord(entryType = "misc").with(BibField.TITLE, title).with(BibField.PUBLISHER, "arXiv").with(BibField.URL, "https://arxiv.org/abs/$id")
        BibFieldCleaner.clean(BibField.YEAR, tag("published")).takeIf { it.isNotBlank() }?.let { r = r.with(BibField.YEAR, it) }
        Regex("<arxiv:doi[^>]*>(.*?)</arxiv:doi>").find(entry)?.groupValues?.get(1)?.let { r = r.with(BibField.DOI, it.trim()) }
        val authors = Regex("<author>\\s*<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).findAll(entry).map { it.groupValues[1].trim() }.joinToString("; ")
        return Found(r, authors, tag("summary"), listOf("arXiv"))
    }

    /** Any identifier typed or pasted: a DOI (or doi.org link), ISBN, PMID, arXiv ID, or ISSN. */
    fun byIdentifier(raw: String): Found? {
        val s = raw.trim()
        BibFieldCleaner.clean(BibField.DOI, s).takeIf { it.isNotBlank() }?.let { return byDoi(it) }
        Regex("(?:arxiv[:/ ]|abs/)?(\\d{4}\\.\\d{4,5})(?:v\\d+)?", RegexOption.IGNORE_CASE).find(s)?.takeIf { s.contains("arxiv", true) || Regex("^\\d{4}\\.\\d{4,5}(v\\d+)?$").matches(s) }
            ?.let { return byArxiv(it.groupValues[1]) }
        Regex("^(?:pmid[: ]*)?(\\d{5,9})$", RegexOption.IGNORE_CASE).find(s)?.takeIf { s.lowercase(Locale.ROOT).startsWith("pmid") || s.length in 5..8 }?.let { return byPmid(it.groupValues[1]) }
        BibFieldCleaner.clean(BibField.ISBN, s).takeIf { it.isNotBlank() }?.let { isbn ->
            val parts = listOfNotNull(runCatching { openLibrary(isbn) }.getOrNull(), runCatching { googleBooks(isbn) }.getOrNull())
            return if (parts.isEmpty()) null else merge(parts)
        }
        BibFieldCleaner.clean(BibField.ISSN, s).takeIf { it.isNotBlank() }?.let { return byIssn(it) }
        return null
    }

    fun byIssn(issn: String): Found? {
        val m = get("https://api.crossref.org/journals/${enc(issn)}")?.optJSONObject("message") ?: return null
        var r = BibRecord(entryType = "article").with(BibField.ISSN, issn)
        m.optString("title").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.JOURNAL, it) }
        m.optString("publisher").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.PUBLISHER, it) }
        return Found(r, sources = listOf("Crossref (journal)"))
    }

    private fun people(a: JSONArray?): String = if (a == null) "" else (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { "${it.optString("given")} ${it.optString("family")}".trim().ifBlank { it.optString("name").trim() } }
    }.filter { it.isNotBlank() }.joinToString("; ")

    fun crossrefItem(m: JSONObject): Found {
        var r = BibRecord()
        fun put(f: BibField, v: String?) { val s = v?.let { stripTags(it) }.orEmpty(); if (s.isNotBlank()) r = r.with(f, s) }
        val type = m.optString("type")
        put(BibField.TITLE, m.optJSONArray("title")?.optString(0))
        put(BibField.SUBTITLE, m.optJSONArray("subtitle")?.optString(0))
        val container = m.optJSONArray("container-title")?.optString(0)
        when (type) {
            "book-chapter", "book-section", "book-part", "reference-entry" -> put(BibField.BOOKTITLE, container)
            "book", "monograph", "edited-book", "reference-book" -> put(BibField.SERIES, container)
            else -> put(BibField.JOURNAL, container)
        }
        put(BibField.PUBLISHER, m.optString("publisher"))
        put(BibField.PLACE, m.optString("publisher-location"))
        put(BibField.VOLUME, m.optString("volume"))
        put(BibField.ISSUE, m.optString("issue"))
        put(BibField.PAGES, m.optString("page").replace("-", "–"))
        put(BibField.EDITION, m.optString("edition-number"))
        val year = listOf("published-print", "issued", "published", "published-online").firstNotNullOfOrNull { k ->
            m.optJSONObject(k)?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0)?.takeIf { it > 0 }
        }
        year?.let { put(BibField.YEAR, it.toString()) }
        val isbns = m.optJSONArray("ISBN").strings().map { BibFieldCleaner.clean(BibField.ISBN, it) }.filter { it.isNotBlank() }
        put(BibField.ISBN, isbns.firstOrNull { it.length == 13 } ?: isbns.firstOrNull())
        put(BibField.ISSN, m.optJSONArray("ISSN").strings().firstOrNull())
        put(BibField.DOI, m.optString("DOI"))
        put(BibField.LANGUAGE, m.optString("language"))
        put(BibField.EDITOR, people(m.optJSONArray("editor")))
        put(BibField.TRANSLATOR, people(m.optJSONArray("translator")))
        val entry = when (type) {
            "journal-article", "proceedings-article" -> "article"
            "book-chapter", "book-section", "book-part", "reference-entry" -> "incollection"
            "book", "monograph", "edited-book", "reference-book" -> "book"
            "dissertation" -> "phdthesis"
            "report" -> "techreport"
            else -> ""
        }
        val abstract = stripTags(m.optString("abstract")).removePrefix("Abstract").trim()
        return Found(r.copy(entryType = entry), people(m.optJSONArray("author")), abstract, listOf("Crossref"))
    }

    // ------------------------------------------------------------------------- books

    fun openLibrary(isbn: String): Found? {
        val root = get("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data") ?: return null
        val b = root.optJSONObject("ISBN:$isbn") ?: return null
        var r = BibRecord(entryType = "book").with(BibField.ISBN, isbn)
        fun put(f: BibField, v: String?) { if (!v.isNullOrBlank()) r = r.with(f, v.trim()) }
        put(BibField.TITLE, b.optString("title"))
        put(BibField.SUBTITLE, b.optString("subtitle"))
        put(BibField.PUBLISHER, b.optJSONArray("publishers")?.optJSONObject(0)?.optString("name"))
        put(BibField.PLACE, b.optJSONArray("publish_places")?.optJSONObject(0)?.optString("name"))
        put(BibField.YEAR, BibFieldCleaner.clean(BibField.YEAR, b.optString("publish_date")))
        put(BibField.SERIES, b.optJSONArray("series")?.optString(0))
        val authors = b.optJSONArray("authors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.trim() } }
            .orEmpty().filter { it.isNotBlank() }.joinToString("; ")
        return Found(r, authors, "", listOf("Open Library"))
    }

    fun googleBooks(isbn: String): Found? {
        val root = get("https://www.googleapis.com/books/v1/volumes?q=isbn:${enc(isbn)}&maxResults=1") ?: return null
        val vi = root.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo") ?: return null
        return googleVolume(vi).let { f -> if (f.record[BibField.ISBN].isBlank()) f.copy(record = f.record.with(BibField.ISBN, isbn)) else f }
    }

    private fun googleVolume(vi: JSONObject): Found {
        var r = BibRecord(entryType = "book")
        fun put(f: BibField, v: String?) { if (!v.isNullOrBlank()) r = r.with(f, v.trim()) }
        put(BibField.TITLE, vi.optString("title"))
        put(BibField.SUBTITLE, vi.optString("subtitle"))
        put(BibField.PUBLISHER, vi.optString("publisher"))
        put(BibField.YEAR, BibFieldCleaner.clean(BibField.YEAR, vi.optString("publishedDate")))
        put(BibField.LANGUAGE, vi.optString("language"))
        val ids = vi.optJSONArray("industryIdentifiers")
        val isbn13 = ids?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("type") == "ISBN_13" }?.optString("identifier") }
        val isbn10 = ids?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("type") == "ISBN_10" }?.optString("identifier") }
        put(BibField.ISBN, isbn13 ?: isbn10)
        val authors = vi.optJSONArray("authors").strings().joinToString("; ")
        return Found(r, authors, stripTags(vi.optString("description")), listOf("Google Books"))
    }

    // ------------------------------------------------------------------------- title search

    fun search(title: String, author: String): Result<List<Candidate>> = runCatching {
        val family = Regex("[\\p{L}'-]+").findAll(author.split(';', ',', '&').firstOrNull().orEmpty()).lastOrNull()?.value.orEmpty()
        val out = mutableListOf<Candidate>()
        val problems = mutableListOf<String>()
        runCatching {
            val url = "https://api.crossref.org/works?rows=6&query.bibliographic=${enc(title)}" + (if (family.isNotBlank()) "&query.author=${enc(family)}" else "")
            get(url)?.optJSONObject("message")?.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.forEach { out += candidate(crossrefItem(it)) }
            }
        }.onFailure { problems += "Crossref: ${it.message}" }
        runCatching {
            val q = "intitle:${title.take(120)}" + (if (family.isNotBlank()) " inauthor:$family" else "")
            get("https://www.googleapis.com/books/v1/volumes?maxResults=5&q=${enc(q)}")?.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("volumeInfo") }.forEach { out += candidate(googleVolume(it)) }
            }
        }.onFailure { problems += "Google Books: ${it.message}" }
        if (out.isEmpty() && problems.isNotEmpty()) error(problems.joinToString(" "))
        // Best first: titles that match what was typed, then the rest.
        val want = norm(title)
        out.distinctBy { norm(it.label) + "|" + it.found.record[BibField.YEAR] }
            .sortedByDescending { c -> val t = norm(c.label); if (t == want) 3 else if (t.startsWith(want) || want.startsWith(t)) 2 else if (want.split(' ').count { it.length > 3 && t.contains(it) } >= 2) 1 else 0 }
            .take(10)
    }

    private fun candidate(f: Found): Candidate {
        val r = f.record
        val title = listOf(r[BibField.TITLE], r[BibField.SUBTITLE]).filter { it.isNotBlank() }.joinToString(": ")
        val where = listOf(r[BibField.JOURNAL], r[BibField.BOOKTITLE].let { if (it.isNotBlank()) "in $it" else "" }, r[BibField.PUBLISHER]).firstOrNull { it.isNotBlank() }.orEmpty()
        val kind = when (r.entryType) { "article" -> "article"; "incollection" -> "chapter"; "book" -> "book"; else -> "" }
        val detail = listOf(f.authors.replace("; ", ", ").take(80), r[BibField.YEAR], where, kind,
            r[BibField.DOI].let { if (it.isNotBlank()) "DOI $it" else "" }, r[BibField.ISBN].let { if (it.isNotBlank()) "ISBN $it" else "" },
            f.sources.joinToString("/")).filter { it.isNotBlank() }.joinToString(" · ")
        return Candidate(title.ifBlank { "(untitled)" }, detail, f)
    }

    // ------------------------------------------------------------------------- comparison

    /**
     * What the lookup found next to what is filled in now. Only fields the lookup has are listed;
     * rows that already agree are marked [Row.same].
     */
    fun compare(current: BibRecord, currentAuthors: String, currentAbstract: String, fallbackTitle: String, found: Found): List<Row> {
        val rows = mutableListOf<Row>()
        if (found.authors.isNotBlank()) rows += Row("AUTHOR", "Author(s)", currentAuthors, found.authors)
        BibField.entries.filter { it != BibField.CITEKEY }.forEach { f ->
            val v = found.record[f]
            if (v.isBlank()) return@forEach
            val cur = if (f == BibField.TITLE) current[f].ifBlank { fallbackTitle } else current[f]
            rows += Row(f.name, f.label, cur, v)
        }
        if (found.abstract.isNotBlank()) rows += Row("ABSTRACT", "Abstract", currentAbstract, found.abstract)
        if (found.record.entryType.isNotBlank()) rows += Row("TYPE", "Type", current.entryType, found.record.entryType)
        return rows
    }

    fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun isbn13(s: String): String {
        val d = s.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase(Locale.ROOT)
        if (d.length != 10) return d
        val core = "978" + d.take(9)
        val sum = core.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return core + ((10 - sum % 10) % 10)
    }

    fun sameValue(key: String, a: String, b: String): Boolean = when (key) {
        "AUTHOR", "EDITOR", "TRANSLATOR" -> {
            fun fams(s: String) = s.split(';', '&').flatMap { if (it.contains(" and ")) it.split(" and ") else listOf(it) }
                .map { n -> val t = n.trim(); if (t.contains(',')) t.substringBefore(',') else t.substringAfterLast(' ') }
                .map { norm(it) }.filter { it.isNotBlank() }.sorted()
            fams(a) == fams(b)
        }
        "EDITION", "VOLUME", "ISSUE" -> {
            fun n(x: String) = Regex("\\d+").find(x)?.value ?: norm(x)
            n(a) == n(b)
        }
        "ISBN" -> isbn13(a) == isbn13(b)
        "DOI" -> BibFieldCleaner.clean(BibField.DOI, a).lowercase(Locale.ROOT) == BibFieldCleaner.clean(BibField.DOI, b).lowercase(Locale.ROOT)
        "ABSTRACT" -> norm(a).take(200) == norm(b).take(200)
        "LANGUAGE" -> norm(a).take(2) == norm(b).take(2)
        else -> norm(a) == norm(b)
    }

    /** JSON for the web page. */
    fun toJson(f: Found): JSONObject = JSONObject()
        .put("fields", JSONObject().apply { f.record.fields.forEach { (k, v) -> put(k.name, v) } })
        .put("author", f.authors)
        .put("abstract", f.abstract)
        .put("entryType", f.record.entryType)
        .put("sources", JSONArray(f.sources))
}
