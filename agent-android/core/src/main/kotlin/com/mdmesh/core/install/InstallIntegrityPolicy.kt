package com.mdmesh.core.install

import com.mdmesh.proto.AgentCheckInResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether every `app.install` must carry a SHA-256 ([InstallRequest.integrityProblem]). The server says so on each
 * check-in ([AgentCheckInResponse.installIntegrity]), before the commands of that response run; a server that
 * predates the field omits it, and the agent then installs as before (a hash that is present is still verified).
 */
@Singleton
class InstallIntegrityPolicy @Inject constructor() {

    /** True while the last check-in said the server puts a SHA-256 on every install it queues. */
    @Volatile
    var required: Boolean = false
        private set

    /** Records the check-in's `installIntegrity` value (null when the server doesn't send one). */
    fun update(installIntegrity: String?) {
        required = installIntegrity == AgentCheckInResponse.INSTALL_INTEGRITY_REQUIRED
    }

    /** Why [req] must not be installed under the current policy, or null when it may proceed. */
    fun problem(req: InstallRequest): String? = if (required) req.integrityProblem else null
}
