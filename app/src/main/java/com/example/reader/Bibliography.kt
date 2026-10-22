package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Bibliographic fields that can be set from a selection ("Set as …") or edited by hand. */
enum class BibField(val label: String, val bibtexKey: String) {
    TITLE("Title", "title"),
    SUBTITLE("Subtitle", "subtitle"),
    YEAR("Year / date", "year"),
    DOI("DOI", "doi"),
    ISBN("ISBN", "isbn"),
    ISSN("ISSN", "issn"),
    PUBLISHER("Publisher", "publisher"),
    PLACE("Place of publication", "address"),
    EDITION("Edition", "edition"),
    EDITOR("Editor(s) of the book", "editor"),
    TRANSLATOR("Translator", "translator"),
    JOURNAL("Journal / container", "journal"),
    BOOKTITLE("Book title (for a chapter)", "booktitle"),
    VOLUME("Volume", "volume"),
    ISSUE("Issue / number", "number"),
    PAGES("Pages (range)", "pages"),
    SERIES("Series", "series"),
    URL("URL", "url"),
    LANGUAGE("Language", "language"),
    CITEKEY("Citation key", "")
}

/** Bibliographic record for one document. Author and abstract stay on [DocumentEntity]. */
data class BibRecord(val fields: Map<BibField, String> = emptyMap(), val entryType: String = "") {
    operator fun get(f: BibField): String = fields[f].orEmpty().trim()
    fun with(f: BibField, value: String): BibRecord =
        copy(fields = fields.toMutableMap().apply { if (value.isBlank()) remove(f) else put(f, value.trim()) })
}

/**
 * Stores bibliographic records as JSON in the app's files folder (included in backups). Kept outside
 * the Room database so no schema migration is needed.
 */
object BibStore {
    @Volatile private var file: File? = null
    @Volatile var appContext: Context? = null
        private set
    private val cache = HashMap<Long, BibRecord>()
    /** Records computed on the fly (chapters of edited volumes); never written to disk. */
    private val transient = HashMap<Long, BibRecord>()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (file == null) file = File(context.applicationContext.filesDir, "bibliography.json")
        load()
    }

    @Synchronized private fun load() {
        if (loaded) return
        val f = file ?: return
        loaded = true
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            root.keys().forEach { id ->
                val o = root.getJSONObject(id)
                val map = HashMap<BibField, String>()
                BibField.entries.forEach { bf -> o.optString(bf.name).takeIf { it.isNotBlank() }?.let { map[bf] = it } }
                cache[id.toLong()] = BibRecord(map, o.optString("_type"))
            }
        }
    }

    /** Forces a reload (after a backup restore). */
    @Synchronized fun reload() { cache.clear(); loaded = false; load() }

    @Synchronized fun get(documentId: Long): BibRecord { load(); return transient[documentId] ?: cache[documentId] ?: BibRecord() }

    @Synchronized fun putTransient(documentId: Long, record: BibRecord) { transient[documentId] = record }

    @Synchronized fun put(documentId: Long, record: BibRecord) {
        load()
        cache[documentId] = record
        val f = file ?: return
        val root = JSONObject()
        cache.filterKeys { it >= 0 }.forEach { (id, r) ->
            val o = JSONObject()
            r.fields.forEach { (k, v) -> o.put(k.name, v) }
            if (r.entryType.isNotBlank()) o.put("_type", r.entryType)
            root.put(id.toString(), o)
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(f)) { f.writeText(root.toString(2)); tmp.delete() }
    }

    fun set(documentId: Long, field: BibField, value: String) = put(documentId, get(documentId).with(field, value))

    /** Fills empty fields from the EPUB package metadata (ISBN, DOI, date, publisher, language) once. */
    fun seedFromFile(document: DocumentEntity) {
        val current = get(document.id)
        if (current.fields.isNotEmpty()) return
        val src = File(document.filePath)
        if (!src.isFile || !src.extension.equals("epub", true)) return
        val opf = runCatching {
            ZipFile(src).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".opf", true) } ?: return@use null
                zip.getInputStream(entry).bufferedReader().readText()
            }
        }.getOrNull() ?: return
        fun tag(name: String) = Regex("<(?:dc:)?$name\\b[^>]*>([^<]+)</(?:dc:)?$name>", RegexOption.IGNORE_CASE)
            .findAll(opf).map { it.groupValues[1].trim() }.toList()
        var r = current
        tag("identifier").forEach { id ->
            BibFieldCleaner.clean(BibField.DOI, id).takeIf { it.isNotBlank() && r[BibField.DOI].isBlank() }?.let { r = r.with(BibField.DOI, it) }
            BibFieldCleaner.clean(BibField.ISBN, id).takeIf { it.isNotBlank() && r[BibField.ISBN].isBlank() }?.let { r = r.with(BibField.ISBN, it) }
        }
        tag("date").firstOrNull()?.let { BibFieldCleaner.clean(BibField.YEAR, it) }?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.YEAR, it) }
        tag("publisher").firstOrNull()?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.PUBLISHER, it) }
        tag("language").firstOrNull()?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.LANGUAGE, it) }
        if (r != current) put(document.id, r)
    }
}

