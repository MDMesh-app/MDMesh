package com.mdmesh.core.transport

import android.os.SystemClock
import android.util.Log
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.di.ApiHttpClient
import com.mdmesh.core.di.AgentScope
import com.mdmesh.core.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val env: Env,
    /** Emits when a check-in reached the server: proof it is up, so a pending backoff is cut short. */
    serverReachable: Flow<Unit> = emptyFlow(),
) {
    /** The clock, randomness and log the channel uses: real ones in production, controlled ones in tests. */
    internal class Env(
        /** Monotonic milliseconds, for the session-length rules. */
        val clock: () -> Long,
        /** Picks each backoff's wait within its bound (see [fullJitter]). */
        val random: Random = Random.Default,
        val log: (String) -> Unit = {},
    )

    private val clock get() = env.clock
    private val random get() = env.random
    private val log get() = env.log

    @Inject constructor(
        @ApiHttpClient okHttpClient: OkHttpClient,
        serverConfig: ServerConfigStore,
        @AgentScope scope: CoroutineScope,
        syncStatus: SyncStatus,
    ) : this(
        // Keepalive ping kept just under typical proxy idle timeouts (~100s) to hold the socket open
        // with the fewest radio wakeups. (When idle on battery in adaptive mode the socket is dropped
        // entirely, so this cost only applies while the socket is intentionally held hot.)
        sockets = okHttpClient.newBuilder().pingInterval(50, TimeUnit.SECONDS).build(),
        baseUrl = serverConfig::baseUrl,
        scope = scope,
        env = Env(clock = SystemClock::elapsedRealtime, log = { Log.i(TAG, it) }),
        serverReachable = syncStatus.successes,
    )

    init {
        // A server outage can push the backoff to its 5-minute cap; the next successful check-in (heartbeat or
        // worker) brings the socket back within that cycle instead. Only when the socket never opened, though: if it
        // opened and was then cut, the server was reachable all along, and the check-in may even be the one this
        // socket's own wake started (open -> wake -> check-in -> cut -> retry -> open ... would defeat the backoff).
        scope.launch { serverReachable.collect { retryNow("server reachable", onlyIfNeverOpened = true) } }
    }

    internal sealed class State(val name: String) {
        object Stopped : State("Stopped")
        /** [socket] is null only while it is being created. */
        class Connecting(val gen: Int) : State("Connecting") { var socket: WebSocket? = null }
        class Connected(val gen: Int, val socket: WebSocket, val since: Long) : State("Connected")
        /** [afterOpen]: the dropped socket had opened, so the server was reachable. */
        class Backoff(val gen: Int, val delayMs: Long, val boundMs: Long, val job: Job, val afterOpen: Boolean) :
            State("Backoff")
    }

    // All guarded by `this`.
    private var state: State = State.Stopped
    private var generation = 0
    /** Drops since the last stable session; sets the backoff bound. Kept across stop/start unless that ended one. */
    private var failures = 0
    private var deviceId: String = ""
    private var secret: String = ""
    private var onWake: (suspend (WakeSignal) -> Unit)? = null

    internal val stateName: String get() = synchronized(this) { state.name }

    /**
     * Start (or restart) the wake channel for this device. Idempotent while running with the same credentials.
     *
     * New credentials (a recovered enrollment's secret, a re-enrollment) replace the running socket or pending
     * reconnect at once, with a fresh backoff: its failures were the old credentials' (a refused secret is closed
     * 1008, which waits minutes), and the socket must not keep dialling, or stay open, with a dropped secret.
     */
    @Synchronized
    fun start(deviceId: String, secret: String, onWake: suspend (WakeSignal) -> Unit) {
        val changed = deviceId != this.deviceId || secret != this.secret
        this.deviceId = deviceId
        this.secret = secret
        this.onWake = onWake
        if (changed) {
            failures = 0
            if (state != State.Stopped) {
                log("credentials changed, reconnecting")
                stop() // re-entrant lock
            }
        }
        if (state == State.Stopped) connect()
    }

    /** Close the socket and drop any pending reconnect: nothing reopens it until [start]. */
    @Synchronized
    fun stop() {
        when (val s = state) {
            is State.Connecting -> s.socket?.cancel()
            is State.Connected -> {
                if (clock() - s.since >= STABLE_SESSION_MS) failures = 0 // a healthy session, ended by us
                s.socket.cancel()
            }
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
    private fun retryNow(reason: String, onlyIfNeverOpened: Boolean = false) {
        val s = state as? State.Backoff ?: return
        if (onlyIfNeverOpened && s.afterOpen) return
        s.job.cancel()
        log("$reason, retrying now")
        connect()
    }

    /**
     * Caller holds the lock. The state is Connecting before the socket exists: OkHttp may report a failure on this
     * thread before `newWebSocket` returns (the lock is re-entrant), and that must find the attempt it belongs to.
     */
    private fun connect() {
        val gen = ++generation
        val connecting = State.Connecting(gen)
        moveTo(connecting, gen)
        val socket = try {
            val request = Request.Builder()
                .url("${baseUrl()}/agent/ws/$deviceId")
                // Secret goes in the handshake header, never the URL (query strings leak into logs).
                .addHeader("Authorization", "Bearer $secret")
                .build()
            sockets.newWebSocket(request, Listener(gen))
        } catch (e: IllegalArgumentException) {
            // A bad provisioned address (OkHttp rejects the URL). Runs on OkHttp/connectivity threads too, where a
            // throw would crash.
            log("connect failed: ${e.javaClass.simpleName}")
            dropped(gen, code = null)
            return
        }
        if (state === connecting) connecting.socket = socket else socket.cancel() // already failed meanwhile
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
        val signal = WakeSignal.parse(text)
        val cb = onWake
        if (signal != null && cb != null) {
            scope.launch {
                runCatching { cb(signal) }
                    .onFailure { Log.w(TAG, "wake handler failed", it) }
            }
        }
    }

    /**
     * The socket failed ([code] null) or the server closed it with [code]: back off, then reconnect.
     *
     * The backoff resets only after a stable session (open ≥30s) that ended in a network failure or a server
     * shutdown (1001). Resetting on open let a connection that dies right after the handshake pin the retry at its
     * minimum. Any other close the server chose (1008 unauthorized for a device deleted in the console, 1000 when
     * another connection for this device replaced the session) is the server saying "not this socket", so it never
     * resets, and 1008 starts at a 64s bound and waits at least half of it (32s, growing to 2.5-5 min). After a
     * session of [LONG_SESSION_MS] or more every close starts from the bottom again (a 1008 still at its 64s floor).
     */
    @Synchronized
    private fun dropped(gen: Int, code: Int?) {
        val s = state
        // Not ours when stopped, superseded, or this socket already reported and its reconnect is pending.
        val ours = (s is State.Connecting && s.gen == gen) || (s is State.Connected && s.gen == gen)
        if (!ours) return
        val since = (s as? State.Connected)?.since
        val lasted = if (since == null) 0L else clock() - since
        val stable = lasted >= STABLE_SESSION_MS
        // After a long healthy session the old failure count says nothing about now (it may still sit at the cap
        // from an outage days ago), so a later close is retried promptly whatever its code.
        if (lasted >= LONG_SESSION_MS) failures = 0
        when (code) {
            null, CLOSE_GOING_AWAY -> if (stable) failures = 0
            CLOSE_POLICY -> failures = maxOf(failures, POLICY_MIN_FAILURES)
        }
        val bound = backoffBound(failures)
        val delayMs = if (code == CLOSE_POLICY) policyJitter(bound, random) else fullJitter(bound, random)
        failures++
        val next = ++generation
        val job = scope.launch {
            delay(delayMs)
            backoffElapsed(next)
        }
        val why = if (code == null) "" else " close=$code"
        moveTo(State.Backoff(next, delayMs, bound, job, afterOpen = since != null), next, why)
    }

    @Synchronized
    private fun backoffElapsed(gen: Int) {
        val s = state
        if (s is State.Backoff && s.gen == gen) connect()
    }

    /** Caller holds the lock. Logs no device id, URL or secret. */
    private fun moveTo(next: State, gen: Int, why: String = "") {
        val from = state
        state = next
        val extra = if (next is State.Backoff) {
            " retry in ${next.delayMs}ms bound=${next.boundMs}ms failures=$failures"
        } else {
            ""
        }
        log("${from.name} -> ${next.name} gen=$gen$why$extra")
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = opened(gen, webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) = received(gen, webSocket, text)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(gen, code = null)

        // OkHttp reports a close the server starts as onClosing only; onClosed follows once we answer. So answer at
        // once and back off now: waiting for a failed ping left a dead socket "Connected" for up to 100s (wakes
        // lost) and then counted it as a stable session.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dropped(gen, code)
            webSocket.close(CLOSE_NORMAL, null)
        }

        // Normally stale by now (onClosing already moved on); covers a close we started ourselves.
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen, code)
    }

    internal companion object {
        const val TAG = "TransportManager"
        const val STABLE_SESSION_MS = 30_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val CLOSE_NORMAL = 1000
        const val CLOSE_GOING_AWAY = 1001
        const val CLOSE_POLICY = 1008
        /** A 1008 close starts at a 64s bound (waits 32-64s): a deleted device must not dial every second. */
        const val POLICY_MIN_FAILURES = 6
        /** A server outage costs an always-on device about a dozen dials an hour instead of sixty. */
        const val MAX_BACKOFF_MS = 5 * 60_000L

        /** A session this long proves the channel healthy, whatever ended it (twice the backoff cap). */
        const val LONG_SESSION_MS = 10 * 60_000L

        /** Far past the cap already; keeps the shift from overflowing. */
        private const val MAX_DOUBLINGS = 20

        /** For a 1008 close: anywhere in the upper half of [bound], so a deleted device never retries in seconds. */
        fun policyJitter(bound: Long, random: Random = Random.Default): Long = random.nextLong(bound / 2, bound + 1)

        /** 1s, 2s, 4s … doubling per failure, capped at [MAX_BACKOFF_MS]: the bound [fullJitter] picks under. */
        fun backoffBound(failures: Int): Long =
            minOf(MAX_BACKOFF_MS, MIN_BACKOFF_MS shl minOf(failures, MAX_DOUBLINGS))

        /**
         * "Full jitter": anywhere from half a second up to [bound] (1.5s for the first retry), so a fleet that lost
         * its sockets together (a server restart closes them all at once) does not reconnect in lock-step, not even
         * on the first try.
         */
        fun fullJitter(bound: Long, random: Random = Random.Default): Long {
            val low = MIN_BACKOFF_MS / 2
            val high = if (bound <= MIN_BACKOFF_MS) MIN_BACKOFF_MS + low else bound
            return random.nextLong(low, high + 1)
        }
    }
}
