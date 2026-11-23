package com.mdmesh.proto

import kotlinx.serialization.Serializable

/** A device lifecycle event, buffered offline and flushed to the server on the next check-in. */
@Serializable
data class TelemetryEventDto(
    val type: String,
    val ts: Long,
    val detail: String? = null,
)

/** Open registry of event types. */
object EventType {
    const val BOOT = "boot"
    const val APP_INSTALLED = "appInstalled"
    const val APP_UNINSTALLED = "appUninstalled"
    const val COMMAND_RESULT = "commandResult"
    const val CONNECTIVITY = "connectivityChange"
    const val LOW_BATTERY = "lowBattery"
    const val ENROLLED = "enrolled"

    /**
     * A store the storage damaged was recovered; detail "<store> from <mirror|configuration|lock task|empty>" (#220).
     */
    const val STORAGE_RECOVERED = "storageRecovered"

    /** The boot/update re-apply of the newest delivered configuration failed in part; detail names what failed. */
    const val CONFIG_REAPPLY_FAILED = "configReapplyFailed"

    /** An accepted factory reset did not reboot the device in time; the agent resumed and restores its policies. */
    const val WIPE_NOT_STARTED = "wipeNotStarted"

    /** The buffer was full, so older events were dropped; the detail starts with how many. */
    const val EVENTS_DROPPED = "eventsDropped"
}
