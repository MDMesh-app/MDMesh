package com.mdmesh.proto

/**
 * Registry of app-management capability keys the agent advertises in `capabilities.appManagement`.
 * The server flattens each into an `app.<key>` token (see `AgentCapabilityTokens`), which is the
 * `requiresCapability` the `app.install` command is gated on — e.g. key `silentInstall` →
 * token `app.silentInstall`. Keys are only advertised when the agent can genuinely perform them
 * (silent install needs Device Owner; see CapabilityCollector wiring).
 */
object AppManagement {
    /** Silent PackageInstaller install/upgrade as Device Owner (gates `app.install`). */
    const val SILENT_INSTALL = "silentInstall"

    /** Keys (before the `app.` prefix) advertised in `capabilities.appManagement` when Device Owner. */
    val DEVICE_OWNER_KEYS: List<String> = listOf(SILENT_INSTALL)
}
