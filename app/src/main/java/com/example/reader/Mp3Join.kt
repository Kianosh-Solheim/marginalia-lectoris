package com.example.reader

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Joins MP3 files into one, without re-encoding: their audio frames are copied one after another, and a new ID3
 * tag with the title, the author and the chapters (CHAP/CTOC) goes in front. Only for files that are alike
 * (MPEG Layer III, the same sample rate, channels and constant bitrate, as a DAISY book's are); otherwise
 * [scan] gives null and the files are kept as they are. Each file's own tags and VBR header ("Xing"/"Info")
 * are left out, so the joined file's length and seeking are right.
 */
object Mp3Join {
    class Scan(val files: List<File>, val startsMs: List<Long>, val durationMs: Long)
    class Chapter(val title: String, val startMs: Long)

    private val BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SR = mapOf(3 to intArrayOf(44100, 48000, 32000), 2 to intArrayOf(22050, 24000, 16000), 0 to intArrayOf(11025, 12000, 8000))

    /** The format (version, sample rate, channels, bitrate) and length of a frame at [i], or null if no frame is there. */
    private class Head(val key: Int, val len: Int, val samples: Int, val sr: Int)

    private fun head(b: ByteArray, i: Int): Head? {
        if (i + 4 > b.size) return null
        val b1 = b[i + 1].toInt() and 0xFF; val b2 = b[i + 2].toInt() and 0xFF; val b3 = b[i + 3].toInt() and 0xFF
        if ((b[i].toInt() and 0xFF) != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val ver = (b1 shr 3) and 3; val layer = (b1 shr 1) and 3
        if (ver == 1 || layer != 1) return null                       // reserved, or not Layer III
        val bri = b2 shr 4; val sri = (b2 shr 2) and 3
        if (bri == 0 || bri == 15 || sri == 3) return null
        val sr = SR.getValue(ver)[sri]
        val kbps = if (ver == 3) BR1[bri] else BR2[bri]
        val pad = (b2 shr 1) and 1
        val len = (if (ver == 3) 144 else 72) * kbps * 1000 / sr + pad
        if (len < 4 || i + len > b.size) return null
        val mono = (b3 shr 6) == 3
        return Head(key = (ver shl 24) or (sri shl 20) or (bri shl 8) or (if (mono) 1 else 0), len = len, samples = if (ver == 3) 1152 else 576, sr = sr)
    }

    private fun id3Size(b: ByteArray): Int =
        if (b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte())
            10 + ((b[6].toInt() and 0x7F) shl 21 or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)) + (if ((b[5].toInt() and 0x10) != 0) 10 else 0)
        else 0

    private fun isVbrHeader(b: ByteArray, at: Int, len: Int): Boolean {
        val s = String(b, at, minOf(len, 64), Charsets.ISO_8859_1)
        return "Xing" in s || "Info" in s || "VBRI" in s
    }

    /** Calls [frame] with each audio frame of [b] (offset, length); → the frames' common format, or -1 if they differ. */
    private fun frames(b: ByteArray, frame: (Int, Int, Head) -> Unit): Int {
        var i = id3Size(b); var key = 0; var first = true
        while (i + 4 <= b.size) {
            val h = head(b, i)
            if (h == null) { i++; continue }                          // stray bytes, an ID3v1 or APE tag
            if (first) { first = false; if (isVbrHeader(b, i, h.len)) { i += h.len; continue } }
            if (key == 0) key = h.key else if (key != h.key) return -1
            frame(i, h.len, h); i += h.len
        }
        return key
    }

    /** Whether [files] can be joined, and where each will begin. */
    fun scan(files: List<File>): Scan? {
        if (files.size < 2 || files.any { !it.name.endsWith(".mp3", true) }) return null
        var key = 0; var samples = 0L; var sr = 0; val starts = ArrayList<Long>()
        for (f in files) {
            starts += if (sr == 0) 0L else samples * 1000 / sr
            var n = 0L
            val k = frames(f.readBytes()) { _, _, h -> n += h.samples; sr = h.sr }
            if (k <= 0 || n == 0L) return null
            if (key == 0) key = k else if (key != k) return null
            samples += n
        }
        return Scan(files, starts, samples * 1000 / sr)
    }

    /** Writes the joined file to [out]. */
    fun write(scan: Scan, out: File, title: String, author: String, chapters: List<Chapter>) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        try {
            tmp.outputStream().buffered(1 shl 20).use { o ->
                o.write(tag(title, author, chapters, scan.durationMs))
                for (f in scan.files) { val b = f.readBytes(); frames(b) { at, len, _ -> o.write(b, at, len) } }
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        } finally { tmp.delete() }
    }

    // ID3v2.3: text in UTF-16 with a byte-order mark (Norwegian letters), frame sizes as plain 32-bit numbers.
    private fun text(s: String): ByteArray = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
    private fun int32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun frame(id: String, data: ByteArray) = id.toByteArray(Charsets.ISO_8859_1) + int32(data.size.toLong()) + byteArrayOf(0, 0) + data

    private fun tag(title: String, author: String, chapters: List<Chapter>, durationMs: Long): ByteArray {
        val body = ByteArrayOutputStream()
        if (title.isNotBlank()) body.write(frame("TIT2", text(title)))
        if (title.isNotBlank()) body.write(frame("TALB", text(title)))
        if (author.isNotBlank()) body.write(frame("TPE1", text(author)))
        body.write(frame("TCON", text("Audiobook")))
        val chs = chapters.sortedBy { it.startMs }.distinctBy { it.startMs }.take(255)   // a table of contents holds at most 255
        if (chs.isNotEmpty()) {
            val ids = chs.indices.map { "ch$it" }
            val toc = ByteArrayOutputStream().apply {
                write("toc".toByteArray()); write(0); write(3); write(ids.size)
                ids.forEach { write(it.toByteArray()); write(0) }
                if (title.isNotBlank()) write(frame("TIT2", text(title)))
            }
            body.write(frame("CTOC", toc.toByteArray()))
            chs.forEachIndexed { i, c ->
                val end = if (i + 1 < chs.size) chs[i + 1].startMs else durationMs
                val d = ByteArrayOutputStream().apply {
                    write(ids[i].toByteArray()); write(0)
                    write(int32(c.startMs)); write(int32(end)); write(int32(0xFFFFFFFFL)); write(int32(0xFFFFFFFFL))
                    write(frame("TIT2", text(c.title)))
                }
                body.write(frame("CHAP", d.toByteArray()))
            }
        }
        val b = body.toByteArray(); val n = b.size
        val size = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + size + b
    }
}
