package com.mdmesh.agent.service

/** What the check-in service does when it is started. */
internal data class StartPlan(
    /** Hold the socket for the reachability window regardless of power mode. */
    val grace: Boolean,
    /** Run a check-in now. */
    val initialSync: Boolean,
    /** Read the stored credentials and decide on the socket (also done after [initialSync]). */
    val loadCredentials: Boolean,
) {
    companion object {
        /**
         * A start by the heartbeat ([fromHeartbeat]) revives a service Android stopped. The heartbeat checks in
         * itself, under its wakelock, so the service neither checks in nor holds the socket for the grace window
         * (a hot socket on battery each time an OEM kills it); it only picks up the credentials and follows the
         * normal power-mode rule.
         */
        fun of(first: Boolean, fromHeartbeat: Boolean, hasCredentials: Boolean): StartPlan {
            val needed = first || !hasCredentials
            return StartPlan(
                grace = first && !fromHeartbeat,
                initialSync = needed && !fromHeartbeat,
                loadCredentials = needed,
            )
        }
    }
}
