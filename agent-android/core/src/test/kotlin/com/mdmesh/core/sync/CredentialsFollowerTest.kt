package com.mdmesh.core.sync

import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.transport.TransportManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wake socket follows the saved credentials, through the real [TransportManager] over fake sockets. */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialsFollowerTest {

    private class Socket(private val request: Request) : WebSocket {
        var cancelled = false
        val bearer get() = request.header("Authorization")?.removePrefix("Bearer ")
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true.also { cancelled = true }
        override fun cancel() { cancelled = true }
    }

    private class Sockets : WebSocket.Factory {
        val opened = mutableListOf<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request).also { opened += it }
        /** The secret each live socket dials with. */
        val live get() = opened.filterNot { it.cancelled }.map { it.bearer }
    }

    /** The saved credentials, set directly. */
    private class Saved(var id: String? = null, var secret: String? = null) : DeviceIdentity {
        override suspend fun current() = id
        override suspend fun secret() = secret
        override suspend fun saveCredentials(id: String, secret: String?) { this.id = id; this.secret = secret }
    }

    private val scope = TestScope() // nothing runs until runCurrent()
    private val sockets = Sockets()
    private val saved = Saved()
    private val successes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var hot = true
    private val transport = TransportManager(
        sockets = sockets,
        baseUrl = { "https://mdm.example" },
        scope = scope,
        env = TransportManager.Env(clock = { scope.currentTime }),
    )

    private fun follower() = CredentialsFollower(scope, successes, saved, transport, hot = { hot }, onWake = {})

    /** What the service's start does after its first sync. */
    private fun CredentialsFollower.loadAndReevaluate() {
        scope.launch { load(); reevaluate() }
        scope.runCurrent()
    }

    private fun checkInSucceeds() {
        successes.tryEmit(Unit)
        scope.runCurrent()
    }

    @Test
    fun `a check-in that recovers the secret moves the socket onto it`() {
        saved.id = "dev-1"; saved.secret = "sek-dead"
        val follower = follower()
        follower.loadAndReevaluate()
        assertEquals(listOf("sek-dead"), sockets.live)

        saved.secret = "sek-live" // the recovery saved the secret the server holds
        checkInSucceeds()

        assertEquals(listOf("sek-live"), sockets.live)
        assertEquals(Credentials("dev-1", "sek-live"), follower.current)
    }

    @Test
    fun `it follows check-ins from the moment it exists, before the scope has run anything`() {
        // The service builds it in onCreate, before any start reads the identity. The enrolling check-in can
        // succeed before the scope's dispatcher gets to run; that success must still count.
        val follower = follower()
        saved.id = "dev-1"; saved.secret = "sek"
        successes.tryEmit(Unit) // dropped if nothing is subscribed yet
        scope.runCurrent()

        assertEquals(listOf("sek"), sockets.live)
        assertEquals(Credentials("dev-1", "sek"), follower.current)
    }

    @Test
    fun `an older read put back by a start is corrected at the next success`() {
        // The start coroutine read the identity before a recovery saved the new secret, and resumed after the
        // collector had already moved the socket on.
        saved.id = "dev-1"; saved.secret = "sek-live"
        val follower = follower()
        checkInSucceeds()
        assertEquals(listOf("sek-live"), sockets.live)

        saved.secret = "sek-dead"
        follower.loadAndReevaluate() // the stale read lands
        saved.secret = "sek-live"
        assertEquals(listOf("sek-dead"), sockets.live)

        checkInSucceeds() // the stored pair is the same as at the last success
        assertEquals(listOf("sek-live"), sockets.live)
    }

    @Test
    fun `successes with unchanged credentials leave the socket alone`() {
        saved.id = "dev-1"; saved.secret = "sek"
        follower().loadAndReevaluate()
        repeat(3) { checkInSucceeds() }
        assertEquals(1, sockets.opened.size)
    }

    @Test
    fun `new credentials while idle wait for the socket to be wanted`() {
        saved.id = "dev-1"; saved.secret = "sek-dead"
        hot = false
        val follower = follower()
        follower.loadAndReevaluate()
        saved.secret = "sek-live"
        checkInSucceeds()
        assertEquals(emptyList<String?>(), sockets.live)

        hot = true // screen on
        follower.reevaluate()
        assertEquals(listOf("sek-live"), sockets.live)
    }

    @Test
    fun `without credentials it re-reads them once and dials if enrollment finished elsewhere`() {
        val follower = follower()
        follower.loadAndReevaluate() // not enrolled yet
        assertNull(follower.current)
        assertEquals(0, sockets.opened.size)

        saved.id = "dev-1"; saved.secret = "sek" // the worker enrolled it
        follower.reevaluate()
        scope.runCurrent()
        assertEquals(listOf("sek"), sockets.live)
    }
}
