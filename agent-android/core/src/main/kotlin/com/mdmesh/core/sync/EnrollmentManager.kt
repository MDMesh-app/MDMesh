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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
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

    /** Returns the server-issued device id, enrolling first if necessary. */
    suspend fun ensureEnrolled(): String {
        identity.current()?.takeIf { it.isNotBlank() }?.let { return it }
        return mutex.withLock {
            // Re-check inside the lock: a racing caller may have enrolled while we waited.
            identity.current()?.takeIf { it.isNotBlank() } ?: enroll()
        }
    }

    private suspend fun enroll(): String {
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
            ?: throw EnrollmentException(EnrollmentFailure.NO_CODE)
        val data = post(token, storedAttempt(token))
        // The POST above already burned the single-use token server-side, so persistence must
        // survive cancellation (e.g. WorkManager REPLACE/stop) or the credentials are lost forever.
        withContext(NonCancellable) {
            // Persist id + per-device secret together; the secret authenticates every check-in.
            identity.saveCredentials(data.deviceId, data.deviceSecret)
            // The attempt can resume nothing now (the server closes it at the first check-in): don't keep it.
            runCatching { tokenProvider.clearEnrollAttempt() }
            runCatching { eventSink.record(EventType.ENROLLED) }
        }
        return data.deviceId
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

    /** POSTs the enrollment; the server's answer, or an [EnrollmentException] that says why there is none. */
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
        val response = runCatching { api.enroll(request) }.getOrElse { throw asEnrollmentFailure(it) }
        val data = response.data
        if (!response.isOk || data == null) {
            throw EnrollmentException(EnrollmentFailure.forServer(response.message))
        }
        return data
    }

    /**
     * Network, TLS, HTTP and parse failures become an [EnrollmentException] in words the person holding the device can
     * act on (it's what the agent's screen shows while it isn't enrolled); the caller retries them. Anything else,
     * cancellation first of all, passes through unchanged.
     */
    private fun asEnrollmentFailure(t: Throwable): Throwable = when (t) {
        is IOException, is HttpException, is SerializationException ->
            EnrollmentException(EnrollmentFailure.forException(t, host()), t)
        else -> t
    }

    /** The server's host for messages; the address itself when it isn't one. */
    private fun host(): String? = runCatching {
        val raw = environment.serverAddress.baseUrl()
        raw.toHttpUrlOrNull()?.host ?: raw
    }.getOrNull()
}

/**
 * What enrollment reads about this device and its server besides the token: the stable hardware id it reports and the
 * server address its messages name. Grouped so tests that don't care can leave both out.
 */
class EnrollmentEnvironment @Inject constructor(
    val hardwareIdSource: HardwareIdSource,
    val serverAddress: ServerAddress,
) {
    /** Tests: no hardware id, no address. */
    constructor() : this(HardwareIdSource { null }, ServerAddress { "" })
}

/**
 * Enrollment could not complete. [message] is for the person holding the device ([EnrollmentFailure]); a server
 * refusal ends with its key, which [CheckInWorker] uses to stop retrying a code that can never work.
 */
class EnrollmentException(message: String, cause: Throwable? = null) : Exception(message, cause)
