package com.mdmesh.core.command.handlers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson

/**
 * `kiosk.enter` — put the device into COSU lock-task. Payload: [KioskApplyPayload]
 * (`mode`, `allowedPackages`, `pinPackage`, `features`, `exitMode`, `password`, `theme`). The
 * agent's own package is always allowlisted; [homeComponent] is the agent's kiosk launcher,
 * registered as the persistent HOME. On success the payload is persisted so the launcher can
 * render it and the agent can re-enter on boot.
 */
class KioskEnterHandler(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val homeComponent: ComponentName,
    private val context: Context,
) : CommandHandler {

    override val type: String = "kiosk.enter"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let {
            runCatching { ProtocolJson.json.decodeFromJsonElement(KioskApplyPayload.serializer(), it) }
                .getOrElse { e -> return CommandResults.failed(command, "bad payload: ${e.message}") }
        } ?: KioskApplyPayload()

        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home,
                recents = p.features.recents,
                notifications = p.features.notifications,
                systemInfo = p.features.systemInfo,
                keyguard = p.features.keyguard,
                lockButtons = p.features.lockButtons,
            ),
        )
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage)).distinct()

        // Make our HOME claim active so the launcher can be pinned as the persistent preferred HOME.
        setHomeAlias(enabled = true)

        return when (val r = kiosk.enter(homeComponent, allowed, features)) {
            KioskResult.Ok -> {
                store.save(p)
                // Bring the launcher forward so kiosk takes visible effect immediately (it observes
                // the saved state and renders the grid / pinned app). Best-effort: if a background
                // activity start is blocked, the persistent-HOME claim still routes the next HOME
                // press here.
                foregroundLauncher()
                CommandResults.done(command)
            }
            KioskResult.Unsupported -> {
                setHomeAlias(enabled = false) // revert: never leave a non-DO device claiming HOME
                CommandResults.unsupported(command, "kiosk requires Device Owner")
            }
            is KioskResult.Failed -> {
                setHomeAlias(enabled = false)
                CommandResults.failed(command, r.reason)
            }
        }
    }

    private fun foregroundLauncher() {
        runCatching {
            context.startActivity(
                Intent().setComponent(homeComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun setHomeAlias(enabled: Boolean) {
        runCatching {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                homeComponent, state, PackageManager.DONT_KILL_APP,
            )
        }
    }
}
