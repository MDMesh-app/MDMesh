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
    /** Who started the service. */
    enum class Starter {
        /** Someone started it with an intent: boot, self-update, provisioning, the app. */
        NORMAL,

        /** The system restarting the START_STICKY service, with no intent, after the process died. */
        STICKY_RESTART,

        /** The heartbeat, reviving a service Android stopped. */
        HEARTBEAT;

        companion object {
            fun of(hasIntent: Boolean, fromHeartbeat: Boolean): Starter = when {
                fromHeartbeat -> HEARTBEAT
                !hasIntent -> STICKY_RESTART
                else -> NORMAL
            }
        }
    }

    companion object {
        /**
         * Only a [Starter.NORMAL] start holds the socket for the grace window: boot and self-update come that way,
         * when an operator is likely at the device. A revived service (an OEM kill, then the heartbeat or a sticky
         * restart) would otherwise hold a hot socket on battery after every kill.
         *
         * The heartbeat checks in itself, under its wakelock, so a service it starts doesn't. A sticky restart does:
         * nothing else would check in right away (the heartbeat can be 10 minutes off, and the start-up worker may
         * wait for a Doze window), and an install queued for the device should not wait that long.
         */
        fun of(first: Boolean, starter: Starter, hasCredentials: Boolean): StartPlan {
            val needed = first || !hasCredentials
            return StartPlan(
                grace = first && starter == Starter.NORMAL,
                initialSync = needed && starter != Starter.HEARTBEAT,
                loadCredentials = needed,
            )
        }
    }
}
