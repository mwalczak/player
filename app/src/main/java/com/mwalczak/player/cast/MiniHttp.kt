package com.mwalczak.player.cast

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Just enough HTTP/1.1 for the phone -> TV remote: one request per connection,
 * a Content-Length body, JSON responses. Kept free of Android types so it is unit-testable.
 */
object MiniHttp {

    data class Request(val method: String, val path: String, val body: String)

    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    fun readRequest(input: InputStream): Request {
        val requestLine = readLine(input) ?: throw IOException("empty request")
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("bad request line")
        var contentLength = 0
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) throw IOException("headers too large")
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength !in 0..MAX_BODY_BYTES) throw IOException("body too large")
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) throw IOException("truncated body")
            read += n
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), String(body, Charsets.UTF_8))
    }

    fun writeJson(output: OutputStream, code: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /** Reads a CRLF (or LF) terminated line; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("line too long")
        }
        return buf.toString(Charsets.US_ASCII.name())
    }
}
