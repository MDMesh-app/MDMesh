package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.EnrollTokenProvider
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.AgentEnrollRequest
import com.mdmesh.proto.EventType
import javax.inject.Inject

/**
 * Owns the one-time enrollment handshake. Idempotent: once a server-issued device id is
 * stored, [ensureEnrolled] returns it without contacting the server.
 *
 * Enrollment posts the capability matrix + the single-use enroll token; the server
 * mints and returns the opaque device id, which becomes the agent's permanent identity.
 */
class EnrollmentManager @Inject constructor(
    private val api: MdmApi,
    private val identity: DeviceIdentity,
    private val tokenProvider: EnrollTokenProvider,
    private val capabilitySource: CapabilitySource,
    private val eventSink: EventSink,
    private val hardwareIdSource: HardwareIdSource = HardwareIdSource { null },
) {

    /** Returns the server-issued device id, enrolling first if necessary. */
    suspend fun ensureEnrolled(): String {
        identity.current()?.takeIf { it.isNotBlank() }?.let { return it }

        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
            ?: throw EnrollmentException("no enrollment token available")

        // deviceId is unknown pre-enrollment; the server ignores it and issues one.
        val matrix = capabilitySource.matrix(deviceId = "")
        val response = api.enroll(
            AgentEnrollRequest(
                enrollToken = token,
                agent = matrix.agent,
                device = matrix.device,
                capabilities = matrix.capabilities,
                hardwareId = runCatching { hardwareIdSource.get() }.getOrNull(),
            ),
        )

        val data = response.data
        if (!response.isOk || data == null) {
            throw EnrollmentException(response.message ?: "enrollment rejected")
        }
        // Persist id + per-device secret together; the secret authenticates every check-in.
        identity.saveCredentials(data.deviceId, data.deviceSecret)
        runCatching { eventSink.record(EventType.ENROLLED) }
        return data.deviceId
    }
}

/** Enrollment could not complete (no token, or server rejected). Lets the worker retry. */
class EnrollmentException(message: String) : Exception(message)
