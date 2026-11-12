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

    /** The stored lost-credentials mark ([CredentialsLost]), once read; see [afterCheckIn]. */
    @Volatile private var lostMark: Long? = null
    @Volatile private var lostMarkRead = false

    /** The refused check-in the last recovery acted on: each refusal is recovered once, whatever path runs next. */
    @Volatile private var recoveredFailure: SyncStatus.Failure? = null

    /**
     * Returns the server-issued device id, enrolling first if necessary. Until the first check-in succeeds it keeps the
     * enrollment's attempt id: if the server rejects the saved credentials meanwhile, it resumes that same
     * attempt, which returns the same device and the credential the server holds, instead of retrying a dead secret.
     */
    suspend fun ensureEnrolled(): String {
        identity.current()?.takeIf { it.isNotBlank() }?.let { id ->
            if (!credentialsRejectedBeforeFirstCheckIn()) {
                afterCheckIn()
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
        val failure = status.lastError.value
        return recoveries < MAX_RECOVERIES && status.lastSuccessAtMs == null && failure !== recoveredFailure &&
            failure?.message?.contains(UNAUTHORIZED) == true
    }

    /**
     * Resumes the held attempt (same token, same id, resume only): the server answers with this attempt's device and
     * the credential it stores, and never makes a new device. Only against a server that said it resumes enrollments
     * ([AgentEnrollResponse.resumeSupported]). Runs once per refused check-in ([recoveredFailure]) and at most
     * [MAX_RECOVERIES] times per process. A network failure or a retryable answer leaves things as they are for the
     * next refusal; a final answer ([FINAL_RECOVERY_ANSWERS]) ends recovery: the attempt is dropped and the credentials
     * are marked lost, which persists for the status screen until the device enrolls again.
     */
    private suspend fun recover(current: String): String {
        recoveries++
        recoveredFailure = environment.syncStatus.lastError.value
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
        val attempt = token?.let { tokenProvider.resumableAttempt(it) }
        if (token == null || attempt == null) return current
        val data = runCatching { post(token, attempt, resumeOnly = true) }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            // Logged (no secrets in it). Only a final answer ends recovery; a retryable one (inProgress, failed) or a
            // network failure leaves the attempt for the next refused check-in.
            environment.log.warn("credential recovery failed; the device keeps its saved credentials", failure)
            val final = failure is EnrollmentException &&
                FINAL_RECOVERY_ANSWERS.any { failure.message.orEmpty().endsWith("$it)") }
            if (final) {
                val mark = CredentialsLost.markerOf(environment.syncStatus.lastSuccessAtMs)
                withContext(NonCancellable) {
                    bestEffort { tokenProvider.clearEnrollAttempt() }
                    // Persistent, for the status screen: later check-ins replace the last error, not this.
                    bestEffort { tokenProvider.setCredentialsLost(mark) }
                }
                lostMark = mark
                lostMarkRead = true
            }
            null
        }
        if (data != null) persist(data)
        return data?.deviceId ?: current
    }

    /**
     * Housekeeping once a check-in has succeeded: the attempt can recover nothing any more (dropped once), and
     * credentials marked lost before that success evidently work again (whatever refused them was fixed), so the mark
     * goes ([CredentialsLost]: no clock comparison). Both reads are cached for the process, so a healthy device
     * writes nothing.
     */
    private suspend fun afterCheckIn() {
        val success = environment.syncStatus.lastSuccessAtMs ?: return
        if (!attemptForgotten) {
            bestEffort { tokenProvider.clearEnrollAttempt() }
            attemptForgotten = true
        }
        if (!lostMarkRead) {
            lostMark = try { tokenProvider.credentialsLostMark() } catch (ignored: IOException) { null }
            lostMarkRead = true
        }
        val mark = lostMark
        if (mark != null && !CredentialsLost.stillLost(mark, success)) {
            withContext(NonCancellable) { bestEffort { tokenProvider.setCredentialsLost(null) } }
            lostMark = null
        }
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
        persist(data, resumeSupported = data.resumeSupported == true)
        runCatching { eventSink.record(EventType.ENROLLED) }
        return data.deviceId
    }

    /**
     * The POST already used the token server-side, so persistence must survive cancellation (e.g. WorkManager
     * REPLACE/stop) or the credentials are lost. Id and secret are saved together; the attempt id is kept until the
     * first successful check-in (see [ensureEnrolled]).
     */
    private suspend fun persist(data: AgentEnrollResponse, resumeSupported: Boolean? = null) {
        withContext(NonCancellable) {
            identity.saveCredentials(data.deviceId, data.deviceSecret)
            // Credentials the server accepts again: whatever was lost is not any more.
            bestEffort { tokenProvider.setCredentialsLost(null) }
            lostMark = null
            lostMarkRead = true
            // A new enrollment's answer decides whether its attempt can be resumed (an older server: no).
            resumeSupported?.let { bestEffort { tokenProvider.setAttemptResumable(it) } }
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
    private suspend fun post(token: String, attempt: String?, resumeOnly: Boolean = false): AgentEnrollResponse {
        // deviceId is unknown pre-enrollment; the server ignores it and issues one.
        val matrix = capabilitySource.matrix(deviceId = "")
        val request = AgentEnrollRequest(
            enrollToken = token,
            agent = matrix.agent,
            device = matrix.device,
            capabilities = matrix.capabilities,
            hardwareId = runCatching { environment.hardwareIdSource.get() }.getOrNull(),
            enrollAttempt = attempt,
            resumeOnly = resumeOnly.takeIf { it },
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
class EnrollmentEnvironment(
    val hardwareIdSource: HardwareIdSource,
    val serverAddress: ServerAddress,
    val syncStatus: SyncStatus,
    val log: EnrollmentLog,
) {
    @Inject
    constructor(hardwareIdSource: HardwareIdSource, serverAddress: ServerAddress, syncStatus: SyncStatus) :
        this(hardwareIdSource, serverAddress, syncStatus, AndroidEnrollmentLog)

    /** Tests: no hardware id; the address, the sync status and the log only when a test needs them. */
    constructor(
        serverAddress: ServerAddress = ServerAddress { "" },
        syncStatus: SyncStatus = SyncStatus(),
        log: EnrollmentLog = EnrollmentLog { _, _ -> },
    ) : this(HardwareIdSource { null }, serverAddress, syncStatus, log)
}

/**
 * Enrollment could not complete. [message] is for the person holding the device ([EnrollmentFailure]); a server
 * refusal ends with its key, which [CheckInWorker] uses to stop retrying a code that can never work.
 */
class EnrollmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The check-in refusal for credentials the server doesn't accept (AgentResource). */
private const val UNAUTHORIZED = "error.agent.unauthorized"

/** Server answers after which a recovery can never succeed: the attempt is dropped and the credentials marked lost. */
private val FINAL_RECOVERY_ANSWERS = listOf(
    "error.agent.enrollment.notResumable", "error.agent.token.used", "error.agent.token.invalid",
)

/** Recoveries per process: a device whose credentials keep being refused stops re-enrolling and keeps retrying. */
private const val MAX_RECOVERIES = 3

/** A store write whose failure (a storage error) must not undo what already happened; cancellation still propagates. */
private suspend fun bestEffort(write: suspend () -> Unit) {
    try {
        write()
    } catch (ignored: IOException) {
        // DataStore failed; the next enrollment or check-in writes it again.
    }
}
