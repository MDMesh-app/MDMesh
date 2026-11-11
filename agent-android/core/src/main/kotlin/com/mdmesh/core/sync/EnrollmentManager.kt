package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.config.ServerAddress
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.EnrollTokenProvider
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.AgentEnrollRequest
import com.mdmesh.proto.AgentEnrollResponse
import com.mdmesh.proto.EventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one-time enrollment handshake. Idempotent: once a server-issued device id is
 * stored, [ensureEnrolled] returns it without contacting the server. A singleton mutex
 * makes the handshake single-flight — the enroll token is single-use, so concurrent
 * callers must never each POST it.
 *
 * Enrollment posts the capability matrix + the enroll token; the server mints and returns the opaque device id, which
 * becomes the agent's permanent identity. It is resumable: the attempt id stored before the request lets a retry whose
 * response was lost get the same device back (servers from protocol 1.9).
 */
@Singleton
class EnrollmentManager @Inject constructor(
    private val api: MdmApi,
    private val identity: DeviceIdentity,
    private val tokenProvider: EnrollTokenProvider,
    private val capabilitySource: CapabilitySource,
    private val eventSink: EventSink,
    private val environment: EnrollmentEnvironment = EnrollmentEnvironment(),
) {

    private val mutex = Mutex()

    /** Recoveries this process made (see [credentialsRejectedBeforeFirstCheckIn]); bounded so it can't loop. */
    @Volatile private var recoveries = 0

    /** The held attempt id was forgotten after the first successful check-in (this process). */
    @Volatile private var attemptForgotten = false

    /**
     * Returns the server-issued device id, enrolling first if necessary. Until the first check-in succeeds it keeps the
     * enrollment's attempt id: if the server rejects the saved credentials meanwhile, it enrolls again with that same
     * attempt, which returns the same device and the credential the server holds, instead of retrying a dead secret.
     */
    suspend fun ensureEnrolled(): String {
        identity.current()?.takeIf { it.isNotBlank() }?.let { id ->
            if (!credentialsRejectedBeforeFirstCheckIn()) {
                forgetAttemptOnceCheckedIn()
                return id
            }
        }
        return mutex.withLock {
            // Re-check inside the lock: a racing caller may have enrolled (or recovered) while we waited.
            val id = identity.current()?.takeIf { it.isNotBlank() }
            when {
                // The enrollment boundary: every failure leaves as a worded EnrollmentException (asEnrollmentFailure).
                id == null -> runCatching { enrollOnce() }.getOrElse { throw asEnrollmentFailure(it) }
                credentialsRejectedBeforeFirstCheckIn() -> recover(id)
                else -> id
            }
        }
    }

    /** The last check-in was refused as unauthorized, and none has ever succeeded: the server holds another secret. */
    private fun credentialsRejectedBeforeFirstCheckIn(): Boolean {
        val status = environment.syncStatus
        return recoveries < MAX_RECOVERIES && status.lastSuccessAtMs == null &&
            status.lastError.value?.message?.contains(UNAUTHORIZED) == true
    }

    /**
     * Enrolls again with the held attempt (same token, same id): the server answers with this attempt's device and its
     * credential (derived from the attempt, so it is the one it stores). Without a held attempt, or when that fails,
     * the device keeps what it has and the next cycle tries again, at most [MAX_RECOVERIES] times per process.
     */
    private suspend fun recover(current: String): String {
        recoveries++
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
        val attempt = token?.let { tokenProvider.heldAttempt(it) }
        val data = if (token == null || attempt == null) {
            null
        } else {
            runCatching { post(token, attempt) }.getOrElse { if (it is CancellationException) throw it else null }
        }
        if (data == null) return current
        persist(data)
        return data.deviceId
    }

    /** After the first successful check-in the attempt can recover nothing: drop it. */
    private suspend fun forgetAttemptOnceCheckedIn() {
        if (attemptForgotten || environment.syncStatus.lastSuccessAtMs == null) return
        runCatching { tokenProvider.clearEnrollAttempt() }.onSuccess { attemptForgotten = true }
    }


    /**
     * One enrollment. Its caller ([ensureEnrolled]) is the enrollment boundary: every failure leaves there as an
     * [EnrollmentException] whose message is for the person holding the device (the agent's screen shows it while it
     * isn't enrolled) and which the callers log and retry; an unexpected one reads "Enrollment didn't go through.
     * Trying again automatically." (its type and message stay in the exception's cause, for the log). Cancellation and
     * VM errors pass through unchanged.
     */
    private suspend fun enrollOnce(): String {
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
            ?: throw EnrollmentException(EnrollmentFailure.NO_CODE)
        val data = post(token, storedAttempt(token))
        persist(data)
        runCatching { eventSink.record(EventType.ENROLLED) }
        return data.deviceId
    }

    /**
     * The POST already used the token server-side, so persistence must survive cancellation (e.g. WorkManager
     * REPLACE/stop) or the credentials are lost. Id and secret are saved together; the attempt id is kept until the
     * first successful check-in (see [ensureEnrolled]).
     */
    private suspend fun persist(data: AgentEnrollResponse) {
        withContext(NonCancellable) {
            identity.saveCredentials(data.deviceId, data.deviceSecret)
        }
    }

    /**
     * The attempt id for [token], stored BEFORE the request: if its response is lost (Wi-Fi drop, the process ends),
     * every retry, even after a restart, sends the same id and the server answers with the device this enrollment
     * already created instead of "code already used". When it can't be stored (a storage error), the enroll still
     * works, just not resumably.
     */
    private suspend fun storedAttempt(token: String): String? = try {
        tokenProvider.enrollAttempt(token)
    } catch (ignored: IOException) {
        null
    }

    /** POSTs the enrollment; the server's answer (a refusal as an [EnrollmentException] naming its key). */
    private suspend fun post(token: String, attempt: String?): AgentEnrollResponse {
        // deviceId is unknown pre-enrollment; the server ignores it and issues one.
        val matrix = capabilitySource.matrix(deviceId = "")
        val request = AgentEnrollRequest(
            enrollToken = token,
            agent = matrix.agent,
            device = matrix.device,
            capabilities = matrix.capabilities,
            hardwareId = runCatching { environment.hardwareIdSource.get() }.getOrNull(),
            enrollAttempt = attempt,
        )
        val response = api.enroll(request)
        val data = response.data
        if (!response.isOk || data == null) {
            throw EnrollmentException(EnrollmentFailure.forServer(response.message))
        }
        return data
    }

    /**
     * A failure in words the person holding the device can act on ([EnrollmentFailure]: network, TLS, HTTP and parse
     * failures are named, anything else is "didn't go through"); the callers retry it. An [EnrollmentException] (a
     * server refusal, no code) is already worded; cancellation and VM errors pass through unchanged.
     */
    private fun asEnrollmentFailure(t: Throwable): Throwable = when (t) {
        is CancellationException, is Error, is EnrollmentException -> t
        else -> EnrollmentException(EnrollmentFailure.forException(t, host()), t)
    }

    /** The server's host for messages; the address itself when it isn't one. */
    private fun host(): String? = runCatching {
        val raw = environment.serverAddress.baseUrl()
        raw.toHttpUrlOrNull()?.host ?: raw
    }.getOrNull()
}

/**
 * What enrollment reads about this device and its server besides the token: the stable hardware id it reports, the
 * server address its messages name, and the sync status (whether a check-in has succeeded or was refused as
 * unauthorized). Grouped so tests that don't care can leave them out.
 */
class EnrollmentEnvironment @Inject constructor(
    val hardwareIdSource: HardwareIdSource,
    val serverAddress: ServerAddress,
    val syncStatus: SyncStatus,
) {
    /** Tests: no hardware id; the address and the sync status only when a test needs them. */
    constructor(
        serverAddress: ServerAddress = ServerAddress { "" },
        syncStatus: SyncStatus = SyncStatus(),
    ) : this(HardwareIdSource { null }, serverAddress, syncStatus)
}

/**
 * Enrollment could not complete. [message] is for the person holding the device ([EnrollmentFailure]); a server
 * refusal ends with its key, which [CheckInWorker] uses to stop retrying a code that can never work.
 */
class EnrollmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The check-in refusal for credentials the server doesn't accept (AgentResource). */
private const val UNAUTHORIZED = "error.agent.unauthorized"

/** Recoveries per process: a device whose credentials keep being refused stops re-enrolling and keeps retrying. */
private const val MAX_RECOVERIES = 3
