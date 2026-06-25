package com.mdmesh.core.net

import com.mdmesh.proto.AgentCheckInRequest
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.AgentEnrollRequest
import com.mdmesh.proto.AgentEnrollResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit contract for the device <-> server agent v1 protocol.
 * Paths and shapes mirror `proto/endpoints.md`.
 *
 * Both calls return the server's `{status,message,data}` envelope. The sync loop is
 * the source of truth; push (MQTT/long-poll) is only an optimisation that triggers an
 * early check-in.
 */
interface MdmApi {

    /** Token-gated enrollment. Returns the server-issued opaque device id. */
    @POST("rest/public/agent/v1/enroll")
    suspend fun enroll(@Body request: AgentEnrollRequest): ResponseEnvelope<AgentEnrollResponse>

    /**
     * Advertise capabilities + ack prior commands; receive the next gated batch.
     * [authorization] is `Bearer <deviceSecret>` (the per-device secret from enrollment).
     */
    @POST("rest/public/agent/v1/checkin")
    suspend fun checkIn(
        @Header("Authorization") authorization: String,
        @Body request: AgentCheckInRequest,
    ): ResponseEnvelope<AgentCheckInResponse>
}
