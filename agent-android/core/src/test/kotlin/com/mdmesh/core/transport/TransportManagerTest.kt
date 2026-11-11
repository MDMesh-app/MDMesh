package com.mdmesh.core.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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
import kotlin.random.Random

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
    private val logs = mutableListOf<String>()
    private val transport = TransportManager(
        sockets = sockets,
        baseUrl = { "https://mdm.example" },
        scope = scope,
        clock = { scope.currentTime },
        jitter = { it }, // the bound itself: deterministic
        log = { logs += it },
    )

    private fun advance(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }

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

    @Test
    fun `the backoff doubles per failure up to five minutes`() {
        start()
        val waits = mutableListOf<Long>()
        repeat(12) {
            val before = scope.currentTime
            sockets.opened.last().fail()
            val count = sockets.opened.size
            while (sockets.opened.size == count) advance(1_000)
            waits += scope.currentTime - before
        }
        assertEquals(
            listOf(1L, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300, 300).map { it * 1_000 },
            waits,
        )
    }

    @Test
    fun `a stable session resets the backoff, a short one does not`() {
        start()
        repeat(3) { sockets.opened.last().fail(); advance(10 * 60_000L) } // failures = 3
        sockets.opened.last().open()
        advance(5_000)
        sockets.opened.last().fail()
        assertTrue(logs.last(), logs.last().contains("retry in 8000ms"))

        advance(10 * 60_000L)
        sockets.opened.last().open()
        advance(TransportManager.STABLE_SESSION_MS)
        sockets.opened.last().fail()
        assertTrue(logs.last(), logs.last().contains("retry in 1000ms"))
    }

    @Test
    fun `network return cuts a backoff short`() {
        start()
        repeat(6) { sockets.opened.last().fail(); advance(10 * 60_000L) }
        sockets.opened.last().fail() // next try in 64s
        assertEquals("Backoff", transport.stateName)

        transport.onNetworkAvailable()
        assertEquals(8, sockets.opened.size)
        assertEquals("Connecting", transport.stateName)
        advance(10 * 60_000L)
        assertEquals("the cancelled backoff must not connect again", 8, sockets.opened.size)
    }

    @Test
    fun `network return does nothing unless backing off`() {
        transport.onNetworkAvailable()
        assertEquals("Stopped", transport.stateName)
        start()
        sockets.opened[0].open()
        transport.onNetworkAvailable()
        assertEquals(1, sockets.opened.size)
        assertEquals("Connected", transport.stateName)
    }

    @Test
    fun `transitions are logged without the device id or secret`() {
        start()
        sockets.opened[0].open()
        sockets.opened[0].fail()
        transport.stop()
        assertEquals(
            listOf(
                "Stopped -> Connecting gen=1",
                "Connecting -> Connected gen=1",
                "Connected -> Backoff gen=2 retry in 1000ms failures=1",
                "Backoff -> Stopped gen=3",
            ),
            logs,
        )
        assertTrue(logs.none { "dev-1" in it || "sek" in it || "mdm.example" in it })
    }

    @Test
    fun `full jitter stays between the minimum and the bound`() {
        val random = Random(42)
        val bound = 64_000L
        val picks = (1..1_000).map { TransportManager.fullJitter(bound, random) }
        assertTrue(picks.all { it in TransportManager.MIN_BACKOFF_MS..bound })
        assertTrue("spread, not lock-step", picks.toSet().size > 900)
        assertEquals(TransportManager.MIN_BACKOFF_MS, TransportManager.fullJitter(1_000L))
    }
}