/** Turns a raw text selection into a clean value for a given field. */
object BibFieldCleaner {
    fun clean(field: BibField, raw: String): String {
        val s = raw.replace(Regex("\\s+"), " ").trim()
        return when (field) {
            BibField.DOI -> Regex("10\\.\\d{4,9}/[^\\s\"<>]+", RegexOption.IGNORE_CASE).find(s)?.value?.trimEnd('.', ',', ';', ')').orEmpty()
            BibField.ISBN -> {
                val m = Regex("(97[89][- ]?)?\\d[\\d -]{8,15}[\\dXx]").find(s)?.value.orEmpty()
                val digits = m.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase(Locale.ROOT)
                if (digits.length == 10 || digits.length == 13) digits else ""
            }
            BibField.ISSN -> Regex("\\d{4}-?\\d{3}[\\dXx]").find(s)?.value?.uppercase(Locale.ROOT).orEmpty()
            BibField.YEAR -> Regex("(1[5-9]\\d{2}|20\\d{2})").find(s)?.value.orEmpty()
            BibField.URL -> Regex("https?://\\S+").find(s)?.value?.trimEnd('.', ',', ')').orEmpty()
            BibField.PAGES -> Regex("\\d+\\s*[–-]\\s*\\d+").find(s)?.value?.replace(Regex("\\s*[–-]\\s*"), "–") ?: s.filter { it.isDigit() }
            BibField.VOLUME, BibField.ISSUE, BibField.EDITION -> Regex("\\d+").find(s)?.value ?: s
            else -> s.trim('"', '“', '”', '.', ',', ';', ':')
        }
    }
}

enum class CitationStyle(val label: String) {
    CHICAGO_AUTHOR_DATE("Chicago author-date"),
    APA7("APA 7"),
    CHICAGO_NOTES("Chicago notes"),
    HARVARD("Harvard"),
    MLA9("MLA 9")
}

/** Formats in-text citations, reference-list entries and BibTeX for a document. */
object Citations {
    private const val PREFS = "citation_prefs"

