package com.mdmesh.agent.ui

/**
 * The status screen's Copy buttons. The values aren't selectable text: the selection toolbar offers Share,
 * Translate and smart "Open" actions that start other apps' activities inside the locked kiosk task.
 */
object StatusCopy {

    /** What can be copied; [label] is the clip's description. */
    enum class Target(val label: String) {
        DEVICE_ID("Device ID"),
        SERVER("Server"),
    }

    /** The value [target] copies in [state]; null when there is none (no device ID yet). */
    fun textOf(state: StatusUiState, target: Target): String? = when (target) {
        Target.DEVICE_ID -> (state.deviceId as? StatusUiState.DeviceIdUi.Known)?.id
        Target.SERVER -> state.serverUrl
    }

    /** Hands [target]'s value to [write] (label, text); false when there is nothing to copy. */
    fun copy(state: StatusUiState, target: Target, write: (String, String) -> Unit): Boolean {
        val text = textOf(state, target) ?: return false
        write(target.label, text)
        return true
    }
}
