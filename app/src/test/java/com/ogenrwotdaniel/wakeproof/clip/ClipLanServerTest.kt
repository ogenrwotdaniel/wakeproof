package com.ogenrwotdaniel.wakeproof.clip

import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The LAN server answers anyone who can reach the port, before any key is checked.
 * These tests hold it to the one promise that matters there: nothing a stranger
 * sends may stop the listener or escape into the scope that hosts it — in the app
 * that scope has no handler, so an escaped failure ends the process, and with it a
 * ringing alarm and the app wall.
 */
class ClipLanServerTest {

    private val escaped = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> escaped += t }
    )
    private val server = ClipLanServer(port = 0) { _, _ -> 204 }
    private var port = -1

    @Before
    fun start() {
        server.start(scope)
        val giveUp = System.currentTimeMillis() + 5_000
        while (server.boundPort <= 0 && System.currentTimeMillis() < giveUp) Thread.sleep(10)
        port = server.boundPort
        assertTrue("server did not start listening", port > 0)
    }

    @After
    fun stop() {
        server.stop()
        scope.cancel()
    }

    private fun connect() = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }

    private fun ping(): String = connect().use { s ->
        s.getOutputStream().apply { write("GET /ping HTTP/1.1\r\n\r\n".toByteArray()); flush() }
        s.getInputStream().readBytes().decodeToString()
    }

    private fun assertStillServing() {
        val reply = ping()
        assertTrue("expected a 200 to /ping, got: $reply", reply.startsWith("HTTP/1.1 200"))
        assertTrue(reply.endsWith(ClipLan.PING_BODY))
        assertEquals("nothing may escape into the hosting scope", emptyList<Throwable>(), escaped.toList())
    }

    @Test
    fun `a clip posted whole is handed to the handler`() {
        val reply = connect().use { s ->
            s.getOutputStream().apply {
                write("POST /clip HTTP/1.1\r\nX-Clip-Id: abc\r\nContent-Length: 3\r\n\r\nxyz".toByteArray())
                flush()
            }
            s.getInputStream().readBytes().decodeToString()
        }
        assertTrue(reply, reply.startsWith("HTTP/1.1 204"))
        assertStillServing()
    }

    @Test
    fun `a connection reset in the middle of a body does not stop the server`() {
        assertStillServing()
        connect().use { s ->
            // Linger 0 makes close() send a reset, so the server's read of the
            // promised 100 bytes fails with an exception rather than a clean end.
            s.setSoLinger(true, 0)
            s.getOutputStream().apply {
                write("POST /clip HTTP/1.1\r\nX-Clip-Id: x\r\nContent-Length: 100\r\n\r\nabc".toByteArray())
                flush()
            }
            Thread.sleep(200)
        }
        Thread.sleep(300)
        assertStillServing()
        assertStillServing()
    }

    @Test
    fun `connections beyond the limit are turned away and the rest are served once they leave`() {
        // Four peers connect and say nothing, which is all the server allows at once.
        val idle = List(4) { connect() }
        Thread.sleep(300)
        // A fifth is dropped at the door: its read sees the end of the stream at
        // once rather than waiting behind the others.
        val turnedAway = connect().use { s ->
            runCatching {
                s.getOutputStream().apply { write("GET /ping HTTP/1.1\r\n\r\n".toByteArray()); flush() }
                s.getInputStream().readBytes().decodeToString()
            }.getOrDefault("")
        }
        assertEquals("", turnedAway)
        idle.forEach { it.close() }
        Thread.sleep(300)
        assertStillServing()
    }

    @Test
    fun `a body that ends early is refused and the server carries on`() {
        val reply = connect().use { s ->
            s.getOutputStream().apply {
                write("POST /clip HTTP/1.1\r\nX-Clip-Id: x\r\nContent-Length: 100\r\n\r\nabc".toByteArray())
                flush()
            }
            s.shutdownOutput()
            s.getInputStream().readBytes().decodeToString()
        }
        assertTrue(reply, reply.startsWith("HTTP/1.1 400"))
        assertStillServing()
    }
}
