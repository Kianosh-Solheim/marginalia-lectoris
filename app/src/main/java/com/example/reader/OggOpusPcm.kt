package com.example.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Opus audio decoded in the app (Concentus, a Java port of libopus), for tablets whose Android has no Opus
 * decoder (the Onyx NoteAir5C has none). Gives a stretch of an Ogg Opus file as 16 kHz mono 16-bit PCM,
 * the way [AudiobookSync.pcmWindow] does with Android's decoders. Pure JVM, so it can be tested.
 *
 * The file's pages are indexed once (their place and granule position, headers only); a stretch is decoded
 * from the page before it, 80 ms early so the decoder has settled (Opus's pre-roll).
 */
object OggOpusPcm {
    private const val RATE = 16_000            // what Whisper wants; Opus decodes to it directly
    private const val PER_MS_48 = 48           // Ogg Opus positions count at 48 kHz
    private const val PREROLL_48 = 3840        // 80 ms

    private class Index(val offsets: LongArray, val granules: LongArray, val continued: BooleanArray, val serial: Int,
                        val channels: Int, val preSkip: Int, val firstAudio: Int)

    private val cache = object : LinkedHashMap<String, Index>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Index>?) = size > 3
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
    private fun le64(b: ByteArray, o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff); return v }

    private fun index(f: File): Index = synchronized(cache) {
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        cache[key] ?: build(f).also { cache[key] = it }
    }

    private fun build(f: File): Index {
        val head = OggAudio.packets(f, 1).firstOrNull()
        require(head != null && head.size >= 19 && String(head, 0, 8, Charsets.ISO_8859_1) == "OpusHead") { "not an Ogg Opus file" }
        val channels = head[9].toInt() and 0xff
        val preSkip = le16(head, 10)
        val family = head[18].toInt() and 0xff
        require(family == 0 || channels <= 2) { "Opus with $channels channels (surround) is not supported" }
        val offs = ArrayList<Long>(1 shl 14); val gran = ArrayList<Long>(1 shl 14); val cont = ArrayList<Boolean>(1 shl 14)
        var serial: Int? = null
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val buf = ByteArray(27 + 255)
            var pos = 0L
            while (pos + 27 <= len) {
                raf.seek(pos); raf.readFully(buf, 0, 27)
                if (buf[0] != 'O'.code.toByte() || buf[1] != 'g'.code.toByte() || buf[2] != 'g'.code.toByte() || buf[3] != 'S'.code.toByte()) break
                val segs = buf[26].toInt() and 0xff
                raf.readFully(buf, 27, segs)
                var body = 0; for (i in 0 until segs) body += buf[27 + i].toInt() and 0xff
                val ser = le32(buf, 14)
                if (serial == null) serial = ser
                if (ser == serial) { offs += pos; gran += le64(buf, 6); cont += (buf[5].toInt() and 1) != 0 }
                pos += 27L + segs + body
            }
        }
        // OpusHead fills page 0, OpusTags begins on page 1 and may run on (pages marked as continuing it);
        // the audio begins on the next fresh page
        var firstAudio = 2
        while (firstAudio < cont.size && cont[firstAudio]) firstAudio++
        return Index(offs.toLongArray(), gran.toLongArray(), cont.toBooleanArray(), serial ?: 0, channels, preSkip, firstAudio)
    }

    /** The packets from page [start] on (the first one beginning on that page), each with the page it ends on. */
    private fun packets(raf: RandomAccessFile, ix: Index, start: Int): Sequence<ByteArray> = sequence {
        var cur = ByteArrayOutputStream(); var skipping = ix.continued[start]
        val hdr = ByteArray(27 + 255)
        for (p in start until ix.offsets.size) {
            raf.seek(ix.offsets[p]); raf.readFully(hdr, 0, 27)
            val segs = hdr[26].toInt() and 0xff
            raf.readFully(hdr, 27, segs)
            var body = 0; for (i in 0 until segs) body += hdr[27 + i].toInt() and 0xff
            val data = ByteArray(body); raf.readFully(data)
            var off = 0
            for (i in 0 until segs) {
                val n = hdr[27 + i].toInt() and 0xff
                if (!skipping) cur.write(data, off, n)
                off += n
                if (n < 255) {
                    if (!skipping) yield(cur.toByteArray())
                    cur = ByteArrayOutputStream(); skipping = false
                }
            }
        }
    }

    /** 16 kHz mono 16-bit little-endian PCM of [lenMs] from [fromMs]. */
    fun window(f: File, fromMs: Long, lenMs: Long): ByteArray {
        val ix = index(f)
        val fromG = fromMs * PER_MS_48 + ix.preSkip
        val toG = (fromMs + lenMs) * PER_MS_48 + ix.preSkip
        // the page to start on: before the stretch less the pre-roll, at a page that begins with a whole packet,
        // where the page before it says how far the stream had come
        var q = ix.firstAudio
        run {
            var lo = ix.firstAudio; var hi = ix.granules.size - 1
            val want = fromG - PREROLL_48
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (ix.granules[mid] in 0 until want) { q = mid + 1; lo = mid + 1 } else hi = mid - 1 }
        }
        q = q.coerceIn(ix.firstAudio, maxOf(ix.firstAudio, ix.offsets.size - 1))
        while (q > ix.firstAudio && (ix.continued[q] || ix.granules[q - 1] < 0)) q--
        var at48 = if (q <= ix.firstAudio) 0L else ix.granules[q - 1]

        val dec = org.concentus.OpusDecoder(RATE, 1)   // a stereo stream is mixed down to one channel
        val pcm = ShortArray(RATE * 120 / 1000)        // the longest Opus packet: 120 ms
        val out = ByteArrayOutputStream((lenMs * 32).toInt().coerceIn(1024, 64 shl 20))
        RandomAccessFile(f, "r").use { raf ->
            for (pkt in packets(raf, ix, q)) {
                if (pkt.isEmpty()) continue
                // a packet that cannot be decoded becomes silence of its length, so the times after it stay right
                val n = try { dec.decode(pkt, 0, pkt.size, pcm, 0, pcm.size, false) }
                catch (_: Exception) { val k = runCatching { org.concentus.OpusPacketInfo.getNumSamples(pkt, 0, pkt.size, RATE) }.getOrDefault(0).coerceIn(0, pcm.size); java.util.Arrays.fill(pcm, 0, k, 0); k }
                for (i in 0 until n) {
                    val g = at48 + i * (48_000 / RATE)
                    if (g < fromG) continue
                    if (g >= toG) return out.toByteArray()
                    val s = pcm[i].toInt()
                    out.write(s and 0xff); out.write((s shr 8) and 0xff)
                }
                at48 += n.toLong() * (48_000 / RATE)
                if (at48 >= toG) break
            }
        }
        return out.toByteArray()
    }
}
