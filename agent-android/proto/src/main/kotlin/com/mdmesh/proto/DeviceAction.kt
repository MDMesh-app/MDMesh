package com.mdmesh.proto

/**
 * Open registry of device-action command types. By convention the action's command [type] is
 * identical to its capability token and to the server-side `requiresCapability` string, so there
 * is exactly one string per action and no drift between agent, server, and UI.
 *
 * The agent advertises [ADVERTISED_KEYS] in `capabilities.device`; the server flattens each into a
 * `device.<key>` token (see `AgentCapabilityTokens`).
 */
object DeviceAction {
    const val LOCK = "device.lock"
    const val REBOOT = "device.reboot"
    const val LOCKSCREEN_MESSAGE = "device.lockscreenMessage"
    const val ALERT = "device.alert"
    const val RING = "device.ring"
    const val RING_STOP = "device.ringStop"
    const val PASSCODE_RESET = "device.passcodeReset"
    const val WIPE = "device.wipe"

    /** Set the agent's connectivity power mode. Payload: `{ "mode": "adaptive" | "alwaysOn" }`. */
    const val POWER_MODE = "device.powerMode"

    /** Connectivity power-mode values (see [POWER_MODE]). */
    const val POWER_ADAPTIVE = "adaptive"
    const val POWER_ALWAYS_ON = "alwaysOn"

    /** Set how location is captured. Payload: `{ "mode": "passive" | "active" }`. */
    const val LOCATION_MODE = "device.locationMode"

    /** Location-mode values (see [LOCATION_MODE]). Passive = last-known (cheap); active = fresh fix. */
    const val LOCATION_PASSIVE = "passive"
    const val LOCATION_ACTIVE = "active"

    /** Keys (after the `device.` prefix) advertised in `capabilities.device`. */
    val ADVERTISED_KEYS: List<String> = listOf(
        "lock", "reboot", "lockscreenMessage", "alert", "ring", "ringStop",
        "passcodeReset", "wipe", "powerMode", "locationMode",
    )
}
