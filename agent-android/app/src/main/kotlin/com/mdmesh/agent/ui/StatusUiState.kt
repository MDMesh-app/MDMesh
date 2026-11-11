package com.mdmesh.agent.ui

/**
 * Everything the status screen shows, as one immutable value. [StatusScreen] draws it; nothing else touches the
 * views. Built by [of] from the device's facts, so the mapping is tested on the JVM.
 */
data class StatusUiState(
    val deviceOwner: Boolean,
    val deviceId: DeviceIdUi,
    val serverUrl: String,
    val kiosk: KioskCardUi,
    /** Why the last Return to kiosk failed; shown in the Kiosk card while Return is offered. */
    val returnFailure: String?,
    /** A value just copied ("Copied" shows under it). */
    val copied: StatusCopy.Target? = null,
) {
    /** The Device ID line: not read yet, the server-issued id, or still enrolling (with the last error, if any). */
    sealed interface DeviceIdUi {
        data object Loading : DeviceIdUi
        data class Known(val id: String) : DeviceIdUi
        data class Enrolling(val lastError: String?, val lastErrorAtMs: Long?) : DeviceIdUi
    }

    companion object {
        @Suppress("LongParameterList") // the screen's inputs, named at the one call site
        fun of(
            deviceOwner: Boolean,
            locked: Boolean,
            configured: Boolean,
            deviceId: String?,
            deviceIdLoaded: Boolean,
            lastError: String?,
            lastErrorAtMs: Long?,
            serverUrl: String,
            returning: Boolean,
            returnFailure: String?,
        ): StatusUiState {
            val kiosk = KioskCardUi.of(deviceOwner, locked, configured, returning)
            return StatusUiState(
                deviceOwner = deviceOwner,
                deviceId = when {
                    !deviceIdLoaded -> DeviceIdUi.Loading
                    !deviceId.isNullOrBlank() -> DeviceIdUi.Known(deviceId)
                    else -> DeviceIdUi.Enrolling(lastError, lastErrorAtMs.takeIf { lastError != null })
                },
                serverUrl = serverUrl,
                kiosk = kiosk,
                returnFailure = returnFailure.takeIf { kiosk.returnVisible },
            )
        }
    }
}
