package com.example.reader

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * A minimal HTTP/1.1 client for the paired tablet's own server on the local network.
 *
 * The app allows only encrypted connections (network_security_config: no cleartext), and a rule there
 * can't name "any address on this Wi‑Fi". The tablet's server is plain HTTP on a local address, so
 * this talks to it over a socket of its own instead of loosening that rule for every connection.
 * Used only by [TabletLink], only for the addresses from the tablet's pairing code.
 */
internal object LanHttp {
    class Response(val code: Int, private val headers: Map<String, String>, val body: InputStream, private val socket: Socket) : Closeable {
        fun header(name: String): String = headers[name.lowercase()].orEmpty()
        override fun close() { runCatching { socket.close() } }
    }

    fun request(base: String, path: String, method: String, key: String?, body: ByteArray?, connectTimeoutMs: Int): Response {
        val u = URL(base.trimEnd('/') + path)
        require(u.protocol == "http") { "Only the tablet's local address (http) is used here." }
        val port = if (u.port > 0) u.port else 80
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(u.host, port), connectTimeoutMs)
            socket.soTimeout = 60_000
            val target = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
            val head = StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(u.host).append(':').append(port).append("\r\n")
                .append("Connection: close\r\n")
                .append("X-Marginalia: 1\r\n")
                .apply { if (key != null) append("Authorization: Bearer ").append(key).append("\r\n") }
                .apply {
                    if (body != null) append("Content-Type: application/json\r\nContent-Length: ").append(body.size).append("\r\n")
                    else if (method != "GET" && method != "HEAD") append("Content-Length: 0\r\n")
                }
                .append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()

            val inp = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val status = readLine(inp) ?: throw IOException("The tablet closed the connection.")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Unexpected answer: $status")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':'); if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val stream: InputStream = when {
                headers["transfer-encoding"]?.contains("chunked", true) == true -> Chunked(inp)
                headers["content-length"]?.toLongOrNull() != null -> Limited(inp, headers["content-length"]!!.toLong())
                else -> inp
            }
            return Response(code, headers, stream, socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readLine(inp: InputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 16 * 1024) throw IOException("Header line too long.")
        }
    }

    private class Limited(inp: InputStream, private var left: Long) : FilterInputStream(inp) {
        override fun read(): Int { if (left <= 0) return -1; val c = super.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
    }

    private class Chunked(private val inp: InputStream) : InputStream() {
        private var left = 0L; private var done = false
        private fun next(): Boolean {
            if (done) return false
            if (left == 0L) {
                var line = readLine(inp) ?: return false.also { done = true }
                if (line.isEmpty()) line = readLine(inp) ?: return false.also { done = true }
                left = line.substringBefore(';').trim().toLong(16)
                if (left == 0L) { done = true; return false }
            }
            return true
        }
        override fun read(): Int { if (!next()) return -1; val c = inp.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
        }
    }
}
