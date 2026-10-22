package com.example.server

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A request as read from the socket. The body is read on demand from [body]. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val remote: String,
    private val input: InputStream,
    val contentLength: Long
) {
    private var consumed = 0L

    fun header(name: String): String = headers[name.lowercase(Locale.ROOT)].orEmpty()

    fun cookie(name: String): String = header("cookie").split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=').orEmpty()

    /** The body as a stream limited to Content-Length. */
    val body: InputStream = object : InputStream() {
        override fun read(): Int {
            if (consumed >= contentLength) return -1
            val b = input.read()
            if (b >= 0) consumed++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= contentLength) return -1
            val n = input.read(b, off, minOf(len.toLong(), contentLength - consumed).toInt())
            if (n > 0) consumed += n
            return n
        }
    }

    fun bodyText(limit: Int = 4 * 1024 * 1024): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = body.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IllegalArgumentException("Request too large")
        }
        return out.toString("UTF-8")
    }

    /** Reads and drops whatever the handler left unread, so the connection can be reused. */
    fun drain() {
        val buf = ByteArray(16 * 1024)
        while (body.read(buf) >= 0) Unit
    }
}

sealed class HttpResponse(val status: Int) {
    val headers = LinkedHashMap<String, String>()
    fun header(name: String, value: String) = apply { headers[name] = value }

    class Bytes(status: Int, val contentType: String, val bytes: ByteArray) : HttpResponse(status)

    /** A file, with Range support. [downloadName] makes the browser save it under that name. */
    class FileBody(val file: File, val contentType: String, val downloadName: String? = null) : HttpResponse(200)

    /** An input stream of unknown length (sent with Connection: close). */
    class Stream(status: Int, val contentType: String, val open: () -> InputStream) : HttpResponse(status)

    companion object {
        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") = Bytes(status, type, text.toByteArray(Charsets.UTF_8))
        fun json(text: String, status: Int = 200) = Bytes(status, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        fun error(status: Int, message: String) = json(org.json.JSONObject().put("error", message).toString(), status)
    }
}

/**
 * A small HTTP/1.1 server for the local network: keep-alive, Range requests for files and
 * streamed request bodies (uploads go straight to disk). No external dependencies.
 */
class MiniHttpServer(private val handler: (HttpRequest) -> HttpResponse) {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val running = AtomicBoolean(false)
    var port: Int = 0
        private set

