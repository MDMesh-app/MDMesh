package com.mdmesh.core.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TransportManagerTest {

    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        /** Closed by the agent, or dead on its own (failed / closed by the server). */
        var cancelled = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true.also { cancelled = true }
        override fun cancel() { cancelled = true }

        fun open() = listener.onOpen(this, Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun fail() { cancelled = true; listener.onFailure(this, IOException("reset"), null) }
        fun closed() { cancelled = true; listener.onClosed(this, 1000, "replaced") }
        fun wake() = listener.onMessage(this, """{"wake":"commands"}""")
    }

    private class FakeSockets : WebSocket.Factory {
        val opened = mutableListOf<FakeSocket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also { opened += it }
        val live get() = opened.filterNot { it.cancelled }
    }

    private val scope = TestScope()
    private val sockets = FakeSockets()
    private val wakes = mutableListOf<WakeSignal>()
    private val transport = TransportManager(sockets, { "https://mdm.example" }, scope)

    private fun start() = transport.start("dev-1", "sek") { wakes += it }

    @Test
    fun `a dropped socket reconnects after the backoff`() {
        start()
        sockets.opened[0].fail()
        scope.advanceTimeBy(999); scope.runCurrent()
        assertEquals(1, sockets.opened.size)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(2, sockets.opened.size)
    }

    @Test
    fun `stop closes the socket and cancels a pending reconnect`() {
        start()
        sockets.opened[0].fail()
        transport.stop()
        scope.advanceTimeBy(10 * 60_000L); scope.runCurrent()
        assertEquals(1, sockets.opened.size)

        start()
        transport.stop()
        assertTrue("socket left open after stop()", sockets.live.isEmpty())
    }

    @Test
    fun `a reconnect pending across stop and start does not open a second socket`() {
        start()
        sockets.opened[0].fail() // reconnect due in 1s
        transport.stop() // screen off
        start() // screen on: socket #2
        scope.advanceTimeBy(10 * 60_000L); scope.runCurrent()

        assertEquals(2, sockets.opened.size)
        assertEquals(listOf(sockets.opened[1]), sockets.live)
    }

    @Test
    fun `a superseded socket's late callbacks are ignored`() {
        start()
        val old = sockets.opened[0]
        transport.stop()
        start()
        // The cancelled socket's callbacks arrive after the new one is up.
        old.open()
        old.wake()
        old.fail()
        old.closed()
        scope.advanceTimeBy(10 * 60_000L); scope.runCurrent()

        assertEquals("no reconnect from a superseded socket", 2, sockets.opened.size)
        assertFalse("the current socket must stay up", sockets.opened[1].cancelled)
        assertTrue("no wake from a superseded socket", wakes.isEmpty())
    }

    @Test
    fun `one dead socket schedules one reconnect even when it reports twice`() {
        start()
        sockets.opened[0].fail()
        sockets.opened[0].closed()
        scope.advanceTimeBy(10 * 60_000L); scope.runCurrent()
        assertEquals(2, sockets.opened.size)
    }

    @Test
    fun `the current socket's wake reaches the host`() {
        start()
        sockets.opened[0].open()
        sockets.opened[0].wake()
        scope.runCurrent()
        assertEquals(listOf(WakeSignal("commands")), wakes)
    }
}
