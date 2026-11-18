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
        .put("note", w.note).put("tags", w.tags).put("added", w.added).put("modified", w.modified)

    private fun fromJson(o: JSONObject): Word? {
        val word = o.optString("word").trim()
        if (word.isBlank()) return null
        return Word(
            id = o.optString("id").ifBlank { newId() }, word = word,
            definition = o.optString("definition"), partOfSpeech = o.optString("partOfSpeech"),
            example = o.optString("example"), docId = o.optLong("docId", 0L), source = o.optString("source"),
            page = o.optString("page"), note = o.optString("note"), tags = o.optString("tags"),
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

    /** Changes the fields given in [o] (word, definition, partOfSpeech, example, page, note, tags). */
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
            tags = s("tags", w.tags).trim(), modified = System.currentTimeMillis())
        val out = list.toMutableList(); out[i] = w
        store(ctx, out)
        w
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) { store(ctx, load(ctx).filterNot { it.id == id }) }

    /**
     * Asks the AI chosen in Settings → AI what [word] means, as used in [example]. Returns the definition
     * and the part of speech; nothing is saved. Only ever called from a button.
     */
    fun define(ctx: Context, word: String, example: String): Pair<String, String> {
        val o = NoteAiAssistant.askJson(ctx,
            "You are a careful lexicographer. Define the word or phrase below as it is used in the example sentence " +
                "(if there is one), in the style of a good dictionary: one or two short sentences, no examples, no etymology " +
                "unless it is needed to understand the word. Write the definition in the language of the word itself " +
                "(Norwegian for a Norwegian word, British English for an English word). Also give the part of speech in " +
                "the same language (for English: noun, verb, adjective, adverb, phrase, …). " +
                "Return only JSON: {\"definition\": string, \"partOfSpeech\": string}.",
            "Word: ${word.trim()}\nExample: ${example.trim().take(800)}", maxTokens = 400, maxChars = 1200)
        val d = o.optString("definition").trim()
        check(d.isNotBlank()) { "The AI gave no definition." }
        return d to o.optString("partOfSpeech").trim()
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