    fun defaultStyle(context: Context): CitationStyle =
        runCatching { CitationStyle.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("style", null)!!) }
            .getOrDefault(CitationStyle.CHICAGO_AUTHOR_DATE)

    /** Default style without a Context (falls back to Chicago author-date before the store is initialised). */
    fun defaultStyle(): CitationStyle = BibStore.appContext?.let { defaultStyle(it) } ?: CitationStyle.CHICAGO_AUTHOR_DATE

    fun setDefaultStyle(context: Context, style: CitationStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("style", style.name).apply()
    }

    data class Person(val given: String, val family: String) {
        val initials: String get() = given.split(Regex("[\\s-]+")).filter { it.isNotBlank() }.joinToString(" ") { "${it.first()}." }
    }

    fun people(raw: String): List<Person> = MarkdownExporter.parseAuthorNamesList(raw).map { name ->
        val parts = name.trim().split(Regex("\\s+"))
        if (parts.size == 1) Person("", parts[0]) else Person(parts.dropLast(1).joinToString(" "), parts.last())
    }

    fun year(document: DocumentEntity, rec: BibRecord): String =
        rec[BibField.YEAR].ifBlank { runCatching { MarkdownExporter.extractPublishedYear(document) }.getOrDefault("") }.ifBlank { "n.d." }

    private fun title(document: DocumentEntity, rec: BibRecord): String {
        val t = rec[BibField.TITLE].ifBlank { document.title }.trim()
        val sub = rec[BibField.SUBTITLE]
        return if (sub.isNotBlank() && !t.endsWith(sub, ignoreCase = true)) "$t: $sub" else t
    }

    private fun isChapter(rec: BibRecord) = rec.entryType == "incollection" || rec.entryType == "inbook" || rec[BibField.BOOKTITLE].isNotBlank()

    private fun isArticle(document: DocumentEntity, rec: BibRecord) =
        !isChapter(rec) && (rec[BibField.JOURNAL].isNotBlank() || document.category.equals("ARTICLE", true))

    private fun natural(ps: List<Person>): String = when (ps.size) {
        0 -> ""
        1 -> "${ps[0].given} ${ps[0].family}".trim()
        2 -> "${ps[0].given} ${ps[0].family} and ${ps[1].given} ${ps[1].family}".replace(Regex("\\s+"), " ").trim()
        else -> ps.dropLast(1).joinToString(", ") { "${it.given} ${it.family}".trim() } + ", and ${ps.last().given} ${ps.last().family}".trimEnd()
    }

    /** Chicago-style shortened page range: 129–80, 101–8, 1100–1123. */
    private fun chicagoRange(pages: String): String {
        val m = Regex("^(\\d+)\\s*[–-]\\s*(\\d+)$").matchEntire(pages.trim()) ?: return pages
        val a = m.groupValues[1]; val b = m.groupValues[2]
        if (a.length != b.length || a.toInt() < 100 || a.toInt() % 100 == 0) return "$a–$b"
        var i = 0
        while (i < a.length - 2 && a[i] == b[i]) i++
        return "$a–${b.substring(i).trimStart('0').ifBlank { b.takeLast(1) }}"
    }

    /** Reference entry for a chapter in an edited book. */
    private fun chapterReference(style: CitationStyle, document: DocumentEntity, rec: BibRecord): String {
        val ps = people(document.author)
        val eds = people(rec[BibField.EDITOR])
        val y = year(document, rec)
        val t = title(document, rec)
        val book = rec[BibField.BOOKTITLE]
        val pages = rec[BibField.PAGES]
        val publisher = rec[BibField.PUBLISHER]
        val place = rec[BibField.PLACE]
        val link = doiOrUrl(rec)
        val pub = listOf(place, publisher).filter { it.isNotBlank() }.joinToString(": ")
        fun end(s: String) = if (s.isBlank() || s.endsWith('.')) s else "$s."
        val edNatural = natural(eds)
        return when (style) {
            CitationStyle.CHICAGO_AUTHOR_DATE -> listOf(
                end(namesAuthorDate(ps, invertFirst = true)), "$y.", "“$t.”",
                "In *$book*" + (if (edNatural.isNotBlank()) ", edited by $edNatural" else "") + (if (pages.isNotBlank()) ", ${chicagoRange(pages)}." else "."),
                if (pub.isNotBlank()) "$pub." else "", link
            )
            CitationStyle.CHICAGO_NOTES -> listOf(
                end(namesAuthorDate(ps, invertFirst = true)), "“$t.”",
                "In *$book*" + (if (edNatural.isNotBlank()) ", edited by $edNatural" else "") + (if (pages.isNotBlank()) ", ${chicagoRange(pages)}." else "."),
                listOf(pub, y).filter { it.isNotBlank() }.joinToString(", ") + ".", link
            )
            CitationStyle.APA7 -> {
                val names = when {
                    ps.isEmpty() -> ""
                    ps.size == 1 -> "${ps[0].family}, ${ps[0].initials}".trimEnd(',', ' ')
                    else -> ps.dropLast(1).joinToString(", ") { "${it.family}, ${it.initials}" } + ", & ${ps.last().family}, ${ps.last().initials}"
                }
                val edApa = if (eds.isEmpty()) "" else eds.joinToString(if (eds.size == 2) " & " else ", ") { "${it.initials} ${it.family}".trim() } +
                    if (eds.size > 1) " (Eds.), " else " (Ed.), "
                listOf(names, "($y).", "$t.", "In $edApa*$book*" + (if (pages.isNotBlank()) " (pp. ${pages.replace("-", "–")})." else "."),
                    if (publisher.isNotBlank()) "$publisher." else "", link)
            }
            CitationStyle.HARVARD -> {
                val edH = if (eds.isEmpty()) "" else eds.joinToString(" and ") { inv(Person(it.initials, it.family)) } + if (eds.size > 1) " (eds) " else " (ed.) "
                val namesH = ps.joinToString(" and ") { inv(Person(it.initials, it.family)) }
                listOf(namesH, "($y)", "‘$t’,", "in $edH*$book*.", if (pub.isNotBlank()) "$pub," else "",
                    if (pages.isNotBlank()) "pp. ${pages.replace("-", "–")}." else "", link)
            }
            CitationStyle.MLA9 -> {
                val names = when {
                    ps.isEmpty() -> ""
                    ps.size == 1 -> inv(ps[0])
                    ps.size == 2 -> "${inv(ps[0])}, and ${ps[1].given} ${ps[1].family}"
                    else -> "${inv(ps[0])}, et al"
                }
                listOf(end(names), "“$t.”", "*$book*,", if (edNatural.isNotBlank()) "edited by $edNatural," else "",
                    if (publisher.isNotBlank()) "$publisher," else "", if (pages.isNotBlank()) "$y, pp. ${pages.replace("-", "–")}." else "$y.")
            }
        }.filter { it.isNotBlank() && it != "." }.joinToString(" ").replace(Regex("\\s+"), " ").replace(" .", ".").trim()
    }

    private fun doiOrUrl(rec: BibRecord): String =
        rec[BibField.DOI].takeIf { it.isNotBlank() }?.let { d -> "https://doi.org/" + d.replace(Regex("^(?:https?://)?(?:dx\\.)?doi\\.org/|^doi:\\s*", RegexOption.IGNORE_CASE), "") } ?: rec[BibField.URL]

    private fun namesAuthorDate(ps: List<Person>, invertFirst: Boolean, andWord: String = "and"): String = when {
        ps.isEmpty() -> ""
        ps.size == 1 -> if (invertFirst) inv(ps[0]) else "${ps[0].given} ${ps[0].family}".trim()
        else -> {
            val first = if (invertFirst) inv(ps[0]) else "${ps[0].given} ${ps[0].family}".trim()
            val rest = ps.drop(1).map { "${it.given} ${it.family}".trim() }
            if (ps.size == 2) (if (invertFirst) "$first, $andWord ${rest[0]}" else "$first $andWord ${rest[0]}") else (listOf(first) + rest.dropLast(1)).joinToString(", ") + ", $andWord ${rest.last()}"
        }
    }

    private fun inv(p: Person) = if (p.given.isBlank()) p.family else "${p.family}, ${p.given}"

    /** In-text citation, e.g. (Rawls 1971, 52) for Chicago author-date. [page] may be blank. */
    fun inText(style: CitationStyle, document: DocumentEntity, rec: BibRecord, page: String): String {
        val ps = people(document.author)
        val y = year(document, rec)
        val fam = when {
            ps.isEmpty() -> title(document, rec).take(40)
            ps.size == 1 -> ps[0].family
            ps.size == 2 -> "${ps[0].family} ${if (style == CitationStyle.APA7) "&" else "and"} ${ps[1].family}"
            else -> "${ps[0].family} et al."
        }
        val pg = page.trim()
        return when (style) {
            CitationStyle.CHICAGO_AUTHOR_DATE -> if (pg.isNotBlank()) "($fam $y, $pg)" else "($fam $y)"
            CitationStyle.APA7 -> if (pg.isNotBlank()) "($fam, $y, ${if (pg.contains('–') || pg.contains('-')) "pp." else "p."} $pg)" else "($fam, $y)"
            CitationStyle.HARVARD -> if (pg.isNotBlank()) "($fam $y, p. $pg)" else "($fam $y)"
            CitationStyle.MLA9 -> if (pg.isNotBlank()) "($fam $pg)" else "($fam)"
            CitationStyle.CHICAGO_NOTES -> note(document, rec, pg)
        }
    }

    /** Full Chicago footnote. */
    private fun note(document: DocumentEntity, rec: BibRecord, page: String): String {
        val ps = people(document.author)
        val names = namesAuthorDate(ps, invertFirst = false)
        val y = year(document, rec)
        val t = title(document, rec)
        val pg = if (page.isNotBlank()) ", $page" else ""
        if (isChapter(rec)) {
            val eds = natural(people(rec[BibField.EDITOR]))
            val pubParts = listOf(rec[BibField.PLACE], rec[BibField.PUBLISHER]).filter { it.isNotBlank() }.joinToString(": ")
            val pub = listOf(pubParts, y).filter { it.isNotBlank() }.joinToString(", ")
            return "${if (names.isNotBlank()) "$names, " else ""}“$t,” in *${rec[BibField.BOOKTITLE]}*" +
                (if (eds.isNotBlank()) ", ed. $eds" else "") + " ($pub)$pg."
        }
        return if (isArticle(document, rec)) {
            val vol = rec[BibField.VOLUME].let { if (it.isNotBlank()) " $it" else "" }
            val iss = rec[BibField.ISSUE].let { if (it.isNotBlank()) ", no. $it" else "" }
            "${if (names.isNotBlank()) "$names, " else ""}“$t,” ${rec[BibField.JOURNAL]}$vol$iss ($y)${if (page.isNotBlank()) ": $page" else ""}."
        } else {
            val pubParts = listOf(rec[BibField.PLACE], rec[BibField.PUBLISHER]).filter { it.isNotBlank() }.joinToString(": ")
            val pub = listOf(pubParts, y).filter { it.isNotBlank() }.joinToString(", ")
            "${if (names.isNotBlank()) "$names, " else ""}$t ($pub)$pg."
        }
    }

    /** Reference-list / bibliography entry. Titles are wrapped in *asterisks* for italics. */
    fun reference(style: CitationStyle, document: DocumentEntity, rec: BibRecord): String {
        if (isChapter(rec)) return chapterReference(style, document, rec)
        val ps = people(document.author)
        val y = year(document, rec)
        val t = title(document, rec)
        val link = doiOrUrl(rec)
        val article = isArticle(document, rec)
        val journal = rec[BibField.JOURNAL]
        val vol = rec[BibField.VOLUME]
        val iss = rec[BibField.ISSUE]
        val pages = rec[BibField.PAGES]
        val publisher = rec[BibField.PUBLISHER]
        val place = rec[BibField.PLACE]
        val edition = rec[BibField.EDITION].let { if (it.isNotBlank()) "${ordinal(it)} ed." else "" }
        fun end(s: String) = if (s.endsWith('.')) s else "$s."
        return when (style) {
            CitationStyle.CHICAGO_AUTHOR_DATE -> {
                val names = namesAuthorDate(ps, invertFirst = true)
                if (article) listOf(
                    end(names), "$y.", "“$t.”", "*$journal*" + (if (vol.isNotBlank()) " $vol" else "") +
                        (if (iss.isNotBlank()) " ($iss)" else "") + (if (pages.isNotBlank()) ": $pages." else "."),
                    link
                ).filter { it.isNotBlank() && it != "." }.joinToString(" ").trim()
                else listOf(
                    end(names), "$y.", "*$t*.", edition,
                    listOf(place, publisher).filter { it.isNotBlank() }.joinToString(": ").let { if (it.isNotBlank()) "$it." else "" },
                    link
                ).filter { it.isNotBlank() }.joinToString(" ")
            }
            CitationStyle.APA7 -> {
                val names = when {
                    ps.isEmpty() -> ""
                    ps.size == 1 -> "${ps[0].family}, ${ps[0].initials}".trimEnd(',', ' ')
                    else -> ps.dropLast(1).joinToString(", ") { "${it.family}, ${it.initials}" } + ", & ${ps.last().family}, ${ps.last().initials}"
                }
                if (article) listOf(
                    names, "($y).", "$t.", "*$journal*" + (if (vol.isNotBlank()) ", *$vol*" else "") +
                        (if (iss.isNotBlank()) "($iss)" else "") + (if (pages.isNotBlank()) ", $pages." else "."),
                    link
                ).filter { it.isNotBlank() }.joinToString(" ")
                else listOf(names, "($y).", "*$t*" + (if (edition.isNotBlank()) " ($edition)" else "") + ".", if (publisher.isNotBlank()) "$publisher." else "", link)
                    .filter { it.isNotBlank() }.joinToString(" ")
            }
            CitationStyle.HARVARD -> {
                val names = namesAuthorDate(ps, invertFirst = true)
                if (article) "${end(names)} ($y) ‘$t’, *$journal*" + (if (vol.isNotBlank()) ", $vol" else "") + (if (iss.isNotBlank()) "($iss)" else "") +
                    (if (pages.isNotBlank()) ", pp. $pages" else "") + "." + (if (link.isNotBlank()) " $link" else "")
                else "${end(names)} ($y) *$t*." + (if (edition.isNotBlank()) " $edition" else "") +
                    listOf(place, publisher).filter { it.isNotBlank() }.joinToString(": ").let { if (it.isNotBlank()) " $it." else "" }
            }
            CitationStyle.MLA9 -> {
                val names = when {
                    ps.isEmpty() -> ""
                    ps.size == 1 -> inv(ps[0])
                    ps.size == 2 -> "${inv(ps[0])}, and ${ps[1].given} ${ps[1].family}"
                    else -> "${inv(ps[0])}, et al"
                }
                if (article) listOf(end(names), "“$t.”", "*$journal*,", (if (vol.isNotBlank()) "vol. $vol," else ""), (if (iss.isNotBlank()) "no. $iss," else ""), "$y,", (if (pages.isNotBlank()) "pp. $pages." else ""))
                    .filter { it.isNotBlank() }.joinToString(" ").trimEnd(',') + (if (link.isNotBlank()) " $link" else "")
                else listOf(end(names), "*$t*.", if (publisher.isNotBlank()) "$publisher," else "", "$y.").filter { it.isNotBlank() }.joinToString(" ")
            }
            CitationStyle.CHICAGO_NOTES -> {
                // Chicago bibliography (notes-bibliography system).
                val names = namesAuthorDate(ps, invertFirst = true)
                if (article) "${end(names)} “$t.” *$journal*" + (if (vol.isNotBlank()) " $vol" else "") + (if (iss.isNotBlank()) ", no. $iss" else "") +
                    " ($y)" + (if (pages.isNotBlank()) ": $pages" else "") + "." + (if (link.isNotBlank()) " $link" else "")
                else "${end(names)} *$t*." + (if (edition.isNotBlank()) " $edition" else "") +
                    listOf(place, publisher).filter { it.isNotBlank() }.joinToString(": ").let { if (it.isNotBlank()) " $it, $y." else " $y." }
            }
        }.replace(Regex("\\s+"), " ").replace(" .", ".").trim()
    }

    private fun ordinal(n: String): String {
        val i = n.toIntOrNull() ?: return n
        val suf = if (i % 100 in 11..13) "th" else when (i % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$i$suf"
    }

    /** Stable citation key: familyYearFirstword, e.g. rawls1971theory. */
    fun citeKey(document: DocumentEntity, rec: BibRecord): String {
        rec[BibField.CITEKEY].takeIf { it.isNotBlank() }?.let { return it }
        val fam = people(document.author).firstOrNull()?.family.orEmpty()
        val y = year(document, rec).takeIf { it != "n.d." }.orEmpty()
        val stop = setOf("the", "a", "an", "on", "of", "en", "et", "ei", "det", "den", "om")
        val word = title(document, rec).split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.isNotBlank() && it.lowercase() !in stop }.orEmpty()
        fun ascii(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace("ø", "o").replace("Ø", "O").replace("æ", "ae").replace("Æ", "Ae")
            .replace("å", "a").replace(Regex("[^A-Za-z0-9]"), "")
        return (ascii(fam).lowercase() + y + ascii(word).lowercase()).ifBlank { "doc${document.id}" }
    }

    /** BibTeX entry for a document. */
    fun bibtex(document: DocumentEntity, rec: BibRecord): String {
        val article = isArticle(document, rec)
        val type = rec.entryType.ifBlank { if (isChapter(rec)) "incollection" else if (article) "article" else "book" }
        val authors = people(document.author).joinToString(" and ") { if (it.given.isBlank()) it.family else "${it.family}, ${it.given}" }
        val entries = LinkedHashMap<String, String>()
        if (authors.isNotBlank()) entries["author"] = authors
        entries["title"] = rec[BibField.TITLE].ifBlank { document.title }
        BibField.entries.filter { it != BibField.TITLE && it != BibField.CITEKEY && it.bibtexKey.isNotBlank() }.forEach { f ->
            val v = rec[f]
            if (v.isNotBlank()) entries[f.bibtexKey] = when (f) {
                BibField.EDITOR, BibField.TRANSLATOR -> people(v).joinToString(" and ") { if (it.given.isBlank()) it.family else "${it.family}, ${it.given}" }.ifBlank { v }
                BibField.PAGES -> v.replace(Regex("\\s*[–—-]+\\s*"), "--")
                else -> v
            }
        }
        if (!entries.containsKey("year")) year(document, rec).takeIf { it != "n.d." }?.let { entries["year"] = it }
        if (document.summary.isNotBlank()) entries["abstract"] = document.summary.trim()
        fun esc(v: String) = v.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("&", "\\&").replace("%", "\\%")
        val body = entries.entries.joinToString(",\n") { (k, v) ->
            val value = if (k == "url" || k == "doi") v else esc(v)
            "  $k = {${value}}"
        }
        return "@$type{${citeKey(document, rec)},\n$body\n}\n"
    }

    /** The printed page if known, otherwise a PDF page number, otherwise blank. */
    fun pageFor(printedPage: String, pageEquivalent: String, paragraphCitation: String, pdfPage: Int?, isPdf: Boolean): String {
        val cleaned = listOf(printedPage, pageEquivalent).map { it.trim().removePrefix("pp.").removePrefix("p.").trim() }.firstOrNull { it.isNotBlank() }
        if (!cleaned.isNullOrBlank()) return cleaned
        Regex("pp?\\.\\s*([\\divxlcdm]+(?:\\s*[–-]\\s*[\\divxlcdm]+)?)", RegexOption.IGNORE_CASE).find(paragraphCitation)?.groupValues?.get(1)?.let { return it }
        return if (isPdf && pdfPage != null && pdfPage > 0) pdfPage.toString() else ""
    }
}

