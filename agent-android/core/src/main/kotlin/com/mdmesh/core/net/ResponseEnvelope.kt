package com.mdmesh.core.net

import kotlinx.serialization.Serializable

/**
 * The forked Headwind server wraps every REST response in `{status, message, data}`
 * (see `com.hmdm.rest.json.Response`). This is the device-side mirror.
 *
 * [status] is `"OK"`, `"WARNING"`, or `"ERROR"`; on error, [message] carries the
 * server's error key (e.g. `error.agent.token.invalid`) and [data] is null.
 */
@Serializable
data class ResponseEnvelope<T>(
    val status: String? = null,
    val message: String? = null,
    val data: T? = null,
) {
    val isOk: Boolean get() = status == STATUS_OK

    private companion object {
        const val STATUS_OK = "OK"
    }
}