    fun start(preferredPorts: List<Int>) {
        var lastError: Exception? = null
        for (p in preferredPorts) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), p), 64)
                socket = s
                port = s.localPort
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        val server = socket ?: throw (lastError ?: IllegalStateException("No free port"))
        running.set(true)
        val workers = Executors.newFixedThreadPool(12)
        pool = workers
        Thread({
            while (running.get()) {
                val client = try { server.accept() } catch (_: SocketException) { break } catch (e: Exception) { continue }
                try { workers.execute { serve(client) } } catch (_: Exception) { runCatching { client.close() } }
            }
        }, "LibraryServer-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        runCatching { pool?.shutdownNow() }
        socket = null
        pool = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 60_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val output = s.getOutputStream().buffered(64 * 1024)
                val remote = s.inetAddress?.hostAddress.orEmpty()
                var served = 0
                while (running.get() && served < 200) {
                    val requestLine = readLine(input) ?: return
                    if (requestLine.isBlank()) continue
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) return
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
                    }
                    val rawTarget = parts[1]
                    val path = decode(rawTarget.substringBefore('?'))
                    val query = rawTarget.substringAfter('?', "").split('&').filter { it.isNotBlank() }.associate { kv ->
                        decode(kv.substringBefore('=')) to decode(kv.substringAfter('=', ""))
                    }
                    val length = headers["content-length"]?.toLongOrNull() ?: 0L
                    val req = HttpRequest(parts[0].uppercase(Locale.ROOT), path, query, headers, remote, input, length)
                    val response = try {
                        handler(req)
                    } catch (e: Throwable) {
                        Log.e("LibraryServer", "Handler failed for ${req.method} $path", e)
                        HttpResponse.error(500, e.message ?: e.javaClass.simpleName)
                    }
                    runCatching { req.drain() }
                    val keepAlive = !headers["connection"].equals("close", true) && parts.getOrNull(2) == "HTTP/1.1"
                    val keep = write(req, response, output, keepAlive)
                    output.flush()
                    served++
                    if (!keep) return
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: SocketException) {
            } catch (e: Exception) {
                Log.w("LibraryServer", "Connection error", e)
            }
        }
    }

    private fun write(req: HttpRequest, r: HttpResponse, out: OutputStream, keepAlive: Boolean): Boolean {
        val head = req.method == "HEAD"
        fun statusLine(code: Int) = "HTTP/1.1 $code ${reason(code)}\r\n"
        fun common(sb: StringBuilder, keep: Boolean) {
            r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("Connection: ").append(if (keep) "keep-alive" else "close").append("\r\n")
            sb.append("X-Content-Type-Options: nosniff\r\n")
        }
        when (r) {
            is HttpResponse.Bytes -> {
                val sb = StringBuilder(statusLine(r.status))
                sb.append("Content-Type: ").append(r.contentType).append("\r\n")
                sb.append("Content-Length: ").append(r.bytes.size).append("\r\n")
                if (!r.headers.containsKey("Cache-Control")) sb.append("Cache-Control: no-store\r\n")
                common(sb, keepAlive)
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (!head) out.write(r.bytes)
                return keepAlive
            }
            is HttpResponse.FileBody -> {
                val total = r.file.length()
                var start = 0L
                var end = total - 1
                var partial = false
                val range = req.header("range")
                if (range.startsWith("bytes=") && total > 0) {
                    val spec = range.removePrefix("bytes=").substringBefore(',')
                    val a = spec.substringBefore('-').trim()
                    val b = spec.substringAfter('-', "").trim()
                    if (a.isEmpty() && b.isNotEmpty()) {
                        start = (total - (b.toLongOrNull() ?: total)).coerceAtLeast(0)
                    } else {
                        start = a.toLongOrNull() ?: 0L
                        if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(total - 1)
                    }
                    if (start > end || start >= total) {
                        val sb = StringBuilder(statusLine(416))
                        sb.append("Content-Range: bytes */").append(total).append("\r\nContent-Length: 0\r\n")
                        common(sb, keepAlive); sb.append("\r\n")
                        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                        return keepAlive
                    }
                    partial = true
                }
                val length = if (total == 0L) 0L else end - start + 1
                val sb = StringBuilder(statusLine(if (partial) 206 else 200))
                sb.append("Content-Type: ").append(r.contentType).append("\r\n")
                sb.append("Content-Length: ").append(length).append("\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
                if (!r.headers.containsKey("Cache-Control")) sb.append("Cache-Control: private, max-age=0, must-revalidate\r\n")
                if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                r.downloadName?.let { sb.append("Content-Disposition: ").append(contentDisposition(it)).append("\r\n") }
                common(sb, keepAlive)
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (!head && length > 0) {
                    java.io.RandomAccessFile(r.file, "r").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(128 * 1024)
                        var left = length
                        while (left > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                }
                return keepAlive
            }
            is HttpResponse.Stream -> {
                val sb = StringBuilder(statusLine(r.status))
                sb.append("Content-Type: ").append(r.contentType).append("\r\n")
                sb.append("Cache-Control: no-store\r\n")
                common(sb, false)
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (!head) r.open().use { it.copyTo(out, 128 * 1024) }
                return false
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16 * 1024) throw IllegalArgumentException("Header line too long")
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
        302 -> "Found"; 304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
        413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"
        else -> if (code >= 500) "Internal Server Error" else "OK"
    }

    companion object {
        fun contentDisposition(name: String): String {
            val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "xhtml" -> "application/xhtml+xml; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "zip" -> "application/zip"
            "md", "markdown" -> "text/markdown; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "bib" -> "application/x-bibtex; charset=utf-8"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