/** Looks up metadata online: Crossref for DOIs, Open Library for ISBNs. Runs only when the user taps "Look up". */
object BibLookup {
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    fun byDoi(doi: String): Result<Pair<BibRecord, String>> = runCatching {
        val req = Request.Builder().url("https://api.crossref.org/works/${android.net.Uri.encode(doi)}")
            .header("User-Agent", "MarginaliaLectoris/1.0 (Android reading app)").build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "DOI not found (HTTP ${resp.code})." }
            val m = JSONObject(resp.body?.string().orEmpty()).getJSONObject("message")
            var r = BibRecord()
            m.optJSONArray("title")?.optString(0)?.let { r = r.with(BibField.TITLE, it) }
            m.optJSONArray("subtitle")?.optString(0)?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.SUBTITLE, it) }
            m.optJSONArray("container-title")?.optString(0)?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.JOURNAL, it) }
            m.optString("publisher").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.PUBLISHER, it) }
            m.optString("volume").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.VOLUME, it) }
            m.optString("issue").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.ISSUE, it) }
            m.optString("page").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.PAGES, it.replace("-", "–")) }
            (m.optJSONObject("issued") ?: m.optJSONObject("published"))?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0)?.takeIf { it > 0 }
                ?.let { r = r.with(BibField.YEAR, it.toString()) }
            m.optJSONArray("ISBN")?.optString(0)?.let { BibFieldCleaner.clean(BibField.ISBN, it) }?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.ISBN, it) }
            m.optJSONArray("ISSN")?.optString(0)?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.ISSN, it) }
            r = r.with(BibField.DOI, doi)
            val type = when (m.optString("type")) { "journal-article" -> "article"; "book-chapter" -> "incollection"; "book", "monograph" -> "book"; else -> "" }
            val authors = m.optJSONArray("author")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { "${it.optString("given")} ${it.optString("family")}".trim() } }
            }.orEmpty().joinToString("; ")
            r.copy(entryType = type) to authors
        }
    }

    fun byIsbn(isbn: String): Result<Pair<BibRecord, String>> = runCatching {
        val req = Request.Builder().url("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data").build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "ISBN lookup failed (HTTP ${resp.code})." }
            val root = JSONObject(resp.body?.string().orEmpty())
            val b = root.optJSONObject("ISBN:$isbn") ?: error("No book found for ISBN $isbn.")
            var r = BibRecord().with(BibField.ISBN, isbn)
            b.optString("title").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.TITLE, it) }
            b.optString("subtitle").takeIf { it.isNotBlank() }?.let { r = r.with(BibField.SUBTITLE, it) }
            b.optJSONArray("publishers")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.PUBLISHER, it) }
            b.optJSONArray("publish_places")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }?.let { r = r.with(BibField.PLACE, it) }
            BibFieldCleaner.clean(BibField.YEAR, b.optString("publish_date")).takeIf { it.isNotBlank() }?.let { r = r.with(BibField.YEAR, it) }
            val authors = b.optJSONArray("authors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty().joinToString("; ")
            r.copy(entryType = "book") to authors
        }
    }
}

