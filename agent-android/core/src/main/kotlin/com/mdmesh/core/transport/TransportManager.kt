package com.mdmesh.core.transport

import android.os.SystemClock
import android.util.Log
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.di.ApiHttpClient
import com.mdmesh.core.di.IoDispatcher
import com.mdmesh.core.sync.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Owns the agent wake channel: one WebSocket to `wss://<base>/agent/ws/{deviceId}`, kept alive (OkHttp ping) and
 * reconnected with capped, jittered backoff. Each wake invokes the host's `onWake` (the foreground service maps that
 * to a check-in or a fast-sync burst).
 *
 * Wake-to-sync: the socket carries only a tiny signal, so an idle device transfers ~nothing; the
 * actual command pull happens over the authenticated HTTPS /checkin. The WorkManager floor is the
 * backstop when the socket is down.
 *
 * The channel is a small state machine ([State]) and every transition happens under this object's lock. Each socket
 * gets a generation number, and its callbacks act only while the state still holds that generation. So a socket that
 * was stopped or replaced can never schedule a reconnect, wake the host, or stay open next to its successor (the
 * server closes a device's older session, so two socket chains would keep closing each other).
 */
@Singleton
class TransportManager internal constructor(
    private val sockets: WebSocket.Factory,
    private val baseUrl: () -> String,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds, for the stable-session rule. */
    private val clock: () -> Long,
    /** Picks the actual wait for a backoff whose upper bound is the argument. */
    private val jitter: (Long) -> Long = { fullJitter(it) },
    private val log: (String) -> Unit = {},
    /** Emits when a check-in reached the server: proof it is up, so a pending backoff is cut short. */
    serverReachable: Flow<Unit> = emptyFlow(),
) {
    @Inject constructor(
        @ApiHttpClient okHttpClient: OkHttpClient,
        serverConfig: ServerConfigStore,
        @IoDispatcher io: CoroutineDispatcher,
        syncStatus: SyncStatus,
    ) : this(
        // Keepalive ping kept just under typical proxy idle timeouts (~100s) to hold the socket open
        // with the fewest radio wakeups. (When idle on battery in adaptive mode the socket is dropped
        // entirely, so this cost only applies while the socket is intentionally held hot.)
        sockets = okHttpClient.newBuilder().pingInterval(50, TimeUnit.SECONDS).build(),
        baseUrl = serverConfig::baseUrl,
        scope = CoroutineScope(SupervisorJob() + io),
        clock = SystemClock::elapsedRealtime,
        log = { Log.i(TAG, it) },
        serverReachable = syncStatus.successes,
    )

    init {
        // A server outage can push the backoff to its 5-minute cap; the next successful check-in (heartbeat or
        // worker) brings the socket back within that cycle instead.
        scope.launch { serverReachable.collect { retryNow("server reachable") } }
    }

    internal sealed class State(val name: String) {
        object Stopped : State("Stopped")
        class Connecting(val gen: Int, val socket: WebSocket) : State("Connecting")
        class Connected(val gen: Int, val socket: WebSocket, val since: Long) : State("Connected")
        class Backoff(val gen: Int, val delayMs: Long, val job: Job) : State("Backoff")
    }

    // All guarded by `this`.
    private var state: State = State.Stopped
    private var generation = 0
    /** Drops since the last stable session; sets the backoff bound. Kept across stop/start. */
    private var failures = 0
    private var deviceId: String = ""
    private var secret: String = ""
    private var onWake: (suspend (WakeSignal) -> Unit)? = null

    internal val stateName: String get() = synchronized(this) { state.name }

    /** Start (or restart) the wake channel for this device. Idempotent while running. */
    @Synchronized
    fun start(deviceId: String, secret: String, onWake: suspend (WakeSignal) -> Unit) {
        this.deviceId = deviceId
        this.secret = secret
        this.onWake = onWake
        if (state == State.Stopped) connect()
    }

    /** Close the socket and drop any pending reconnect: nothing reopens it until [start]. */
    @Synchronized
    fun stop() {
        when (val s = state) {
            is State.Connecting -> s.socket.cancel()
            is State.Connected -> s.socket.cancel()
            is State.Backoff -> s.job.cancel()
            State.Stopped -> return
        }
        moveTo(State.Stopped, ++generation)
    }

    /** The device has a network again: don't sit out the rest of a backoff. */
    fun onNetworkAvailable() = retryNow("network available")

    /**
     * Ends a pending backoff early. The failure count stays, so if the socket itself keeps failing (a proxy that
     * blocks WebSockets) each hint costs one attempt, not a fresh run of short backoffs.
     */
    @Synchronized
    private fun retryNow(reason: String) {
        val s = state as? State.Backoff ?: return
        s.job.cancel()
        log("$reason, retrying now")
        connect()
    }

    /** Caller holds the lock. */
    private fun connect() {
        val gen = ++generation
        val request = Request.Builder()
            .url("${baseUrl()}/agent/ws/$deviceId")
            // Secret goes in the handshake header, never the URL (query strings leak into logs).
            .addHeader("Authorization", "Bearer $secret")
            .build()
        moveTo(State.Connecting(gen, sockets.newWebSocket(request, Listener(gen))), gen)
    }

    @Synchronized
    private fun opened(gen: Int, socket: WebSocket) {
        val s = state
        if (s !is State.Connecting || s.gen != gen) return socket.cancel() // superseded
        moveTo(State.Connected(gen, socket, clock()), gen)
    }

    @Synchronized
    private fun received(gen: Int, socket: WebSocket, text: String) {
        val s = state
        if (s !is State.Connected || s.gen != gen) return socket.cancel() // superseded
        val signal = WakeSignal.parse(text) ?: return
        val cb = onWake ?: return
        scope.launch {
            runCatching { cb(signal) }
                .onFailure { Log.w(TAG, "wake handler failed", it) }
        }
    }

    /** The socket failed, or the server closed it: back off, then reconnect. */
    @Synchronized
    private fun dropped(gen: Int) {
        val since = when (val s = state) {
            is State.Connecting -> if (s.gen == gen) null else return
            is State.Connected -> if (s.gen == gen) s.since else return
            else -> return // stopped, or this socket already reported and its reconnect is pending
        }
        // Reset the backoff only after a stable session (open ≥30s): resetting on open let a connection that dies
        // right after the handshake pin the retry delay at its minimum.
        if (since != null && clock() - since >= STABLE_SESSION_MS) failures = 0
        val delayMs = jitter(backoffBound(failures))
        failures++
        val next = ++generation
        val job = scope.launch {
            delay(delayMs)
            backoffElapsed(next)
        }
        moveTo(State.Backoff(next, delayMs, job), next)
    }

    @Synchronized
    private fun backoffElapsed(gen: Int) {
        val s = state
        if (s is State.Backoff && s.gen == gen) connect()
    }

    /** Caller holds the lock. Logs no device id, URL or secret. */
    private fun moveTo(next: State, gen: Int) {
        val from = state
        state = next
        val extra = if (next is State.Backoff) " retry in ${next.delayMs}ms failures=$failures" else ""
        log("${from.name} -> ${next.name} gen=$gen$extra")
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = opened(gen, webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) = received(gen, webSocket, text)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(gen)

        // A server-initiated clean close is a failure for backoff purposes too — otherwise
        // an accept-then-close server drives a reconnect every second, forever.
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen)
    }

    internal companion object {
        const val TAG = "TransportManager"
        const val STABLE_SESSION_MS = 30_000L
        const val MIN_BACKOFF_MS = 1_000L
        /** A server outage costs an always-on device about a dozen dials an hour instead of sixty. */
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** 1s, 2s, 4s … doubling per failure, capped at [MAX_BACKOFF_MS]. */
        fun backoffBound(failures: Int): Long =
            minOf(MAX_BACKOFF_MS, MIN_BACKOFF_MS shl minOf(failures, 20))

        /**
         * "Full jitter": anywhere from the minimum up to [bound], so a fleet that lost its sockets together (a server
         * restart) does not reconnect in lock-step.
         */
        fun fullJitter(bound: Long, random: Random = Random.Default): Long =
            if (bound <= MIN_BACKOFF_MS) MIN_BACKOFF_MS else random.nextLong(MIN_BACKOFF_MS, bound + 1)
    }
}
