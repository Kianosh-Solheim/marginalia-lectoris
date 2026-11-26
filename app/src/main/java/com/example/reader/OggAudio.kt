package com.example.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Ogg audio (Opus or Vorbis): the chapter marks in its comment header, and its length. Pure JVM, so it can be tested.
 *
 * Chapters are written as comments, the way ffmpeg and most audiobook tools write them:
 *   CHAPTER001=00:12:34.500   CHAPTER001NAME=The second chapter
 */
object OggAudio {
    private val LATIN = Charsets.ISO_8859_1

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun le64(b: ByteArray, o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff); return v }

    /** The first [count] packets of the file's first stream; a packet is cut at [max] bytes (a cover picture can be large). */
    fun packets(f: File, count: Int, max: Int = 4 shl 20): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        RandomAccessFile(f, "r").use { raf ->
            var serial: Int? = null
            var cur = ByteArrayOutputStream()
            var pos = 0L
            val hdr = ByteArray(27)
            while (out.size < count && pos + 27 <= raf.length()) {
                raf.seek(pos); raf.readFully(hdr)
                if (String(hdr, 0, 4, LATIN) != "OggS") break
                val segs = hdr[26].toInt() and 0xff
                val table = ByteArray(segs).also { raf.readFully(it) }
                val bodyLen = table.sumOf { it.toInt() and 0xff }
                val ser = le32(hdr, 14)
                if (serial == null) serial = ser
                if (ser == serial) {
                    // a page of a long packet that is already over [max] is skipped, not read
                    val needed = cur.size() < max
                    val body = if (needed) ByteArray(bodyLen).also { raf.readFully(it) } else null
                    var off = 0
                    for (s in table) {
                        val n = s.toInt() and 0xff
                        if (body != null && cur.size() + n <= max) cur.write(body, off, n)
                        off += n
                        if (n < 255) {   // the packet ends here
                            out += cur.toByteArray(); cur = ByteArrayOutputStream()
                            if (out.size >= count) break
                        }
                    }
                }
                pos += 27 + segs + bodyLen
            }
        }
        return out
    }

    /**
     * Where the bytes of packet [index] of the file's first stream lie: (file offset, length) spans, in order.
     * Only the page headers are read, so a packet of any size (a large cover picture) costs nothing.
     */
    private fun spans(raf: RandomAccessFile, index: Int): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        var serial: Int? = null
        var packet = 0
        var pos = 0L
        val hdr = ByteArray(27)
        while (pos + 27 <= raf.length()) {
            raf.seek(pos); raf.readFully(hdr)
            if (String(hdr, 0, 4, LATIN) != "OggS") break
            val segs = hdr[26].toInt() and 0xff
            val table = ByteArray(segs).also { raf.readFully(it) }
            val bodyStart = pos + 27 + segs
            val ser = le32(hdr, 14)
            if (serial == null) serial = ser
            var off = 0L
            for (s in table) {
                val n = s.toInt() and 0xff
                if (ser == serial) {
                    if (packet == index && n > 0) out += (bodyStart + off) to n
                    if (n < 255) { if (packet == index) return out; packet++ }
                }
                off += n
            }
            pos = bodyStart + table.sumOf { it.toInt() and 0xff }
        }
        return out
    }

    /** Reads the bytes of a packet from its spans, and skips over long stretches without reading them. */
    private class SpanReader(private val raf: RandomAccessFile, private val spans: List<Pair<Long, Int>>) {
        private var k = 0; private var at = 0
        fun read(n: Int): ByteArray? {
            val out = ByteArray(n); var got = 0
            while (got < n) {
                val (start, len) = spans.getOrNull(k) ?: return null
                val take = minOf(n - got, len - at)
                raf.seek(start + at); raf.readFully(out, got, take)
                got += take; at += take
                if (at == len) { k++; at = 0 }
            }
            return out
        }
        fun skip(n: Long): Boolean {
            var left = n
            while (left > 0) {
                val (_, len) = spans.getOrNull(k) ?: return false
                val take = minOf(left, (len - at).toLong()).toInt()
                left -= take; at += take
                if (at == len) { k++; at = 0 }
            }
            return true
        }
        fun le32(): Int? = read(4)?.let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) or ((it[2].toInt() and 0xff) shl 16) or ((it[3].toInt() and 0xff) shl 24) }
    }

    /** The comments (NAME=value) of the comment header, in order; long ones (a cover picture) are left out. */
    fun comments(f: File): List<String> = RandomAccessFile(f, "r").use { raf ->
        val r = SpanReader(raf, spans(raf, 1))
        val magic = r.read(7) ?: return@use emptyList()
        when {
            String(magic, LATIN) == "OpusTag" -> if (r.read(1)?.get(0)?.toInt() != 's'.code) return@use emptyList()
            magic[0].toInt() == 3 && String(magic, 1, 6, LATIN) == "vorbis" -> {}
            else -> return@use emptyList()
        }
        val vendor = r.le32() ?: return@use emptyList()
        if (vendor < 0 || !r.skip(vendor.toLong())) return@use emptyList()
        val n = r.le32() ?: return@use emptyList()
        val out = mutableListOf<String>()
        for (k in 0 until n.coerceIn(0, 100_000)) {
            val len = r.le32() ?: break
            if (len < 0) break
            if (len > 64 * 1024) { if (!r.skip(len.toLong() and 0xffffffffL)) break; continue }
            out += String(r.read(len) ?: break, Charsets.UTF_8)
        }
        out
    }

    private val TIME = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:[.,](\d+))?$""")

    private fun timeMs(s: String): Long? {
        val m = TIME.find(s.trim()) ?: return null
        val (h, mi, se, frac) = m.destructured
        val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
        return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + ms
    }

    /** The chapter marks: (title, start ms), in time order. */
    fun chapters(f: File): List<Pair<String, Long>> {
        val tags = LinkedHashMap<String, String>()
        comments(f).forEach { c -> val i = c.indexOf('='); if (i > 0) tags[c.substring(0, i).uppercase()] = c.substring(i + 1) }
        val num = Regex("^CHAPTER(\\d+)$")
        return tags.keys.mapNotNull { num.find(it)?.groupValues?.get(1) }
            .mapNotNull { n ->
                val ms = timeMs(tags["CHAPTER$n"].orEmpty()) ?: return@mapNotNull null
                (tags["CHAPTER${n}NAME"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter ${n.toInt()}") to ms
            }
            .sortedBy { it.second }
    }

    /** The length in ms, from the last page's position (Opus counts at 48 kHz, less its pre-skip; Vorbis at its own rate). */
    fun durationMs(f: File): Long {
        val head = packets(f, 1).firstOrNull() ?: return 0
        val (rate, skip) = when {
            head.size >= 19 && String(head, 0, 8, LATIN) == "OpusHead" -> 48_000 to ((head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8))
            head.size >= 16 && head[0].toInt() == 1 && String(head, 1, 6, LATIN) == "vorbis" -> le32(head, 12) to 0
            else -> return 0
        }
        if (rate <= 0) return 0
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val n = minOf(len, 256 * 1024L).toInt()
            raf.seek(len - n)
            val b = ByteArray(n).also { raf.readFully(it) }
            var i = n - 27
            while (i >= 0) {
                if (b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() && b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte()) {
                    val g = le64(b, i + 6)
                    if (g > 0) return (g - skip).coerceAtLeast(0) * 1000 / rate
                }
                i--
            }
        }
        return 0
    }
}
