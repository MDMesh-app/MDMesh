package com.mdmesh.core.sync

import com.mdmesh.core.capability.CapabilitySource
import com.mdmesh.core.net.MdmApi
import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.core.store.EnrollTokenProvider
import com.mdmesh.proto.AgentCheckInRequest
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.AgentEnrollRequest
import com.mdmesh.proto.AgentEnrollResponse
import com.mdmesh.proto.AgentInfo
import com.mdmesh.proto.Capabilities
import com.mdmesh.proto.CapabilityMatrix
import com.mdmesh.proto.DeviceInfo
import kotlinx.coroutines.CompletableDeferred

/** Shared fakes for the sync/enroll unit tests. Pure JVM — no Android. */

/** No-op event sink for tests that don't assert on events. */
object NoopEventSink : com.mdmesh.core.telemetry.EventSink {
    override fun record(type: String, detail: String?) {}
    override fun drain(): List<com.mdmesh.proto.TelemetryEventDto> = emptyList()
    override fun restore(events: List<com.mdmesh.proto.TelemetryEventDto>) {}
}

class FakeIdentity(
    initialId: String? = null,
    initialSecret: String? = null,
) : DeviceIdentity {
    private var idValue: String? = initialId
    private var secretValue: String? = initialSecret
    var saveCount = 0
        private set

    override suspend fun current(): String? = idValue
    override suspend fun secret(): String? = secretValue
    override suspend fun saveCredentials(id: String, secret: String?) {
        idValue = id
        secretValue = secret
        saveCount++
    }
}

class FakeTokenProvider(private val token: String?, private val attempt: String? = null) : EnrollTokenProvider {
    val attemptRequests = mutableListOf<String>()
    var attemptCleared = 0
        private set
    override suspend fun token(): String? = token
    override suspend fun enrollAttempt(token: String): String? {
        attemptRequests += token
        return attempt
    }
    override suspend fun clearEnrollAttempt() {
        attemptCleared++
    }
    var resumable = false
    /** When the credentials were marked lost (ms), or null. */
    var credentialsLostAt: Long? = null
    val credentialsLost: Boolean get() = credentialsLostAt != null
    var lostWrites = 0
        private set
    override suspend fun setAttemptResumable(resumable: Boolean) {
        this.resumable = resumable
    }
    override suspend fun setCredentialsLost(lost: Boolean) {
        lostWrites++
        credentialsLostAt = if (lost) System.currentTimeMillis() else null
    }
    override suspend fun credentialsLostAt(): Long? = credentialsLostAt
    override suspend fun resumableAttempt(token: String): String? =
        if (attemptCleared == 0 && resumable) attempt else null
}

class FakeCapabilitySource(
    private val capabilities: Capabilities = Capabilities(policy = listOf("wifi")),
) : CapabilitySource {
    override fun matrix(deviceId: String): CapabilityMatrix = CapabilityMatrix(
        agent = AgentInfo(version = "0.1.0", packageName = "com.mdmesh.agent"),
        device = DeviceInfo(id = deviceId, androidSdkInt = 34, isDeviceOwner = true),
        capabilities = capabilities,
    )
}

/** Records calls and returns scripted responses (or throws). */
class FakeMdmApi : MdmApi {
    var enrollResponse: ResponseEnvelope<AgentEnrollResponse> =
        ResponseEnvelope(status = "OK", data = AgentEnrollResponse(deviceId = "srv-1", deviceSecret = "sek-1"))
    var checkInResponse: ResponseEnvelope<AgentCheckInResponse> =
        ResponseEnvelope(status = "OK", data = AgentCheckInResponse())
    var checkInThrows: Throwable? = null

    /** When set, calls suspend until the gate completes — lets tests hold a request in flight. */
    var enrollGate: CompletableDeferred<Unit>? = null
    var checkInGate: CompletableDeferred<Unit>? = null

    val enrollRequests = mutableListOf<AgentEnrollRequest>()
    /** When set, enroll throws it (after recording the request): a request the server may have handled. */
    var enrollThrows: Throwable? = null
    val checkInRequests = mutableListOf<AgentCheckInRequest>()
    val checkInAuth = mutableListOf<String>()

    override suspend fun enroll(request: AgentEnrollRequest): ResponseEnvelope<AgentEnrollResponse> {
        enrollRequests += request
        enrollGate?.await()
        enrollThrows?.let { throw it }
        return enrollResponse
    }

    override suspend fun checkIn(
        authorization: String,
        request: AgentCheckInRequest,
    ): ResponseEnvelope<AgentCheckInResponse> {
        checkInAuth += authorization
        checkInRequests += request
        checkInGate?.await()
        checkInThrows?.let { throw it }
        return checkInResponse
    }
}
