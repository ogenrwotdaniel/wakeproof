package com.ogenrwotdaniel.wakeproof.clip

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal HTTP/1.1 POST over a raw socket, for the clip LAN transport only.
 *
 * Why not OkHttp: targetSdk ≥ 28 forbids cleartext HTTP through every platform-aware
 * HTTP stack ("cleartext communication to 10.x not permitted by network security
 * policy"), and a network-security-config cannot whitelist an IP RANGE — the laptop's
 * campus address changes. Allowing cleartext app-wide would also cover every other
 * host. The LAN payloads are already AES-GCM sealed (docs/CLIPBOARD.md), so plain
 * HTTP carries nothing readable, and a raw socket is not subject to the policy.
 * Blocking; call on Dispatchers.IO.
 */
internal object LanHttp {

    /** The status code and body of a reply. */
    class Reply(val code: Int, val body: ByteArray)

    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_REPLY_BYTES = 1024 * 1024

    fun post(
        host: String,
        port: Int,
        path: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Reply {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val hostHeader = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val head = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
            return readReply(BufferedInputStream(socket.getInputStream()))
        }
    }

    private fun readReply(input: InputStream): Reply {
        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed before the reply head ended")
            if (++total > MAX_HEAD_BYTES) throw IOException("reply head too large")
            if (b == '\n'.code) {
                val text = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) break
                lines += text
            } else {
                line.write(b)
            }
        }
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("malformed status line")
        val length = lines.drop(1).firstNotNullOfOrNull { h ->
            val colon = h.indexOf(':')
            if (colon > 0 && h.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                h.substring(colon + 1).trim().toIntOrNull()
            } else null
        }
        val body = if (length != null) {
            if (length < 0 || length > MAX_REPLY_BYTES) throw IOException("reply body too large")
            val bytes = ByteArray(length)
            var at = 0
            while (at < length) {
                val n = input.read(bytes, at, length - at)
                if (n < 0) throw IOException("reply body truncated")
                at += n
            }
            bytes
        } else {
            // Connection: close with no length — read to EOF, capped.
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_REPLY_BYTES) throw IOException("reply body too large")
            }
            buf.toByteArray()
        }
        return Reply(status, body)
    }
}
