package com.example.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The lexicon: words worth keeping, with what they mean and where they were found. Kept on the
 * tablet beside the notes (lexicon.json, part of backups); used by the reader on the tablet and,
 * through the web server, by the web view and the writing program.
 */
object Lexicon {
    data class Word(
        val id: String,
        val word: String,
        val definition: String = "",
        val partOfSpeech: String = "",
        /** The sentence it was found in. */
        val example: String = "",
        val docId: Long = 0L,
        /** Title of the source, kept so the word still says where it came from if the book is removed. */
        val source: String = "",
        val page: String = "",
        val note: String = "",
        val tags: String = "",
        /** Everyday words this one can stand in for (comma-separated): they are marked in the writing program. */
        val insteadOf: String = "",
        /** The word family, one form per line with what it can replace: "salience: importance, prominence". */
        val forms: String = "",
        val added: Long = System.currentTimeMillis(),
        val modified: Long = System.currentTimeMillis()
    )

    /** Goes up on every change, so open lists redraw. */
    val version = MutableStateFlow(0)

    private val lock = Any()
    private var cache: MutableList<Word>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "lexicon.json")

    fun toJson(w: Word): JSONObject = JSONObject()
        .put("id", w.id).put("word", w.word).put("definition", w.definition).put("partOfSpeech", w.partOfSpeech)
        .put("example", w.example).put("docId", w.docId).put("source", w.source).put("page", w.page)
        .put("note", w.note).put("tags", w.tags).put("insteadOf", w.insteadOf).put("forms", w.forms).put("added", w.added).put("modified", w.modified)

    private fun fromJson(o: JSONObject): Word? {
        val word = o.optString("word").trim()
        if (word.isBlank()) return null
        return Word(
            id = o.optString("id").ifBlank { newId() }, word = word,
            definition = o.optString("definition"), partOfSpeech = o.optString("partOfSpeech"),
            example = o.optString("example"), docId = o.optLong("docId", 0L), source = o.optString("source"),
            page = o.optString("page"), note = o.optString("note"), tags = o.optString("tags"), insteadOf = o.optString("insteadOf"), forms = o.optString("forms"),
            added = o.optLong("added", System.currentTimeMillis()), modified = o.optLong("modified", System.currentTimeMillis())
        )
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + Integer.toString((Math.random() * 1_000_000).toInt(), 36)

    private fun load(ctx: Context): MutableList<Word> = synchronized(lock) {
        cache ?: run {
            val arr = runCatching { file(ctx).takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONArray("words") } }.getOrNull() ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }.toMutableList()
        }.also { cache = it }
    }

    private fun store(ctx: Context, words: List<Word>) = synchronized(lock) {
        cache = words.toMutableList()
        val f = file(ctx); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("words", JSONArray().apply { words.forEach { put(toJson(it)) } }).toString())
        check(tmp.renameTo(f)) { "Could not save the lexicon." }
        version.value = version.value + 1
    }

    /** Re-read from disk (after a backup is restored). */
    fun reload() { synchronized(lock) { cache = null }; version.value = version.value + 1 }

    fun all(ctx: Context): List<Word> = synchronized(lock) { load(ctx).sortedBy { it.word.lowercase(Locale.ROOT) } }

    fun find(ctx: Context, word: String): Word? = synchronized(lock) { load(ctx).firstOrNull { it.word.equals(word.trim(), ignoreCase = true) } }

    /** Adds a word; when it is already there, the one that is there is returned (second = false). */
    fun add(ctx: Context, w: Word): Pair<Word, Boolean> = synchronized(lock) {
        val clean = w.word.trim().trim { !it.isLetterOrDigit() && it != '-' && it != '\'' && it != '’' }
        require(clean.isNotBlank()) { "No word to add." }
        require(clean.length <= 80) { "That is too long for one word or phrase." }
        find(ctx, clean)?.let { return it to false }
        val now = System.currentTimeMillis()
        val made = w.copy(id = newId(), word = clean, added = now, modified = now)
        store(ctx, load(ctx) + made)
        made to true
    }

    /** Changes the fields given in [o] (word, definition, partOfSpeech, example, page, note, tags, insteadOf). */
    fun update(ctx: Context, id: String, o: JSONObject): Word = synchronized(lock) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) throw NoSuchElementException("That word is no longer in the lexicon.")
        var w = list[i]
        fun s(k: String, cur: String) = if (o.has(k)) o.optString(k) else cur
        val word = s("word", w.word).trim()
        require(word.isNotBlank()) { "The word cannot be empty." }
        list.firstOrNull { it.id != id && it.word.equals(word, ignoreCase = true) }?.let { throw IllegalArgumentException("“$word” is already in the lexicon.") }
        w = w.copy(word = word, definition = s("definition", w.definition).trim(), partOfSpeech = s("partOfSpeech", w.partOfSpeech).trim(),
            example = s("example", w.example).trim(), page = s("page", w.page).trim(), note = s("note", w.note).trim(),
            tags = s("tags", w.tags).trim(), insteadOf = s("insteadOf", w.insteadOf).trim(), forms = s("forms", w.forms).trim(), modified = System.currentTimeMillis())
        val out = list.toMutableList(); out[i] = w
        store(ctx, out)
        w
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) { store(ctx, load(ctx).filterNot { it.id == id }) }

    /**
     * Asks the AI chosen in Settings → AI what [word] means, as used in [example]. Returns the definition
     * and the part of speech; nothing is saved. Only ever called from a button.
     */
    data class Definition(val definition: String, val partOfSpeech: String, val insteadOf: String, val forms: String = "")

    private const val FAMILY_ASK = "Also give the word's family: the other forms of it that are used in writing (other parts of speech, " +
        "and irregular forms; not the plain plural, past tense, -ing or -ly forms that follow the usual rules), each with two to four " +
        "plainer everyday words or phrases, in the same grammatical form, that it could replace. For 'salient': salience → importance, " +
        "prominence. Leave it empty when the word has no such forms. "

    private fun formsText(o: JSONObject): String {
        val a = o.optJSONArray("forms") ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { f ->
            val form = f.optString("form").trim().replace(":", "")
            val alt = f.optJSONArray("insteadOf")?.let { x -> (0 until x.length()).map { x.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty()
            if (form.isBlank() || alt.isEmpty()) null else "$form: ${alt.take(5).joinToString(", ")}"
        }.take(8).joinToString("\n")
    }

    /** The word family alone, for a word that is already saved. Only ever run from a button. */
    fun family(ctx: Context, word: String, partOfSpeech: String, definition: String): String {
        val o = NoteAiAssistant.askJson(ctx,
            "You are a careful lexicographer. For the word below (as defined), " + FAMILY_ASK.replaceFirst("Also give", "give") +
                "Write in the language of the word. Return only JSON: {\"forms\": [{\"form\": string, \"insteadOf\": [string]}]}.",
            "Word: ${word.trim()}" + (if (partOfSpeech.isNotBlank()) " ($partOfSpeech)" else "") + "\nMeaning: ${definition.trim().take(400)}",
            maxTokens = 500, maxChars = 1000)
        return formsText(o)
    }

    fun define(ctx: Context, word: String, example: String): Definition {
        val o = NoteAiAssistant.askJson(ctx,
            "You are a careful lexicographer. Define the word or phrase below as it is used in the example sentence " +
                "(if there is one), in the style of a good dictionary: one or two short sentences, no examples, no etymology " +
                "unless it is needed to understand the word. Write the definition in the language of the word itself " +
                "(Norwegian for a Norwegian word, British English for an English word). Also give the part of speech in " +
                "the same language (for English: noun, verb, adjective, adverb, phrase, …), and two to five plainer, everyday " +
                "words or short phrases in the same language that this word could replace in writing, in the same grammatical form " +
                "(for 'salient': important, striking, prominent). " + FAMILY_ASK +
                "Return only JSON: {\"definition\": string, \"partOfSpeech\": string, \"insteadOf\": [string], \"forms\": [{\"form\": string, \"insteadOf\": [string]}]}.",
            "Word: ${word.trim()}\nExample: ${example.trim().take(800)}", maxTokens = 800, maxChars = 1200)
        val d = o.optString("definition").trim()
        check(d.isNotBlank()) { "The AI gave no definition." }
        val alt = o.optJSONArray("insteadOf")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() && !it.equals(word.trim(), true) } }.orEmpty()
        return Definition(d, o.optString("partOfSpeech").trim(), alt.take(6).joinToString(", "), formsText(o))
    }

    /**
     * Places in [text] where words from the lexicon would fit, as the AI sees it: each with the words that
     * stand there now, what to put instead, and the lexicon word it brings in. [only]: just that word.
     */
    fun suggest(ctx: Context, text: String, only: String = ""): JSONArray {
        val words = all(ctx).filter { only.isBlank() || it.word.equals(only.trim(), ignoreCase = true) }
        check(words.isNotEmpty()) { if (only.isBlank()) "The lexicon is empty." else "“$only” is not in the lexicon." }
        val list = words.take(400).joinToString("\n") { w ->
            "- ${w.word}" + (if (w.partOfSpeech.isNotBlank()) " (${w.partOfSpeech})" else "") + (if (w.definition.isNotBlank()) ": ${w.definition.replace(Regex("\\s+"), " ").take(140)}" else "")
        }
        val o = NoteAiAssistant.askJson(ctx,
            "You help a writer use the words they are collecting. Below are their words (with meanings) and a text they wrote. " +
                (if (only.isBlank()) "Find places where one of these words would fit naturally and improve the sentence. "
                else "Find the two or three best places where the word ‘${only.trim()}’ would fit naturally and improve the sentence. ") +
                "Only suggest a word where its meaning truly fits; never force one in, and never change quotations, citations or names. " +
                "Keep the grammar right (inflect the word as needed, and adjust a neighbouring article or preposition if you must). " +
                "At most one suggestion per sentence, and at most twelve in all. " +
                "For each, give the exact words of the text that would be replaced (copied character for character, as short as possible), " +
                "the replacement, the lexicon word it uses, and a short reason. " +
                "Return only JSON: {\"suggestions\": [{\"original\": string, \"suggestion\": string, \"word\": string, \"reason\": string}]}. " +
                "Return {\"suggestions\": []} when nothing fits well.",
            "WORDS:\n$list\n\nTEXT:\n${text.take(14000)}", maxTokens = 3000, maxChars = 40000)
        return o.optJSONArray("suggestions") ?: JSONArray()
    }

    /** The sentence of [passage] that holds [word] (for the example). */
    fun sentenceWith(passage: String, word: String): String {
        val text = passage.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return ""
        val at = text.lowercase(Locale.ROOT).indexOf(word.trim().lowercase(Locale.ROOT))
        if (at < 0) return text.take(300)
        val start = (Regex("[.!?…][\"'’”)]?\\s").findAll(text.substring(0, at)).lastOrNull()?.range?.last?.plus(1)) ?: 0
        val endMatch = Regex("[.!?…]").find(text, at + word.length)
        val end = endMatch?.range?.last?.plus(1) ?: text.length
        return text.substring(start, end).trim().take(400)
    }
}
