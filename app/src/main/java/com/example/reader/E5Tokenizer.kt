package com.example.reader

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.Normalizer

/**
 * The tokenizer of the offline meaning model (multilingual-e5-small, an XLM-RoBERTa SentencePiece
 * unigram vocabulary): text → word pieces → ids, with <s> … </s> around them.
 *
 * Normalisation follows the model's own (NFKC, whitespace made single spaces); each word starts with
 * "▁", and is split into the pieces with the best total score (Viterbi).
 */
class E5Tokenizer(private val pieces: HashMap<String, Int>, private val scores: FloatArray) {
    private val maxPiece = 24
    private val unkScore = (scores.minOrNull() ?: 0f) - 10f
    val bos = 0; val pad = 1; val eos = 2; val unk = 3

    /** Ids for [text], at most [maxTokens] including <s> and </s>. */
    fun encode(text: String, maxTokens: Int = 512): IntArray {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("[\\s\\u0000-\\u001F\\u00A0\\u2000-\\u200B\\u2028\\u2029\\u3000]+"), " ").trim()
        val out = ArrayList<Int>(minOf(maxTokens, norm.length / 3 + 8))
        out += bos
        if (norm.isNotEmpty()) for (w in norm.split(' ')) {
            if (w.isEmpty()) continue
            if (!word("▁" + w, out, maxTokens - 1)) break
        }
        if (out.size > maxTokens - 1) while (out.size > maxTokens - 1) out.removeAt(out.size - 1)
        out += eos
        return out.toIntArray()
    }

    /** Best split of one word into pieces (unknown characters become <unk>, joined). False when full. */
    private fun word(w: String, out: MutableList<Int>, limit: Int): Boolean {
        val n = w.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val id = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var matched = false
            val end = minOf(n, i + maxPiece)
            for (j in i + 1..end) {
                val p = pieces[w.substring(i, j)] ?: continue
                matched = matched || j == i + 1
                val s = best[i] + scores[p]
                if (s > best[j]) { best[j] = s; from[j] = i; id[j] = p }
            }
            // No piece for this single character: <unk> (with a large penalty, as SentencePiece does).
            if (!matched) {
                val j = i + if (Character.isHighSurrogate(w[i]) && i + 1 < n) 2 else 1
                val s = best[i] + unkScore
                if (s > best[j]) { best[j] = s; from[j] = i; id[j] = unk }
            }
        }
        val ids = ArrayList<Int>()
        var j = n
        while (j > 0) { val i = from[j]; if (i < 0) break; ids += id[j]; j = i }
        ids.reverse()
        var lastUnk = false
        for (x in ids) {
            if (x == unk && lastUnk) continue   // consecutive unknowns are one <unk>
            lastUnk = x == unk
            if (out.size >= limit) return false
            out += x
        }
        return true
    }

    companion object {
        /** Reads the compact vocabulary made by [convert]. */
        fun load(bin: File): E5Tokenizer = DataInputStream(bin.inputStream().buffered(1 shl 16)).use { inp ->
            val n = inp.readInt()
            val map = HashMap<String, Int>(n * 2)
            val sc = FloatArray(n)
            for (i in 0 until n) { val p = inp.readUTF(); sc[i] = inp.readFloat(); map.putIfAbsent(p, i) }
            E5Tokenizer(map, sc)
        }

        /** tokenizer.json (17 MB of JSON) → a compact vocabulary file, read as a stream to spare memory. */
        fun convert(json: File, bin: File) {
            val pieces = ArrayList<String>(260000); val scores = ArrayList<Float>(260000)
            android.util.JsonReader(json.reader().buffered(1 shl 16)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "model") { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() != "vocab") { r.skipValue(); continue }
                        r.beginArray()
                        while (r.hasNext()) { r.beginArray(); pieces += r.nextString(); scores += r.nextDouble().toFloat(); r.endArray() }
                        r.endArray()
                    }
                    r.endObject()
                }
                r.endObject()
            }
            check(pieces.size > 1000) { "The tokenizer file has no vocabulary" }
            val tmp = File(bin.parentFile, bin.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o ->
                o.writeInt(pieces.size)
                pieces.forEachIndexed { i, p -> o.writeUTF(p); o.writeFloat(scores[i]) }
            }
            tmp.renameTo(bin)
        }
    }
}
