package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.EnrollTokenProvider
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.AgentEnrollRequest
import com.mdmesh.proto.EventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * response was lost get the same device back (servers from protocol 1.6).
 */
@Singleton
class EnrollmentManager @Inject constructor(
    private val api: MdmApi,
    private val identity: DeviceIdentity,
    private val tokenProvider: EnrollTokenProvider,
    private val capabilitySource: CapabilitySource,
    private val eventSink: EventSink,
    private val hardwareIdSource: HardwareIdSource = HardwareIdSource { null },
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
            ?: throw EnrollmentException("no enrollment token available")

        // Stored BEFORE the request: if its response is lost (Wi-Fi drop, the process ends), every retry, even after a
        // restart, sends the same id and the server answers with the device this enrollment already created instead
        // of "code already used". Without it (not stored), the enroll still works, just not resumably.
        val attempt = try {
            tokenProvider.enrollAttempt(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        // deviceId is unknown pre-enrollment; the server ignores it and issues one.
        val matrix = capabilitySource.matrix(deviceId = "")
        val response = api.enroll(
            AgentEnrollRequest(
                enrollToken = token,
                agent = matrix.agent,
                device = matrix.device,
                capabilities = matrix.capabilities,
                hardwareId = runCatching { hardwareIdSource.get() }.getOrNull(),
                enrollAttempt = attempt,
            ),
        )

        val data = response.data
        if (!response.isOk || data == null) {
            throw EnrollmentException(response.message ?: "enrollment rejected")
        }
        // The POST above already burned the single-use token server-side, so persistence must
        // survive cancellation (e.g. WorkManager REPLACE/stop) or the credentials are lost forever.
        withContext(NonCancellable) {
            // Persist id + per-device secret together; the secret authenticates every check-in.
            identity.saveCredentials(data.deviceId, data.deviceSecret)
            runCatching { eventSink.record(EventType.ENROLLED) }
        }
        return data.deviceId
    }
}

/** Enrollment could not complete (no token, or server rejected). Lets the worker retry. */
class EnrollmentException(message: String) : Exception(message)
