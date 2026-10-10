package com.llawsxx.uvclivestreaming.recording

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Sequential HTTP requests on one socket. Lifecycle operations may cancel I/O from another thread. */
internal class PersistentHttpConnection(url: String, private val watchdog: ScheduledExecutorService) : Closeable {
    data class Response(val status: Int, val headers: Map<String, String>, val transferNs: Long = 0L)
    private val uri = URI(url)
    private val guard = Any()
    private var transport: Socket? = null
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var active: Any? = null
    private var generation = 0L
    private var closed = false

    fun generation(): Long = synchronized(guard) { generation }

    /** Keep the endpoint usable, but cancel its current request and cached connection. */
    fun invalidate() = synchronized(guard) { invalidateLocked() }
    private fun invalidateLocked() {
        generation++
        runCatching { transport?.close() }
        transport = null; socket = null; input = null
    }
    override fun close() = synchronized(guard) { closed = true; invalidateLocked() }

    fun request(method: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
                deadlineNs: Long, readTimeoutMs: Int = 5_000, expectedGeneration: Long? = null): Response {
        val token = Any()
        val raw = synchronized(guard) {
            check(active == null) { "Concurrent requests on an HTTP connection" }
            if (closed || (expectedGeneration != null && expectedGeneration != generation))
                throw IOException("HTTP request cancelled")
            active = token
            transport ?: Socket().apply { tcpNoDelay = true; keepAlive = true; transport = this }
        }
        val timeout = watchdog.schedule({ synchronized(guard) {
            // A cancelled watchdog must never close the next request's reused socket.
            if (active === token) invalidateLocked()
        } }, (deadlineNs - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        var reusable = false
        try {
            val tls = uri.scheme == "https"
            val port = if (uri.port >= 0) uri.port else if (tls) 443 else 80
            if (!raw.isConnected) raw.connect(InetSocketAddress(uri.host, port), 5_000)
            raw.soTimeout = readTimeoutMs
            val connected = synchronized(guard) {
                if (closed || transport !== raw) throw IOException("HTTP request cancelled")
                socket
            } ?: if (tls) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, uri.host, port, true).let {
                    (it as SSLSocket).apply {
                        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        soTimeout = readTimeoutMs
                        startHandshake()
                    }
                }
            } else raw
            val stream = synchronized(guard) {
                if (closed || transport !== raw) { connected.close(); throw IOException("HTTP request cancelled") }
                socket = connected
                connected.soTimeout = readTimeoutMs
                input ?: connected.getInputStream().buffered().also { input = it }
            }
            val message = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:$port\r\nConnection: keep-alive\r\n")
                if (body != null) append("Content-Length: ${body.size}\r\n")
                headers.forEach { (key, value) -> append("$key: $value\r\n") }
                append("\r\n")
            }
            val transferStartedNs = System.nanoTime()
            connected.getOutputStream().apply {
                write(message.toByteArray(Charsets.US_ASCII)); if (body != null) write(body); flush()
            }
            var headerBytes = 0
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val value = stream.read()
                    check(value >= 0 && ++headerBytes <= 16_384) { "Incomplete or oversized HTTP response headers" }
                    if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                    bytes.write(value)
                }
            }
            var status: Int
            var version: String
            var response: Map<String, String>
            do {
                val first = line().split(' ')
                version = first.firstOrNull().orEmpty()
                check(version == "HTTP/1.1" || version == "HTTP/1.0") { "Invalid HTTP response" }
                status = checkNotNull(first.getOrNull(1)?.toIntOrNull()) { "Invalid HTTP status" }
                response = buildMap {
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        check(':' in header) { "Invalid HTTP response header" }
                        val key = header.substringBefore(':').lowercase()
                        check(!containsKey(key)) { "Duplicate HTTP response header" }
                        put(key, header.substringAfter(':').trim())
                    }
                }
            } while (status == 100)
            val length = response["content-length"]?.toLongOrNull()
            if (response["transfer-encoding"] == null && length != null) {
                check(length in 0..65_536) { "Oversized HTTP acknowledgement body" }
                var remaining = length.toInt()
                val buffer = ByteArray(4_096)
                while (remaining > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    check(count > 0) { "Incomplete HTTP acknowledgement body" }
                    remaining -= count
                }
                val connection = response["connection"]?.lowercase()?.split(',')?.map { it.trim() }.orEmpty()
                reusable = "close" !in connection && (version == "HTTP/1.1" || "keep-alive" in connection)
            }
            // Unframed/chunked ACKs remain compatible, with a fresh connection for the next request.
            return Response(status, response, (System.nanoTime() - transferStartedNs).coerceAtLeast(1))
        } finally {
            synchronized(guard) {
                if (transport === raw && !reusable) invalidateLocked()
                if (active === token) active = null
            }
            timeout.cancel(false)
        }
    }
}

internal fun TsUploadBlock.httpHeaders(): Map<String, String> = mapOf(
    "Content-Type" to "video/mp2t", "X-Session-ID" to session, "X-Sequence" to sequence.toString(),
    "X-Session-Started-Ms" to sessionStartedMs.toString(), "X-Start-Us" to startUs.toString(),
    "X-Duration-Us" to durationUs.toString(), "X-Final" to if (final) "1" else "0",
)
