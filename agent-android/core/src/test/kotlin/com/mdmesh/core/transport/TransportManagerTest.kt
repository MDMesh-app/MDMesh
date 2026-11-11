package com.mdmesh.core.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /**
     * Behaves like OkHttp 4.12's RealWebSocket: a close the server starts reaches the listener as `onClosing` only,
     * and `onClosed` follows only once the client answers with its own `close()`.
     */
    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        /** Closed or cancelled by the agent, or dead on its own (failed). */
        var cancelled = false
        var closedByAgentWith: Int? = null
        private var serverClosing: Pair<Int, String>? = null
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean {
            if (cancelled) return false
            cancelled = true
            closedByAgentWith = code
            serverClosing?.let { (c, r) -> listener.onClosed(this, c, r) }
            return true
        }
        override fun cancel() { cancelled = true }

        fun open() = listener.onOpen(this, Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun fail() { cancelled = true; listener.onFailure(this, IOException("reset"), null) }
        /** The server sends a close frame. */
        fun serverClose(code: Int, reason: String = "") {
            serverClosing = code to reason
            listener.onClosing(this, code, reason)
        }
        fun wake() = listener.onMessage(this, """{"wake":"commands"}""")
    }

    private class FakeSockets : WebSocket.Factory {
        val opened = mutableListOf<FakeSocket>()
        /** Like OkHttp when its dispatcher rejects the call: onFailure on the calling thread, before returning. */
        var failSynchronously = false
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also {
                opened += it
                if (failSynchronously) it.fail()
            }
        val live get() = opened.filterNot { it.cancelled }
    }

    private val scope = TestScope()
    private val sockets = FakeSockets()
    private val wakes = mutableListOf<WakeSignal>()
    private val logs = mutableListOf<String>()
    private var baseUrl: () -> String = { "https://mdm.example" }
    private val checkInSucceeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val transport = TransportManager(
        sockets = sockets,
        baseUrl = { baseUrl() },
        scope = scope,
        clock = { scope.currentTime },
        jitter = { it }, // the bound itself: deterministic
        log = { logs += it },
        serverReachable = checkInSucceeded,
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
        old.serverClose(1000, "replaced")
        old.fail()
        scope.advanceTimeBy(10 * 60_000L); scope.runCurrent()

        assertEquals("no reconnect from a superseded socket", 2, sockets.opened.size)
        assertFalse("the current socket must stay up", sockets.opened[1].cancelled)
        assertTrue("no wake from a superseded socket", wakes.isEmpty())
    }

    @Test
    fun `one dead socket schedules one reconnect even when it reports twice`() {
        start()
        sockets.opened[0].serverClose(1000)
        sockets.opened[0].fail()
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
    fun `a successful check-in cuts a backoff short`() {
        start()
        repeat(9) { sockets.opened.last().fail(); advance(10 * 60_000L) }
        sockets.opened.last().fail() // next try in 5 min: the server was down
        assertEquals("Backoff", transport.stateName)

        checkInSucceeded.tryEmit(Unit) // the server answered a check-in: it is back
        scope.runCurrent()
        assertEquals("Connecting", transport.stateName)
        assertEquals(11, sockets.opened.size)
        assertTrue(logs.any { it == "server reachable, retrying now" })
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
    fun `full jitter stays between half a second and the bound`() {
        val random = Random(42)
        val bound = 64_000L
        val picks = (1..1_000).map { TransportManager.fullJitter(bound, random) }
        assertTrue(picks.all { it in 500L..bound })
        assertTrue("spread, not lock-step", picks.toSet().size > 900)
    }

    /** The wait the last backoff chose, from its log line. */
    private fun lastRetryMs(): Long =
        Regex("retry in (\\d+)ms").find(logs.last { "retry in" in it })!!.groupValues[1].toLong()

    @Test
    fun `a close from the server is answered and backs off at once`() {
        start()
        val socket = sockets.opened[0]
        socket.open()
        socket.serverClose(1000, "replaced")

        assertEquals("answered with our own close", 1000, socket.closedByAgentWith)
        assertEquals("Backoff", transport.stateName)
        advance(10 * 60_000L)
        assertEquals("one reconnect, not one per callback", 2, sockets.opened.size)
    }

    @Test
    fun `an accept-then-close server makes the backoff grow`() {
        start()
        val waits = mutableListOf<Long>()
        repeat(5) {
            sockets.opened.last().open()
            advance(500)
            sockets.opened.last().serverClose(1000)
            waits += lastRetryMs()
            advance(10 * 60_000L)
        }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000), waits)
    }

    @Test
    fun `an unauthorized close never resets the backoff and waits at least a minute`() {
        // A device deleted in the console: the server accepts, then closes with 1008 "unauthorized".
        start()
        val waits = mutableListOf<Long>()
        repeat(3) {
            sockets.opened.last().open()
            advance(TransportManager.STABLE_SESSION_MS + 1)
            sockets.opened.last().serverClose(1008, "unauthorized")
            waits += lastRetryMs()
            advance(10 * 60_000L)
        }
        assertTrue(waits.toString(), waits.first() >= 60_000L)
        assertEquals(listOf(64_000L, 128_000, 256_000), waits)
    }

    @Test
    fun `a session the server replaced does not reset the backoff`() {
        start()
        repeat(3) { sockets.opened.last().fail(); advance(10 * 60_000L) }
        sockets.opened.last().open()
        advance(TransportManager.STABLE_SESSION_MS + 1)
        sockets.opened.last().serverClose(1000, "replaced")
        assertEquals(8_000L, lastRetryMs())
    }

    @Test
    fun `a server shutting down after a stable session is retried soon`() {
        start()
        repeat(3) { sockets.opened.last().fail(); advance(10 * 60_000L) }
        sockets.opened.last().open()
        advance(TransportManager.STABLE_SESSION_MS + 1)
        sockets.opened.last().serverClose(1001, "going away")
        assertEquals(1_000L, lastRetryMs())
    }

    @Test
    fun `a check-in that came through the socket does not cut the backoff`() {
        // open -> server wakes -> check-in succeeds -> the socket is cut: retrying "because the server is up" would
        // dial, get woken, check in and be cut again, forever.
        scope.runCurrent() // the check-in listener is subscribed
        start()
        sockets.opened[0].open()
        sockets.opened[0].wake()
        sockets.opened[0].fail()
        checkInSucceeded.tryEmit(Unit)
        scope.runCurrent()

        assertEquals("Backoff", transport.stateName)
        assertEquals(1, sockets.opened.size)
    }

    @Test
    fun `the first retry is spread too`() {
        val random = Random(7)
        val picks = (1..1_000).map { TransportManager.fullJitter(TransportManager.backoffBound(0), random) }
        assertTrue(picks.toString(), picks.all { it in 500L..1_500L })
        assertTrue("not lock-step", picks.toSet().size > 500)
    }

    @Test
    fun `a socket that fails while being created still backs off`() {
        sockets.failSynchronously = true
        start()
        assertEquals("Backoff", transport.stateName)
        sockets.failSynchronously = false
        advance(10 * 60_000L)
        assertEquals("Connecting", transport.stateName)
    }

    @Test
    fun `a stop after a stable session starts the next one with a short backoff`() {
        start()
        repeat(3) { sockets.opened.last().fail(); advance(10 * 60_000L) }
        sockets.opened.last().open()
        advance(TransportManager.STABLE_SESSION_MS + 1)
        transport.stop() // screen off
        start() // screen on
        sockets.opened.last().fail()
        assertEquals(1_000L, lastRetryMs())
    }

    @Test
    fun `a malformed server address backs off instead of crashing`() {
        baseUrl = { "not a url" }
        start()
        assertEquals("Backoff", transport.stateName)
        assertTrue(sockets.opened.isEmpty())
    }
}