/** A chapter record from Crossref. */
data class CrossrefChapter(val title: String, val authors: String, val pages: String, val doi: String, val abstract: String)

/** Chapters of an edited volume from Crossref: by the book's ISBN, else by its title. Only runs when the user taps the button. */
object CrossrefChapters {
    private val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", "MarginaliaLectoris/1.0 (Android reading app)").build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Crossref answered HTTP ${resp.code}." }
            return JSONObject(resp.body?.string().orEmpty()).getJSONObject("message")
        }
    }

    private fun parse(item: JSONObject): CrossrefChapter {
        val title = listOfNotNull(item.optJSONArray("title")?.optString(0), item.optJSONArray("subtitle")?.optString(0)?.takeIf { it.isNotBlank() })
            .joinToString(": ").replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
        val authors = item.optJSONArray("author")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { "${it.optString("given")} ${it.optString("family")}".trim().ifBlank { it.optString("name") } } }
        }.orEmpty().filter { it.isNotBlank() }.joinToString("; ")
        val abstract = item.optString("abstract").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().removePrefix("Abstract").trim()
        return CrossrefChapter(title, authors, item.optString("page").replace("-", "–"), item.optString("DOI"), abstract)
    }

    /** ISBNs of the book: its own field, plus those Crossref lists for its DOI. */
    fun isbnsFor(rec: BibRecord): List<String> {
        val out = mutableListOf<String>()
        rec[BibField.ISBN].takeIf { it.isNotBlank() }?.let { out += it }
        rec[BibField.DOI].takeIf { it.isNotBlank() }?.let { doi ->
            runCatching { get("https://api.crossref.org/works/${android.net.Uri.encode(doi)}") }.getOrNull()?.optJSONArray("ISBN")?.let { a ->
                (0 until a.length()).forEach { out += a.optString(it).filter { c -> c.isDigit() || c == 'X' || c == 'x' } }
            }
        }
        return out.filter { it.length == 10 || it.length == 13 }.distinct()
    }

    fun fetch(bookTitle: String, rec: BibRecord): Result<List<CrossrefChapter>> = runCatching {
        val found = LinkedHashMap<String, CrossrefChapter>()
        isbnsFor(rec).forEach { isbn ->
            runCatching { get("https://api.crossref.org/works?filter=isbn:$isbn,type:book-chapter&rows=300") }.getOrNull()
                ?.optJSONArray("items")?.let { arr -> (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { val c = parse(it); found.putIfAbsent(c.doi.ifBlank { c.title }, c) } } }
        }
        if (found.isEmpty() && bookTitle.isNotBlank()) {
            // Title search: keep only chapters whose container is this book.
            val q = android.net.Uri.encode(bookTitle)
            val items = get("https://api.crossref.org/works?query.container-title=$q&filter=type:book-chapter&rows=200").optJSONArray("items")
            val want = bookTitle.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            if (items != null) (0 until items.length()).forEach { i ->
                val it = items.optJSONObject(i) ?: return@forEach
                val container = it.optJSONArray("container-title")?.optString(0).orEmpty().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                if (container.isNotBlank() && (container == want || container.startsWith(want) || want.startsWith(container))) {
                    val c = parse(it); found.putIfAbsent(c.doi.ifBlank { c.title }, c)
                }
            }
        }
        check(found.isNotEmpty()) { "Crossref has no chapter records for this book. Add its ISBN or DOI in the book details and try again." }
        found.values.toList()
    }
}

/** Handwriting sketches saved with notes as Obsidian embeds: ![[sketch-….png]]. */
object Sketches {
    private val embed = Regex("!\\[\\[(sketch-[A-Za-z0-9_-]+\\.png)]]")
    fun dir(context: Context): File = File(context.applicationContext.filesDir, "sketches").apply { mkdirs() }
    fun referencedIn(text: String): List<String> = embed.findAll(text).map { it.groupValues[1] }.toList()
    fun filesFor(context: Context, notes: List<String>): List<File> =
        notes.flatMap { referencedIn(it) }.distinct().map { File(dir(context), it) }.filter { it.isFile }
}
