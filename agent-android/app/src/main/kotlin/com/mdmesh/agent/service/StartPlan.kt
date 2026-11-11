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
         * Whether this start revives a service Android stopped, rather than one someone started: the heartbeat's
         * start, or the system restarting the START_STICKY service with no intent after the process died (an OEM or
         * low-memory kill). Boot and self-update start it with an intent (BootReceiver), and keep the grace window:
         * an operator is likely at the device then. A revived service needs no check-in of its own either: the
         * heartbeat makes one, and every process start schedules one (MdmApplication).
         */
        fun revived(hasIntent: Boolean, fromHeartbeat: Boolean): Boolean = fromHeartbeat || !hasIntent

        /**
         * A [revived] service neither checks in nor holds the socket for the grace window (a hot socket on battery
         * each time an OEM kills it); it only picks up the credentials and follows the normal power-mode rule.
         */
        fun of(first: Boolean, revived: Boolean, hasCredentials: Boolean): StartPlan {
            val needed = first || !hasCredentials
            return StartPlan(
                grace = first && !revived,
                initialSync = needed && !revived,
                loadCredentials = needed,
            )
        }
    }
}
